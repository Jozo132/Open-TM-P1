package com.github.opentmp1

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.opentmp1.camera.FrameParser
import com.github.opentmp1.camera.ThermalCameraDriver
import com.github.opentmp1.databinding.ActivityMainBinding
import com.github.opentmp1.ui.ColormapManager
import com.github.opentmp1.camera.UsbConnectionException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.github.opentmp1.USB_PERMISSION"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var driver: ThermalCameraDriver? = null
    // Track driver being initialized (not yet assigned to `driver`)
    // so forceDisconnect can cancel it mid-init.
    @Volatile private var pendingDriver: ThermalCameraDriver? = null
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception", throwable)
        runOnUiThread {
            showStatus(
                "${getString(R.string.error_prefix)}${throwable.message ?: "Unknown error"}",
                error = true
            )
        }
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + coroutineExceptionHandler)
    private var streamJob: Job? = null

    // Connection generation: monotonically incrementing token.
    // Every new connect or forced stop bumps this. Old coroutines compare
    // their captured generation to the current value and bail if stale.
    private val connectionGeneration = AtomicLong(0)

    // Mutex serializes all USB lifecycle operations (connect, disconnect,
    // permission result, attach/detach handling) so they never overlap.
    private val usbLifecycleMutex = Mutex()

    // Debounce / dedup for USB broadcasts
    private var lastAttachTime = 0L
    private var lastAttachDeviceName: String? = null
    private var lastDetachTime = 0L
    private var lastDetachDeviceName: String? = null
    private val BROADCAST_DEDUP_MS = 1000L

    // --- State ---
    private var gainHigh = true
    private var videoMode = false
    private var isRecording = false
    private var showTempHud = true

    // --- Diagnostics tracking ---
    private var frameCount = 0L
    private var droppedFrames = 0L
    private var streamStartTime = 0L
    private var lastFpsTime = 0L
    private var fpsFrameCount = 0
    private var currentFps = 0f
    private var lastCalibrationTime: String? = null
    private var connectedDevice: UsbDevice? = null

    // --- Power monitoring ---
    private var batteryLevel = -1           // 0-100 %
    private var batteryVoltage = -1         // millivolts
    private var batteryCurrentNow = 0       // microamps (negative = discharging)
    private var batteryTemperature = -1     // tenths of degC
    private var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
    private var batteryPlugged = 0          // 0 = unplugged
    private var powerWarningShown = false
    private var lastBatteryLevel = -1
    private var lastBatteryLevelTime = 0L
    private var batteryDrainRate = 0f       // %/minute

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    // --- Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize persistent file logger (prunes logs older than 7 days)
        AppLogger.init(this)
        AppLogger.i(TAG, "onCreate - app starting")

        // Prevent unhandled exceptions from crashing the whole system (some phones reboot)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            AppLogger.e(TAG, "UNCAUGHT on ${thread.name}", throwable)
            try {
                runOnUiThread {
                    showStatus(
                        getString(R.string.error_fatal, throwable.message ?: "Unknown error"),
                        error = true
                    )
                }
            } catch (_: Exception) {
                // If we can't even show the UI error, fall through to default handler
            }
            // Still delegate to the default handler so the OS knows the app crashed,
            // but the error is now logged and the user sees a message first.
            defaultHandler?.uncaughtException(thread, throwable)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Full-screen immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setupCameraControls()
        setupTopBar()
        setupBottomBar()
        setupQuickSettings()
        setupPanels()
        setupLogViewer()
        setupDebugConsole()
        registerUsbReceiver()
        registerBatteryReceiver()

        AppLogger.i(TAG, "UI setup complete, looking for camera...")

        // If launched by USB_DEVICE_ATTACHED, the device is in the intent
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && isP1Camera(device)) {
            AppLogger.i(TAG, "Launched via USB attach intent for ${device.deviceName}")
            requestPermission(device)
        } else {
            findAndConnectCamera()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With singleTask, USB_DEVICE_ATTACHED arrives here instead of creating a new Activity.
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && isP1Camera(device)) {
            AppLogger.i(TAG, "onNewIntent: USB attached ${device.deviceName} VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            requestPermission(device)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "onDestroy - app shutting down")
        AppLogger.onNewEntry = null   // stop pushing to dead UI
        stopStreaming()
        scope.cancel()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // --- USB permission & connection ---
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLogger.i(TAG, "USB permission result: granted=$granted device=${device?.deviceName}")
                        if (granted && device != null) {
                            scope.launch { connectCamera(device) }
                        } else {
                            AppLogger.w(TAG, "USB permission denied by user")
                            showStatus(getString(R.string.permission_denied), error = true)
                        }
                    }
                    // USB_DEVICE_ATTACHED is handled by onNewIntent (singleTask launch mode)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        AppLogger.i(TAG, "USB detached: ${device?.deviceName}")
                        if (device != null && isP1Camera(device)) {
                            // Dedup: ignore if same device detached within BROADCAST_DEDUP_MS
                            val now = SystemClock.elapsedRealtime()
                            if (device.deviceName == lastDetachDeviceName &&
                                now - lastDetachTime < BROADCAST_DEDUP_MS
                            ) {
                                AppLogger.w(TAG, "Ignoring duplicate detach for ${device.deviceName}")
                                return
                            }
                            lastDetachDeviceName = device.deviceName
                            lastDetachTime = now

                            // Hard cancel everything immediately - no recovery on dead handle
                            scope.launch { forceDisconnect("detach broadcast") }
                            showStatus(getString(R.string.disconnected))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling USB event: ${intent.action}", e)
                AppLogger.e(TAG, "USB event error (${intent.action})", e)
                showStatus(
                    "${getString(R.string.error_prefix)}USB event error: ${e.message}",
                    error = true
                )
            }
        }
    }

    // --- Battery / power monitoring ---
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = if (scale > 0) (level * 100) / scale else -1
            batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            batteryPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            // Read instantaneous current if available (API 21+)
            try {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batteryCurrentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (_: Exception) {}

            // Calculate drain rate (%/min)
            val now = SystemClock.elapsedRealtime()
            if (lastBatteryLevel >= 0 && batteryLevel in 0..100) {
                val dtMin = (now - lastBatteryLevelTime) / 60000f
                if (dtMin >= 0.5f) {
                    batteryDrainRate = (lastBatteryLevel - batteryLevel) / dtMin
                    lastBatteryLevel = batteryLevel
                    lastBatteryLevelTime = now
                }
            } else {
                lastBatteryLevel = batteryLevel
                lastBatteryLevelTime = now
            }

            checkPowerWarnings()
            updateDiagnosticsIfVisible()
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun checkPowerWarnings() {
        val streaming = driver?.isStreaming() == true
        if (!streaming) {
            powerWarningShown = false
            return
        }

        val isDischarging = batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING ||
                (batteryPlugged == 0 && batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING)

        // Warning conditions:
        // 1) Battery voltage dropped below 3.4V (danger zone for Li-ion)
        // 2) Drain rate is very high (>3%/min - phone will die within ~30 min)
        // 3) Battery level critically low while camera is active
        val lowVoltage = batteryVoltage in 1..3400
        val highDrain = batteryDrainRate > 3.0f && isDischarging
        val criticalLevel = batteryLevel in 1..5 && isDischarging

        if ((lowVoltage || highDrain || criticalLevel) && !powerWarningShown) {
            powerWarningShown = true
            val reason = when {
                lowVoltage -> getString(R.string.power_warn_voltage, batteryVoltage / 1000f)
                criticalLevel -> getString(R.string.power_warn_critical, batteryLevel)
                highDrain -> getString(R.string.power_warn_drain, batteryDrainRate)
                else -> ""
            }
            Log.w(TAG, "POWER WARNING: $reason (V=${batteryVoltage}mV, I=${batteryCurrentNow}uA, " +
                    "level=$batteryLevel%, drain=${batteryDrainRate}%/min)")
            AppLogger.w(TAG, "POWER WARNING: $reason (V=${batteryVoltage}mV I=${batteryCurrentNow}uA " +
                    "level=$batteryLevel% drain=${batteryDrainRate}%/min)")
            toast(reason)
        }

        // Reset the warning flag when conditions recover
        if (!lowVoltage && !highDrain && !criticalLevel) {
            powerWarningShown = false
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            // USB_DEVICE_ATTACHED handled via onNewIntent (singleTask), not broadcast
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun findAndConnectCamera() {
        val device = usbManager.deviceList.values.firstOrNull { isP1Camera(it) }
        if (device != null) {
            requestPermission(device)
        } else {
            showStatus(getString(R.string.camera_not_found))
        }
    }

    private fun requestPermission(device: UsbDevice) {
        try {
            if (usbManager.hasPermission(device)) {
                scope.launch { connectCamera(device) }
            } else {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                // Intent must be explicit (package set) for Android 14+ (API 34)
                val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                val permIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                usbManager.requestPermission(device, permIntent)
                showStatus(getString(R.string.requesting_permission))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission", e)
            showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
            updateStatusDot(StatusState.ERROR)
        }
    }

    private fun isP1Camera(device: UsbDevice): Boolean =
        device.vendorId == ThermalCameraDriver.VENDOR_ID &&
        device.productId == ThermalCameraDriver.PRODUCT_ID

    // --- Camera lifecycle ---
    private fun logPowerSnapshot(label: String) {
        try {
            // Read battery data directly from sticky intent if cached values are stale (-1)
            var level = batteryLevel
            var voltage = batteryVoltage
            var temp = batteryTemperature
            var plugged = batteryPlugged
            var status = batteryStatus
            if (level < 0 || voltage < 0) {
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                }
            }
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val curNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            AppLogger.i(TAG, "\u26A1 [$label] bat=$level% V=${voltage}mV " +
                    "I=${curNow}\u00B5A (${curNow/1000}mA) T=${temp/10f}\u00B0C " +
                    "plugged=$plugged status=$status")
        } catch (e: Exception) {
            AppLogger.i(TAG, "\u26A1 [$label] bat=$batteryLevel% V=${batteryVoltage}mV " +
                    "(current unavailable) plugged=$batteryPlugged")
        }
    }

    private var connectRetryCount = 0

    /**
     * Single entry point for all connect attempts. Acquires the USB lifecycle
     * mutex so that connect, disconnect, detach, and permission handling never
     * overlap. No auto-retry - if it fails, it just reports the error.
     */
    private suspend fun connectCamera(device: UsbDevice) = usbLifecycleMutex.withLock {
        // Tear down any previous connection inside the lock
        teardownLocked("connectCamera")

        connectedDevice = device
        showStatus(getString(R.string.connecting))
        updateStatusDot(StatusState.CONNECTING)

        val myGeneration = connectionGeneration.incrementAndGet()
        AppLogger.i(TAG, "connectCamera: ${device.deviceName} gen=$myGeneration " +
                "(VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)})")
        logPowerSnapshot("pre-connect")

        val newDriver = ThermalCameraDriver(usbManager, device)
        pendingDriver = newDriver
        var connected = false
        try {
            // Check generation before blocking connect
            if (connectionGeneration.get() != myGeneration) {
                AppLogger.w(TAG, "Stale connect (gen $myGeneration != ${connectionGeneration.get()}), aborting")
                return@withLock
            }
            AppLogger.i(TAG, "Calling driver.connect() [gen=$myGeneration]...")
            withContext(Dispatchers.IO) { newDriver.connect() }

            // Check generation AFTER connect - detach may have arrived while we blocked
            if (connectionGeneration.get() != myGeneration) {
                AppLogger.w(TAG, "Generation changed during connect ($myGeneration -> ${connectionGeneration.get()}), aborting")
                try { newDriver.disconnect() } catch (_: Exception) {}
                return@withLock
            }

            driver = newDriver
            pendingDriver = null
            connected = true
            connectRetryCount = 0
            logPowerSnapshot("post-connect")
            AppLogger.i(TAG, "Camera connected [gen=$myGeneration] - starting stream")
            resetDiagnostics()
            showStreaming(true)
            logPowerSnapshot("streaming-start")
            launchStreamLoop(newDriver, myGeneration)
        } catch (e: UsbConnectionException) {
            if (connectionGeneration.get() != myGeneration) return@withLock
            Log.e(TAG, "Camera connect failed: USB error", e)
            AppLogger.e(TAG, "Camera connect failed: USB error", e)
            logPowerSnapshot("connect-failed")
            showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
            updateStatusDot(StatusState.ERROR)
        } catch (e: SecurityException) {
            if (connectionGeneration.get() != myGeneration) return@withLock
            Log.e(TAG, "Camera connect failed: permission error", e)
            AppLogger.e(TAG, "Camera connect failed: permission error", e)
            showStatus(getString(R.string.error_usb_permission), error = true)
            updateStatusDot(StatusState.ERROR)
        } catch (e: Exception) {
            if (connectionGeneration.get() != myGeneration) return@withLock
            Log.e(TAG, "Camera connect failed", e)
            AppLogger.e(TAG, "Camera connect failed", e)
            logPowerSnapshot("connect-failed")
            showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
            updateStatusDot(StatusState.ERROR)
        } finally {
            if (!connected) {
                pendingDriver = null
                try { newDriver.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private var lastPowerLogTime = 0L

    private fun launchStreamLoop(d: ThermalCameraDriver, generation: Long) {
        streamJob = scope.launch(Dispatchers.IO) {
            var invalidFrameStreak = 0

            AppLogger.i(TAG, "Stream loop started [gen=$generation]")

            try {
                while (isActive && d.isStreaming() && connectionGeneration.get() == generation) {
                    val now = SystemClock.elapsedRealtime()

                    try {
                        val rawFrame = d.readFrame()

                        // Generation check after potentially blocking bulkTransfer
                        if (connectionGeneration.get() != generation) {
                            AppLogger.w(TAG, "Stale stream gen=$generation (now=${connectionGeneration.get()}), exiting")
                            return@launch
                        }

                        if (rawFrame == null) {
                            droppedFrames++
                            // After first USB -1, do NOT try recovery commands on this handle.
                            // Just count errors; driver will throw UsbConnectionException at MAX.
                            continue
                        }

                        val frame = FrameParser.parse(rawFrame)

                        if (!frame.isPlausible()) {
                            invalidFrameStreak++
                            droppedFrames++
                            if (invalidFrameStreak <= 5) {
                                AppLogger.w(TAG, "Invalid frame (center=" +
                                        "${FrameParser.formatTemp(frame.centerTemp())}), " +
                                        "streak=$invalidFrameStreak \u2014 skipping")
                            }
                            continue
                        }
                        invalidFrameStreak = 0

                        frameCount++
                        fpsFrameCount++

                        if (frameCount <= 5) {
                            AppLogger.i(TAG, "Frame #$frameCount " +
                                    "(center=${FrameParser.formatTemp(frame.centerTemp())})")
                        }

                        if (now - lastFpsTime >= 1000) {
                            currentFps = fpsFrameCount * 1000f / (now - lastFpsTime)
                            fpsFrameCount = 0
                            lastFpsTime = now
                        }

                        if (now - lastPowerLogTime >= 5_000) {
                            lastPowerLogTime = now
                            AppLogger.i(TAG, "Stream [gen=$generation]: " +
                                    "fps=${String.format("%.1f", currentFps)}, " +
                                    "frames=$frameCount, dropped=$droppedFrames, " +
                                    "center=${FrameParser.formatTemp(frame.centerTemp())}")
                            AppLogger.power(batteryLevel, batteryVoltage, batteryCurrentNow,
                                batteryTemperature, batteryDrainRate, batteryPlugged != 0)
                        }

                        withContext(Dispatchers.Main) {
                            if (connectionGeneration.get() != generation) return@withContext
                            binding.thermalView.updateFrame(frame)
                            updateTemperatureHud(frame)
                            updateFpsDisplay()
                            updateDiagnosticsIfVisible()
                        }

                        // Throttle USB bus to ~8-9 fps to prevent Samsung A10's USB host
                        // controller from crashing under sustained bulk transfer load.
                        // The camera produces ~25 fps but the A10's PMIC/EHCI can't sustain
                        // continuous 77KB bulk reads without eventually triggering a kernel panic.
                        delay(100)

                    } catch (e: UsbConnectionException) {
                        // First USB failure -> close everything, no recovery commands
                        if (connectionGeneration.get() != generation) return@launch
                        Log.e(TAG, "USB connection lost [gen=$generation]", e)
                        AppLogger.e(TAG, "USB connection lost [gen=$generation]", e)
                        logPowerSnapshot("stream-lost")
                        withContext(Dispatchers.Main) {
                            forceDisconnect("USB error")
                            showStatus(
                                "${getString(R.string.error_prefix)}${e.message}",
                                error = true
                            )
                            updateStatusDot(StatusState.ERROR)
                        }
                        return@launch
                    } catch (e: Exception) {
                        if (connectionGeneration.get() != generation) return@launch
                        Log.w(TAG, "Frame read error [gen=$generation]", e)
                        droppedFrames++
                        delay(100)
                    }
                }

                if (connectionGeneration.get() != generation) {
                    AppLogger.i(TAG, "Stream loop exiting: stale generation $generation")
                }
            } catch (e: Exception) {
                if (connectionGeneration.get() != generation) return@launch
                Log.e(TAG, "Stream loop terminated unexpectedly [gen=$generation]", e)
                AppLogger.e(TAG, "Stream loop terminated unexpectedly [gen=$generation]", e)
                withContext(Dispatchers.Main) {
                    forceDisconnect("stream error")
                    showStatus(
                        "${getString(R.string.error_prefix)}${e.message ?: "Stream interrupted"}",
                        error = true
                    )
                    updateStatusDot(StatusState.ERROR)
                }
            }
        }
    }

    /**
     * Hard-cancel everything: bump generation, cancel all jobs, disconnect driver.
     * After this, no old coroutine can touch the USB handle.
     * Acquires the USB lifecycle mutex to prevent overlap with connectCamera.
     */
    private suspend fun forceDisconnect(reason: String) = usbLifecycleMutex.withLock {
        teardownLocked(reason)
    }

    /**
     * Inner teardown that MUST only be called while holding [usbLifecycleMutex].
     */
    private fun teardownLocked(reason: String) {
        val gen = connectionGeneration.incrementAndGet()
        AppLogger.i(TAG, "teardownLocked($reason) - generation now $gen")
        streamJob?.cancel()
        streamJob = null
        // Cancel pending driver that hasn't been assigned to `driver` yet
        try {
            pendingDriver?.let {
                it.cancelled = true
                it.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling pending driver", e)
        }
        pendingDriver = null
        try {
            driver?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during driver disconnect", e)
            AppLogger.e(TAG, "Error during driver disconnect", e)
        }
        driver = null
        connectedDevice = null
        showStreaming(false)
    }

    private fun stopStreaming() {
        // Called from onDestroy - use teardownLocked directly (no coroutine scope available).
        // This is safe because onDestroy runs after all other lifecycle callbacks.
        teardownLocked("stopStreaming")
    }

    private fun resetDiagnostics() {
        frameCount = 0
        droppedFrames = 0
        fpsFrameCount = 0
        currentFps = 0f
        streamStartTime = SystemClock.elapsedRealtime()
        lastFpsTime = streamStartTime
    }

    // --- UI setup ---
    private fun setupCameraControls() {
        binding.thermalView.onTouchTemp = { temp ->
            binding.tvTouch.text = "+ ${FrameParser.formatTemp(temp)}"
            binding.tvTouch.visibility = View.VISIBLE
        }
    }

    private fun setupTopBar() {
        binding.btnSettings.setOnClickListener { togglePanel(Panel.SETTINGS) }
        binding.btnDiagnostics.setOnClickListener { togglePanel(Panel.DIAGNOSTICS) }
    }

    private fun setupBottomBar() {
        // Capture button
        binding.btnCapture.setOnClickListener {
            if (videoMode) {
                toggleRecording()
            } else {
                saveScreenshot()
            }
        }

        // Mode toggle
        binding.tvModePhoto.setOnClickListener { setMode(false) }
        binding.tvModeVideo.setOnClickListener { setMode(true) }
    }

    private fun setupQuickSettings() {
        binding.btnQuickCalibrate.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                AppLogger.i(TAG, "NUC shutter triggered")
                driver?.triggerShutter()
                withContext(Dispatchers.Main) {
                    lastCalibrationTime = timeFormatter.format(Date())
                    toast(getString(R.string.shutter_triggered))
                    updateCalibrationPanel()
                }
            }
        }

        binding.btnQuickGain.setOnClickListener {
            toggleGain()
        }

        binding.btnQuickColormap.setOnClickListener {
            cycleColormap()
        }

        binding.btnQuickGallery.setOnClickListener {
            // Placeholder: could open gallery intent
            toast("Gallery")
        }
    }

    private fun setupPanels() {
        // Dim backdrop closes all panels
        binding.panelDimBackdrop.setOnClickListener { closeAllPanels() }

        // Settings panel
        binding.settingsPanel.btnCloseSettings.setOnClickListener { closeAllPanels() }
        setupSettingsPanel()

        // Calibration panel
        binding.calibrationPanel.btnCloseCalibration.setOnClickListener { closeAllPanels() }
        setupCalibrationPanel()

        // Diagnostics panel
        binding.diagnosticsPanel.btnCloseDiagnostics.setOnClickListener { closeAllPanels() }
    }

    private fun setupLogViewer() {
        binding.diagnosticsPanel.btnViewLogs.setOnClickListener {
            showLogViewer()
        }
        binding.btnCloseLogViewer.setOnClickListener {
            binding.logViewerOverlay.visibility = View.GONE
        }
        binding.btnExportLogs.setOnClickListener {
            exportLogs()
        }
        binding.btnClearLogs.setOnClickListener {
            AppLogger.clearLogs()
            binding.tvLogContent.text = ""
            binding.tvDebugConsole.text = ""
            toast(getString(R.string.log_cleared))
        }
    }

    private fun setupDebugConsole() {
        // Show last 10 lines from ring buffer on startup
        binding.tvDebugConsole.text = AppLogger.getTailLines(10)

        // Register live push: every new log entry immediately appends a line
        AppLogger.onNewEntry = { entry ->
            runOnUiThread {
                val current = binding.tvDebugConsole.text.toString()
                val lines = if (current.isEmpty()) emptyList() else current.lines()
                val kept = lines.takeLast(9)  // keep 9, new entry will be the 10th
                binding.tvDebugConsole.text = (kept + entry).joinToString("\n")
            }
        }
    }

    private fun showLogViewer() {
        closeAllPanels()
        scope.launch(Dispatchers.IO) {
            val content = AppLogger.readLogs()
            // Keep only the last 100 lines so the viewer opens instantly
            val tail = content.lines().takeLast(100).joinToString("\n")
            withContext(Dispatchers.Main) {
                binding.tvLogContent.text = tail
                binding.logViewerOverlay.visibility = View.VISIBLE
                // Scroll to bottom
                binding.logScrollView.post {
                    binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun exportLogs() {
        scope.launch(Dispatchers.IO) {
            val content = AppLogger.readLogs().sanitizeLogText()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "ThermalP1_log_$stamp.log"

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportLogQ(content, fileName)
            } else {
                exportLogLegacy(content, fileName)
            }

            withContext(Dispatchers.Main) {
                if (success) toast(getString(R.string.log_exported))
                else toast(getString(R.string.log_export_failed))
            }
        }
    }

    private fun exportLogQ(content: String, fileName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ThermalP1")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            AppLogger.i(TAG, "Log exported to Downloads/$fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Log export (Q+) failed", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLogLegacy(content: String, fileName: String): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ThermalP1"
            ).also { it.mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            AppLogger.i(TAG, "Log exported to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Log export (legacy) failed", e)
            false
        }
    }

    private fun String.sanitizeLogText(): String {
        val sb = StringBuilder(length)
        for (c in this) {
            when (c) {
                '\u00b5' -> sb.append('u')    // micro sign µ
                '\u00b0' -> sb.append('d')    // degree sign °
                '\u2014', '\u2013' -> sb.append('-')  // em/en dash
                '\u2192' -> sb.append('-')    // arrow
                '\u2026' -> sb.append("...")  // ellipsis
                '\u2295' -> sb.append('+')    // circled plus
                '\u2193' -> sb.append('v')    // down arrow
                '\u2191' -> sb.append('^')    // up arrow
                else -> if (c.code < 128) sb.append(c) else sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun setupSettingsPanel() {
        // Colormap radio group
        binding.settingsPanel.rbIronbow.isChecked = true
        binding.settingsPanel.rgColormap.setOnCheckedChangeListener { _, checkedId ->
            val colormap = when (checkedId) {
                R.id.rbIronbow -> ColormapManager.Colormap.IRONBOW
                R.id.rbRainbow -> ColormapManager.Colormap.RAINBOW
                R.id.rbGrayscale -> ColormapManager.Colormap.GRAYSCALE
                R.id.rbHot -> ColormapManager.Colormap.HOT
                R.id.rbPlasma -> ColormapManager.Colormap.PLASMA
                else -> return@setOnCheckedChangeListener
            }
            binding.thermalView.colormap = colormap
            binding.tvColormapLabel.text = colormap.displayName
        }

        // Overlay switches
        binding.settingsPanel.swReticule.setOnCheckedChangeListener { _, checked ->
            binding.thermalView.showReticule = checked
        }
        binding.settingsPanel.swMinMax.setOnCheckedChangeListener { _, checked ->
            binding.thermalView.showMinMax = checked
        }
        binding.settingsPanel.swColorbar.setOnCheckedChangeListener { _, checked ->
            binding.thermalView.showColorbar = checked
        }
        binding.settingsPanel.swTempHud.setOnCheckedChangeListener { _, checked ->
            showTempHud = checked
            binding.tempHud.visibility = if (checked && driver?.isStreaming() == true) View.VISIBLE else View.GONE
        }
    }

    private fun setupCalibrationPanel() {
        binding.calibrationPanel.btnTriggerNuc.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                driver?.triggerShutter()
                withContext(Dispatchers.Main) {
                    lastCalibrationTime = timeFormatter.format(Date())
                    toast(getString(R.string.shutter_triggered))
                    updateCalibrationPanel()
                }
            }
        }

        binding.calibrationPanel.btnToggleGain.setOnClickListener {
            toggleGain()
            updateCalibrationPanel()
        }
    }

    // --- Mode switching ---
    private fun setMode(isVideo: Boolean) {
        videoMode = isVideo
        if (isVideo) {
            binding.tvModePhoto.background = null
            binding.tvModePhoto.setTextColor(getColor(R.color.text_secondary))
            binding.tvModeVideo.setBackgroundResource(R.drawable.mode_tab_active)
            binding.tvModeVideo.setTextColor(getColor(R.color.colorBackground))
            binding.btnCapture.setBackgroundResource(
                if (isRecording) R.drawable.capture_button_recording
                else R.drawable.capture_button_video
            )
        } else {
            binding.tvModeVideo.background = null
            binding.tvModeVideo.setTextColor(getColor(R.color.text_secondary))
            binding.tvModePhoto.setBackgroundResource(R.drawable.mode_tab_active)
            binding.tvModePhoto.setTextColor(getColor(R.color.colorBackground))
            binding.btnCapture.setBackgroundResource(R.drawable.capture_button_photo)
            if (isRecording) {
                stopRecording()
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // Video recording requires MediaCodec encoding of thermal frames.
        // The UI is fully wired; actual encoding will be added in a future update.
        toast(getString(R.string.recording_not_available))
    }

    private fun stopRecording() {
        isRecording = false
        binding.tvRecording.visibility = View.GONE
        binding.btnCapture.setBackgroundResource(
            if (videoMode) R.drawable.capture_button_video
            else R.drawable.capture_button_photo
        )
    }

    // --- Gain & colormap ---
    private fun toggleGain() {
        gainHigh = !gainHigh
        val isHigh = gainHigh
        AppLogger.i(TAG, "Gain changed to ${if (isHigh) "HIGH" else "LOW"}")
        scope.launch(Dispatchers.IO) {
            if (isHigh) driver?.setGainHigh() else driver?.setGainLow()
            withContext(Dispatchers.Main) {
                binding.tvGainLabel.text = if (isHigh) "HIGH" else "LOW"
                toast(if (isHigh) getString(R.string.gain_high) else getString(R.string.gain_low))
                updateCalibrationPanel()
            }
        }
    }

    private fun cycleColormap() {
        val next = ColormapManager.next(binding.thermalView.colormap)
        binding.thermalView.colormap = next
        binding.tvColormapLabel.text = next.displayName
        // Sync radio button in settings panel
        val rbId = when (next) {
            ColormapManager.Colormap.IRONBOW -> R.id.rbIronbow
            ColormapManager.Colormap.RAINBOW -> R.id.rbRainbow
            ColormapManager.Colormap.GRAYSCALE -> R.id.rbGrayscale
            ColormapManager.Colormap.HOT -> R.id.rbHot
            ColormapManager.Colormap.PLASMA -> R.id.rbPlasma
        }
        binding.settingsPanel.rgColormap.check(rbId)
    }

    // --- Panel management ---
    private enum class Panel { SETTINGS, CALIBRATION, DIAGNOSTICS }
    private var openPanel: Panel? = null

    private fun togglePanel(panel: Panel) {
        if (openPanel == panel) {
            closeAllPanels()
        } else {
            openPanel(panel)
        }
    }

    private fun openPanel(panel: Panel) {
        closeAllPanels(animate = false)
        openPanel = panel

        binding.panelDimBackdrop.visibility = View.VISIBLE
        binding.panelDimBackdrop.alpha = 0f
        binding.panelDimBackdrop.animate().alpha(1f).setDuration(200).start()

        val panelView = when (panel) {
            Panel.SETTINGS -> {
                syncSettingsPanel()
                binding.settingsPanel.root
            }
            Panel.CALIBRATION -> {
                updateCalibrationPanel()
                binding.calibrationPanel.root
            }
            Panel.DIAGNOSTICS -> {
                updateDiagnosticsPanel()
                binding.diagnosticsPanel.root
            }
        }

        panelView.visibility = View.VISIBLE
        val panelWidth = resources.getDimension(R.dimen.panel_width)
        panelView.translationX = panelView.width.toFloat().coerceAtLeast(panelWidth)
        panelView.animate()
            .translationX(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeAllPanels(animate: Boolean = true) {
        openPanel = null

        if (animate) {
            binding.panelDimBackdrop.animate().alpha(0f).setDuration(200).withEndAction {
                binding.panelDimBackdrop.visibility = View.GONE
            }.start()

            animatePanelClose(binding.settingsPanel.root)
            animatePanelClose(binding.calibrationPanel.root)
            animatePanelClose(binding.diagnosticsPanel.root)
        } else {
            binding.panelDimBackdrop.visibility = View.GONE
            binding.settingsPanel.root.visibility = View.GONE
            binding.calibrationPanel.root.visibility = View.GONE
            binding.diagnosticsPanel.root.visibility = View.GONE
        }
    }

    private fun animatePanelClose(view: View) {
        if (view.visibility != View.VISIBLE) return
        val panelWidth = resources.getDimension(R.dimen.panel_width)
        view.animate()
            .translationX(view.width.toFloat().coerceAtLeast(panelWidth))
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    // --- Panel content sync ---
    private fun syncSettingsPanel() {
        val rbId = when (binding.thermalView.colormap) {
            ColormapManager.Colormap.IRONBOW -> R.id.rbIronbow
            ColormapManager.Colormap.RAINBOW -> R.id.rbRainbow
            ColormapManager.Colormap.GRAYSCALE -> R.id.rbGrayscale
            ColormapManager.Colormap.HOT -> R.id.rbHot
            ColormapManager.Colormap.PLASMA -> R.id.rbPlasma
        }
        binding.settingsPanel.rgColormap.check(rbId)
        binding.settingsPanel.swReticule.isChecked = binding.thermalView.showReticule
        binding.settingsPanel.swMinMax.isChecked = binding.thermalView.showMinMax
        binding.settingsPanel.swColorbar.isChecked = binding.thermalView.showColorbar
    }

    private fun updateCalibrationPanel() {
        val gainText = if (gainHigh) getString(R.string.settings_gain_high) else getString(R.string.settings_gain_low)
        binding.calibrationPanel.tvCurrentGain.text = getString(R.string.calibration_current_gain, gainText)
        binding.calibrationPanel.btnToggleGain.text =
            if (gainHigh) getString(R.string.calibration_switch_low) else getString(R.string.calibration_switch_high)
        binding.calibrationPanel.tvLastCalibration.text =
            lastCalibrationTime ?: getString(R.string.calibration_never)

        val hasCamera = driver?.isStreaming() == true
        binding.calibrationPanel.btnTriggerNuc.isEnabled = hasCamera
        binding.calibrationPanel.btnTriggerNuc.alpha = if (hasCamera) 1.0f else 0.4f
        binding.calibrationPanel.btnToggleGain.isEnabled = hasCamera
        binding.calibrationPanel.btnToggleGain.alpha = if (hasCamera) 1.0f else 0.4f
    }

    private fun updateDiagnosticsPanel() {
        val device = connectedDevice
        val streaming = driver?.isStreaming() == true

        // Connection
        binding.diagnosticsPanel.tvDiagStatus.text = when {
            streaming -> getString(R.string.streaming)
            device != null -> getString(R.string.connecting)
            else -> getString(R.string.diagnostics_not_connected)
        }
        binding.diagnosticsPanel.tvDiagStatus.setTextColor(
            getColor(when {
                streaming -> R.color.status_ok
                device != null -> R.color.status_connecting
                else -> R.color.status_error
            })
        )

        if (device != null) {
            binding.diagnosticsPanel.tvDiagUsbDevice.text = device.deviceName
            binding.diagnosticsPanel.tvDiagVidPid.text = "0x%04X / 0x%04X".format(device.vendorId, device.productId)
            binding.diagnosticsPanel.tvDiagInterface.text = "USB 2.0 Bulk"
        } else {
            binding.diagnosticsPanel.tvDiagUsbDevice.text = getString(R.string.diagnostics_na)
            binding.diagnosticsPanel.tvDiagVidPid.text = getString(R.string.diagnostics_na)
            binding.diagnosticsPanel.tvDiagInterface.text = getString(R.string.diagnostics_na)
        }

        // Performance
        binding.diagnosticsPanel.tvDiagFps.text = if (streaming) "%.1f fps".format(currentFps) else getString(R.string.diagnostics_na)
        binding.diagnosticsPanel.tvDiagFrameCount.text = "%,d".format(frameCount)
        binding.diagnosticsPanel.tvDiagDropped.text = "%,d".format(droppedFrames)
        binding.diagnosticsPanel.tvDiagUptime.text = if (streaming) formatUptime() else getString(R.string.diagnostics_na)

        // Temperature
        val frame = binding.thermalView.lastFramePublic
        if (frame != null) {
            binding.diagnosticsPanel.tvDiagSceneRange.text =
                "${FrameParser.formatTemp(frame.minTemp())} - ${FrameParser.formatTemp(frame.maxTemp())}"
            binding.diagnosticsPanel.tvDiagCenter.text = FrameParser.formatTemp(frame.centerTemp())
        }

        // Power
        updateDiagnosticsPower()

        // Logs summary
        binding.diagnosticsPanel.tvDiagLogSummary.text = AppLogger.getSummary()
    }

    private fun updateDiagnosticsPower() {
        val na = getString(R.string.diagnostics_na)

        // Battery level
        if (batteryLevel in 0..100) {
            binding.diagnosticsPanel.tvDiagBatteryLevel.text = "$batteryLevel%"
            binding.diagnosticsPanel.tvDiagBatteryLevel.setTextColor(
                getColor(when {
                    batteryLevel <= 5 -> R.color.status_error
                    batteryLevel <= 15 -> R.color.status_warning
                    else -> R.color.text_primary
                })
            )
        } else {
            binding.diagnosticsPanel.tvDiagBatteryLevel.text = na
        }

        // Voltage
        if (batteryVoltage > 0) {
            val volts = batteryVoltage / 1000f
            binding.diagnosticsPanel.tvDiagBatteryVoltage.text = "%.3f V".format(volts)
            binding.diagnosticsPanel.tvDiagBatteryVoltage.setTextColor(
                getColor(when {
                    batteryVoltage <= 3400 -> R.color.status_error
                    batteryVoltage <= 3600 -> R.color.status_warning
                    else -> R.color.text_primary
                })
            )
        } else {
            binding.diagnosticsPanel.tvDiagBatteryVoltage.text = na
        }

        // Current (sign convention varies by device: negative = discharge on most)
        if (batteryCurrentNow != 0) {
            val mA = batteryCurrentNow / 1000f
            binding.diagnosticsPanel.tvDiagCurrentDraw.text = "%.0f mA".format(mA)
            binding.diagnosticsPanel.tvDiagCurrentDraw.setTextColor(
                getColor(when {
                    kotlin.math.abs(mA) > 1500 -> R.color.status_error
                    kotlin.math.abs(mA) > 800 -> R.color.status_warning
                    else -> R.color.text_primary
                })
            )
        } else {
            binding.diagnosticsPanel.tvDiagCurrentDraw.text = na
        }

        // Battery temperature
        if (batteryTemperature > 0) {
            val tempC = batteryTemperature / 10f
            binding.diagnosticsPanel.tvDiagBatteryTemp.text = "%.1f degC".format(tempC)
            binding.diagnosticsPanel.tvDiagBatteryTemp.setTextColor(
                getColor(when {
                    tempC >= 45 -> R.color.status_error
                    tempC >= 40 -> R.color.status_warning
                    else -> R.color.text_primary
                })
            )
        } else {
            binding.diagnosticsPanel.tvDiagBatteryTemp.text = na
        }

        // Drain rate
        if (batteryDrainRate != 0f) {
            binding.diagnosticsPanel.tvDiagDrainRate.text = "%.1f %%/min".format(batteryDrainRate)
            binding.diagnosticsPanel.tvDiagDrainRate.setTextColor(
                getColor(when {
                    batteryDrainRate > 3f -> R.color.status_error
                    batteryDrainRate > 1.5f -> R.color.status_warning
                    else -> R.color.text_primary
                })
            )
        } else {
            binding.diagnosticsPanel.tvDiagDrainRate.text = na
        }

        // Charging status
        val chargingText = when {
            batteryPlugged != 0 && batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            batteryPlugged != 0 && batteryStatus == BatteryManager.BATTERY_STATUS_FULL -> "Full"
            batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging (USB OTG)"
            batteryPlugged == 0 -> "Not charging"
            else -> na
        }
        binding.diagnosticsPanel.tvDiagCharging.text = chargingText
    }

    private fun updateDiagnosticsIfVisible() {
        if (openPanel == Panel.DIAGNOSTICS) {
            updateDiagnosticsPanel()
        }
    }

    // --- UI state management ---
    private enum class StatusState { CONNECTED, DISCONNECTED, CONNECTING, ERROR }

    private fun updateStatusDot(state: StatusState) {
        val dotBg = when (state) {
            StatusState.CONNECTED -> R.drawable.status_indicator_connected
            StatusState.DISCONNECTED -> R.drawable.status_indicator_disconnected
            StatusState.CONNECTING -> R.drawable.status_indicator_connecting
            StatusState.ERROR -> R.drawable.status_indicator_disconnected
        }
        binding.statusDot.setBackgroundResource(dotBg)
    }

    private fun showStatus(message: String, error: Boolean = false) {
        binding.statusOverlay.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(
            if (error) getColor(R.color.status_error) else getColor(R.color.text_primary)
        )
        binding.tvStatusHint.visibility = if (error) View.GONE else View.VISIBLE
        binding.tempHud.visibility = View.GONE
        binding.quickSettings.visibility = View.GONE
        updateStatusDot(if (error) StatusState.ERROR else StatusState.DISCONNECTED)
        setCameraControlsEnabled(false)
    }

    private fun showStreaming(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.statusOverlay.visibility = View.GONE
            binding.tempHud.visibility = if (showTempHud) View.VISIBLE else View.GONE
            binding.quickSettings.visibility = View.VISIBLE
            updateStatusDot(StatusState.CONNECTED)
            setCameraControlsEnabled(true)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.tempHud.visibility = View.GONE
            binding.quickSettings.visibility = View.GONE
            setCameraControlsEnabled(false)
        }
    }

    private fun setCameraControlsEnabled(enabled: Boolean) {
        binding.btnCapture.isEnabled = enabled
        binding.btnCapture.alpha = if (enabled) 1.0f else 0.4f
        binding.btnQuickCalibrate.isEnabled = enabled
        binding.btnQuickCalibrate.alpha = if (enabled) 1.0f else 0.4f
        binding.btnQuickGain.isEnabled = enabled
        binding.btnQuickGain.alpha = if (enabled) 1.0f else 0.4f
        binding.btnQuickColormap.isEnabled = enabled
        binding.btnQuickColormap.alpha = if (enabled) 1.0f else 0.4f
    }

    // --- Temperature & FPS display ---
    private fun updateTemperatureHud(frame: com.github.opentmp1.camera.ParsedFrame) {
        binding.tvCenterLarge.text = FrameParser.formatTemp(frame.centerTemp())
        binding.tvMin.text = "v ${FrameParser.formatTemp(frame.minTemp())}"
        binding.tvMax.text = "^ ${FrameParser.formatTemp(frame.maxTemp())}"
    }

    private fun updateFpsDisplay() {
        binding.tvFps.text = "%.0f fps".format(currentFps)
    }

    private fun formatUptime(): String {
        val elapsed = (SystemClock.elapsedRealtime() - streamStartTime) / 1000
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // --- Screenshot ---
    private fun saveScreenshot() {
        AppLogger.i(TAG, "Screenshot capture")
        val bmp = binding.thermalView.captureBitmap()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ThermalP1_$stamp.png"

        // Flash effect
        binding.thermalView.alpha = 0.5f
        binding.thermalView.animate().alpha(1.0f).setDuration(200).start()

        scope.launch(Dispatchers.IO) {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveScreenshotQ(bmp, fileName)
            } else {
                saveScreenshotLegacy(bmp, fileName)
            }
            withContext(Dispatchers.Main) {
                if (success) toast(getString(R.string.screenshot_saved))
                else toast(getString(R.string.screenshot_failed))
            }
        }
    }

    private fun saveScreenshotQ(bmp: android.graphics.Bitmap, fileName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ThermalP1")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot save failed", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveScreenshotLegacy(bmp: android.graphics.Bitmap, fileName: String): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ThermalP1"
            ).also { it.mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot save failed (legacy)", e)
            false
        }
    }
}

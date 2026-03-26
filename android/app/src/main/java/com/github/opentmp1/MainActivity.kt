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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.github.opentmp1.USB_PERMISSION"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var driver: ThermalCameraDriver? = null
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

    // ── State ────────────────────────────────────────────────────────────────
    private var gainHigh = true
    private var videoMode = false
    private var isRecording = false
    private var showTempHud = true

    // ── Diagnostics tracking ─────────────────────────────────────────────────
    private var frameCount = 0L
    private var droppedFrames = 0L
    private var streamStartTime = 0L
    private var lastFpsTime = 0L
    private var fpsFrameCount = 0
    private var currentFps = 0f
    private var lastCalibrationTime: String? = null
    private var connectedDevice: UsbDevice? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent unhandled exceptions from crashing the whole system (some phones reboot)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
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

        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // True full-screen
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
        registerUsbReceiver()

        // If launched by USB_DEVICE_ATTACHED, the device is in the intent
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && isP1Camera(device)) {
            requestPermission(device)
        } else {
            findAndConnectCamera()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && isP1Camera(device)) {
            requestPermission(device)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        scope.cancel()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    // ── USB permission & connection ───────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            startCamera(device)
                        } else {
                            showStatus(getString(R.string.permission_denied), error = true)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (device != null && isP1Camera(device)) requestPermission(device)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (device != null && isP1Camera(device)) {
                            stopStreaming()
                            connectedDevice = null
                            showStatus(getString(R.string.disconnected))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling USB event: ${intent.action}", e)
                showStatus(
                    "${getString(R.string.error_prefix)}USB event error: ${e.message}",
                    error = true
                )
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
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
                startCamera(device)
            } else {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val permIntent = PendingIntent.getBroadcast(this, 0,
                    Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(device, permIntent)
                showStatus(getString(R.string.connecting))
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

    // ── Camera lifecycle ──────────────────────────────────────────────────────

    private fun startCamera(device: UsbDevice) {
        stopStreaming()
        connectedDevice = device
        showStatus(getString(R.string.connecting))
        updateStatusDot(StatusState.CONNECTING)

        scope.launch {
            val newDriver = ThermalCameraDriver(usbManager, device)
            try {
                withContext(Dispatchers.IO) { newDriver.connect() }
                driver = newDriver
                resetDiagnostics()
                showStreaming(true)
                launchStreamLoop(newDriver)
            } catch (e: UsbConnectionException) {
                Log.e(TAG, "Camera connect failed: USB error", e)
                showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
                updateStatusDot(StatusState.ERROR)
                try { newDriver.disconnect() } catch (_: Exception) {}
            } catch (e: SecurityException) {
                Log.e(TAG, "Camera connect failed: permission error", e)
                showStatus(getString(R.string.error_usb_permission), error = true)
                updateStatusDot(StatusState.ERROR)
                try { newDriver.disconnect() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Camera connect failed", e)
                showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
                updateStatusDot(StatusState.ERROR)
                try { newDriver.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun launchStreamLoop(d: ThermalCameraDriver) {
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && d.isStreaming()) {
                    try {
                        val rawFrame = d.readFrame()
                        if (rawFrame == null) {
                            droppedFrames++
                            continue
                        }
                        val frame = FrameParser.parse(rawFrame)
                        frameCount++
                        fpsFrameCount++

                        // Calculate FPS every second
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFpsTime >= 1000) {
                            currentFps = fpsFrameCount * 1000f / (now - lastFpsTime)
                            fpsFrameCount = 0
                            lastFpsTime = now
                        }

                        withContext(Dispatchers.Main) {
                            binding.thermalView.updateFrame(frame)
                            updateTemperatureHud(frame)
                            updateFpsDisplay()
                            updateDiagnosticsIfVisible()
                        }
                    } catch (e: UsbConnectionException) {
                        // Fatal connection loss — stop streaming and notify user
                        Log.e(TAG, "USB connection lost during streaming", e)
                        withContext(Dispatchers.Main) {
                            stopStreaming()
                            showStatus(
                                "${getString(R.string.error_prefix)}${e.message}",
                                error = true
                            )
                            updateStatusDot(StatusState.ERROR)
                        }
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame read error", e)
                        droppedFrames++
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                // Catch-all for unexpected failures in the loop itself
                Log.e(TAG, "Stream loop terminated unexpectedly", e)
                withContext(Dispatchers.Main) {
                    stopStreaming()
                    showStatus(
                        "${getString(R.string.error_prefix)}${e.message ?: "Stream interrupted"}",
                        error = true
                    )
                    updateStatusDot(StatusState.ERROR)
                }
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        try {
            driver?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during driver disconnect", e)
        }
        driver = null
        showStreaming(false)
    }

    private fun resetDiagnostics() {
        frameCount = 0
        droppedFrames = 0
        fpsFrameCount = 0
        currentFps = 0f
        streamStartTime = SystemClock.elapsedRealtime()
        lastFpsTime = streamStartTime
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupCameraControls() {
        binding.thermalView.onTouchTemp = { temp ->
            binding.tvTouch.text = "⊕ ${FrameParser.formatTemp(temp)}"
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

    // ── Mode switching ────────────────────────────────────────────────────────

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

    // ── Gain & colormap ──────────────────────────────────────────────────────

    private fun toggleGain() {
        gainHigh = !gainHigh
        val isHigh = gainHigh
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

    // ── Panel management ─────────────────────────────────────────────────────

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

    // ── Panel content sync ───────────────────────────────────────────────────

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
                "${FrameParser.formatTemp(frame.minTemp())} – ${FrameParser.formatTemp(frame.maxTemp())}"
            binding.diagnosticsPanel.tvDiagCenter.text = FrameParser.formatTemp(frame.centerTemp())
        }
    }

    private fun updateDiagnosticsIfVisible() {
        if (openPanel == Panel.DIAGNOSTICS) {
            updateDiagnosticsPanel()
        }
    }

    // ── UI state management ──────────────────────────────────────────────────

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
            binding.statusOverlay.visibility = View.GONE
            binding.tempHud.visibility = if (showTempHud) View.VISIBLE else View.GONE
            binding.quickSettings.visibility = View.VISIBLE
            updateStatusDot(StatusState.CONNECTED)
            setCameraControlsEnabled(true)
        } else {
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

    // ── Temperature & FPS display ────────────────────────────────────────────

    private fun updateTemperatureHud(frame: com.github.opentmp1.camera.ParsedFrame) {
        binding.tvCenterLarge.text = FrameParser.formatTemp(frame.centerTemp())
        binding.tvMin.text = "↓ ${FrameParser.formatTemp(frame.minTemp())}"
        binding.tvMax.text = "↑ ${FrameParser.formatTemp(frame.maxTemp())}"
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

    // ── Screenshot ────────────────────────────────────────────────────────────

    private fun saveScreenshot() {
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

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
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.opentmp1.camera.FrameParser
import com.github.opentmp1.camera.ThermalCameraDriver
import com.github.opentmp1.databinding.ActivityMainBinding
import com.github.opentmp1.ui.ColormapManager
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null

    private var gainHigh = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setupButtons()
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
                        showStatus(getString(R.string.disconnected))
                    }
                }
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
    }

    private fun isP1Camera(device: UsbDevice): Boolean =
        device.vendorId == ThermalCameraDriver.VENDOR_ID &&
        device.productId == ThermalCameraDriver.PRODUCT_ID

    // ── Camera lifecycle ──────────────────────────────────────────────────────

    private fun startCamera(device: UsbDevice) {
        stopStreaming()
        showStatus(getString(R.string.connecting))

        scope.launch {
            val newDriver = ThermalCameraDriver(usbManager, device)
            try {
                withContext(Dispatchers.IO) { newDriver.connect() }
                driver = newDriver
                showStreaming(true)
                launchStreamLoop(newDriver)
            } catch (e: Exception) {
                Log.e(TAG, "Camera connect failed", e)
                showStatus("${getString(R.string.error_prefix)}${e.message}", error = true)
            }
        }
    }

    private fun launchStreamLoop(d: ThermalCameraDriver) {
        streamJob = scope.launch(Dispatchers.IO) {
            while (isActive && d.isStreaming()) {
                try {
                    val rawFrame = d.readFrame() ?: continue
                    val frame = FrameParser.parse(rawFrame)

                    withContext(Dispatchers.Main) {
                        binding.thermalView.updateFrame(frame)
                        binding.tvCenter.text = "Center: ${FrameParser.formatTemp(frame.centerTemp())}"
                        binding.tvMin.text    = "Min: ${FrameParser.formatTemp(frame.minTemp())}"
                        binding.tvMax.text    = "Max: ${FrameParser.formatTemp(frame.maxTemp())}"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame read error", e)
                    delay(100)
                }
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        driver?.disconnect()
        driver = null
        showStreaming(false)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnShutter.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                driver?.triggerShutter()
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.shutter_triggered))
                }
            }
        }

        binding.btnGain.setOnClickListener {
            gainHigh = !gainHigh
            val isHigh = gainHigh
            val label = if (isHigh) getString(R.string.gain_high) else getString(R.string.gain_low)
            scope.launch(Dispatchers.IO) {
                if (isHigh) driver?.setGainHigh() else driver?.setGainLow()
                withContext(Dispatchers.Main) {
                    binding.btnGain.text = if (isHigh) "Gain: High" else "Gain: Low"
                    toast(label)
                }
            }
        }

        binding.btnColormap.setOnClickListener {
            binding.thermalView.colormap = ColormapManager.next(binding.thermalView.colormap)
            binding.btnColormap.text = binding.thermalView.colormap.displayName
        }

        binding.btnScreenshot.setOnClickListener {
            saveScreenshot()
        }

        // Touch temperature display
        binding.thermalView.onTouchTemp = { temp ->
            binding.tvTouch.text = "Touch: ${FrameParser.formatTemp(temp)}"
        }
    }

    private fun showStatus(message: String, error: Boolean = false) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(
            if (error) getColor(R.color.status_error) else getColor(R.color.status_connecting)
        )
        binding.tempInfoLayout.visibility = View.GONE
        setCameraButtonsEnabled(false)
    }

    private fun showStreaming(active: Boolean) {
        if (active) {
            binding.tvStatus.visibility = View.GONE
            binding.tempInfoLayout.visibility = View.VISIBLE
            setCameraButtonsEnabled(true)
        } else {
            binding.tempInfoLayout.visibility = View.GONE
            setCameraButtonsEnabled(false)
        }
    }

    private fun setCameraButtonsEnabled(enabled: Boolean) {
        binding.btnShutter.isEnabled = enabled
        binding.btnGain.isEnabled = enabled
        binding.btnScreenshot.isEnabled = enabled
        binding.btnShutter.alpha = if (enabled) 1.0f else 0.4f
        binding.btnGain.alpha = if (enabled) 1.0f else 0.4f
        binding.btnScreenshot.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── Screenshot ────────────────────────────────────────────────────────────

    private fun saveScreenshot() {
        val bmp = binding.thermalView.captureBitmap()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ThermalP1_$stamp.png"

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

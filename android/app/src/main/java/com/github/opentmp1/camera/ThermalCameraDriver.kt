package com.github.opentmp1.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.github.opentmp1.AppLogger

/**
 * USB driver for the Thermal Master P1 camera.
 *
 * Protocol reference: https://github.com/jvdillon/p3-ir-camera
 *
 * P1 specs:
 *   VID = 0x3474, PID = 0x45C2
 *   Resolution: 160×120
 *   Frame size: 77,440 bytes (2 × (2×120+2) × 160)
 *   Frame rate: ~25 fps
 */
class ThermalCameraDriver(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    companion object {
        private const val TAG = "ThermalCameraDriver"

        const val VENDOR_ID  = 0x3474
        const val PRODUCT_ID = 0x45C2

        // Sensor dimensions for P1
        const val SENSOR_W = 160
        const val SENSOR_H = 120

        // Frame size in bytes: 2 bytes per pixel × (2×H+2) rows × W columns
        const val FRAME_SIZE = 2 * (2 * SENSOR_H + 2) * SENSOR_W  // 77,440

        // USB bulk transfer: first transfer = start marker + frame data
        private const val MARKER_SIZE  = 12
        const val TRANSFER1_SIZE = FRAME_SIZE + MARKER_SIZE  // 77,452

        // USB control transfer constants
        private const val RT_VENDOR_OUT_IFACE = 0x41   // OUT | VENDOR | INTERFACE
        private const val RT_VENDOR_IN_IFACE  = 0xC1   // IN  | VENDOR | INTERFACE
        private const val RT_VENDOR_OUT_DEV   = 0x40   // OUT | VENDOR | DEVICE
        private const val REQ_WRITE           = 0x20
        private const val REQ_READ_RESP       = 0x21
        private const val REQ_STATUS          = 0x22
        private const val REQ_STREAM_ENABLE   = 0xEE

        // Pre-computed 18-byte commands (with CRC16)
        private val CMD_START_STREAM = byteArrayOf(
            0x01, 0x2f, 0x81.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x49, 0x30
        )
        private val CMD_GAIN_HIGH = byteArrayOf(
            0x01, 0x2f, 0x41, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x49, 0x39
        )
        private val CMD_GAIN_LOW = byteArrayOf(
            0x01, 0x2f, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3c, 0x3a
        )
        private val CMD_SHUTTER = byteArrayOf(
            0x01, 0x36, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xcd.toByte(), 0x0b
        )

        // Bulk endpoint address (IN)
        private const val ENDPOINT_IN = 0x81

        /** Number of consecutive frame read failures before auto-disconnect. */
        const val MAX_CONSECUTIVE_ERRORS = 30

        /** Threshold to attempt a soft reset (re-send stream command) before giving up. */
        const val SOFT_RESET_THRESHOLD = 10
    }

    private var connection: UsbDeviceConnection? = null
    private var iface0: UsbInterface? = null
    private var iface1Alt0: UsbInterface? = null
    private var iface1Alt1: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null

    @Volatile private var streaming = false
    @Volatile var cancelled = false

    /** Tracks consecutive frame read failures for auto-disconnect. */
    @Volatile var consecutiveErrors = 0
        private set

    /**
     * Open the USB connection and run the full initialization sequence.
     * Must be called from a background thread.
     *
     * @throws UsbConnectionException with a descriptive message on failure.
     */
    fun connect() {
        Log.i(TAG, "Connecting to P1 camera…")
        AppLogger.i(TAG, "Connecting to P1 camera…")

        // Phase 1: Open the USB device
        try {
            connection = usbManager.openDevice(device)
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "USB permission revoked/denied", e)
            throw UsbConnectionException("USB permission was revoked or denied", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open USB device", e)
            throw UsbConnectionException("Failed to open USB device: ${e.message}", e)
        }
        if (connection == null) {
            throw UsbConnectionException(
                "Could not open USB device. " +
                "The device may be in use by another app or the USB connection is unstable."
            )
        }

        // Phase 2: Locate interfaces and alternate settings
        iface0 = null
        iface1Alt0 = null
        iface1Alt1 = null
        bulkIn = null

        Log.d(TAG, "Device has ${device.interfaceCount} interface(s)")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.d(TAG, "  Interface ${iface.id} alt=${iface.alternateSetting} " +
                    "endpoints=${iface.endpointCount}")
            when {
                iface.id == 0 -> iface0 = iface
                iface.id == 1 && iface.alternateSetting == 0 -> iface1Alt0 = iface
                iface.id == 1 && iface.alternateSetting == 1 -> {
                    iface1Alt1 = iface
                    // Find the bulk IN endpoint
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN &&
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                        ) {
                            bulkIn = ep
                            Log.d(TAG, "Bulk IN endpoint found: address=0x${ep.address.toString(16)}")
                        }
                    }
                }
            }
        }

        // Validate that all required interfaces were found
        if (iface0 == null) {
            throw UsbConnectionException(
                "USB device does not have the expected interface layout (Interface 0 missing). " +
                "The camera firmware may be incompatible."
            )
        }
        if (iface1Alt0 == null) {
            throw UsbConnectionException(
                "USB device is missing Interface 1 alt-setting 0. " +
                "The camera firmware may be incompatible."
            )
        }
        if (iface1Alt1 == null || bulkIn == null) {
            throw UsbConnectionException(
                "USB device is missing the streaming interface (Interface 1 alt-setting 1) " +
                "or its bulk IN endpoint. The camera firmware may be incompatible."
            )
        }

        val conn = connection ?: throw UsbConnectionException(
            "USB connection was lost before initialization could complete"
        )

        // Phase 3: Claim interfaces
        try {
            if (!conn.claimInterface(iface0, true)) {
                throw UsbConnectionException(
                    "Failed to claim USB control interface. " +
                    "Another app may be using the camera."
                )
            }
            if (!conn.claimInterface(iface1Alt0, true)) {
                throw UsbConnectionException(
                    "Failed to claim USB streaming interface. " +
                    "Another app may be using the camera."
                )
            }
        } catch (e: Exception) {
            if (e is UsbConnectionException) throw e
            throw UsbConnectionException("Failed to claim USB interfaces: ${e.message}", e)
        }

        // Phase 4: Run initialization sequence
        try {
            initSequence(conn)
        } catch (e: Exception) {
            if (e is UsbConnectionException) throw e
            throw UsbConnectionException(
                "Camera initialization failed: ${e.message}", e
            )
        }
    }

    private fun initSequence(conn: UsbDeviceConnection) {
        // 1. Send initial start-stream command
        AppLogger.i(TAG, "Init phase 1: sending start-stream command")
        sendCommand(conn, CMD_START_STREAM)
        readStatus(conn)
        val resp1 = readResponse(conn, 1)
        readStatus(conn)
        Log.d(TAG, "Init start_stream response: 0x${resp1[0].toInt().and(0xFF).toString(16)}")

        // 2. Brief pause
        AppLogger.i(TAG, "Init phase 2: 1s pause")
        Thread.sleep(1000)
        if (cancelled) throw UsbConnectionException("Connection cancelled during init")

        // 3. Enable streaming interface (alt setting 1)
        AppLogger.i(TAG, "Init phase 3: activating streaming interface")
        val alt1 = iface1Alt1 ?: throw UsbConnectionException(
            "Streaming interface lost during initialization"
        )
        try {
            conn.setInterface(alt1)
        } catch (e: Exception) {
            throw UsbConnectionException(
                "Failed to activate streaming interface: ${e.message}", e
            )
        }
        val streamEnableResult = conn.controlTransfer(
            RT_VENDOR_OUT_DEV, REQ_STREAM_ENABLE, 0, 1, null, 0, 1000
        )
        if (streamEnableResult < 0) {
            Log.w(TAG, "Stream-enable control transfer returned $streamEnableResult")
            throw UsbConnectionException(
                "Failed to enable camera streaming (error code: $streamEnableResult). " +
                "Try reconnecting the camera."
            )
        }

        // 4. Wait for camera ready
        AppLogger.i(TAG, "Init phase 4: 2s wait for camera ready")
        Thread.sleep(2000)
        if (cancelled) throw UsbConnectionException("Connection cancelled during init")

        // 5. Discard any partial data waiting in the pipe
        AppLogger.i(TAG, "Init phase 5: draining stale buffers")
        try {
            val dummy = ByteArray(TRANSFER1_SIZE)
            var totalDiscarded = 0
            for (i in 0 until 5) {
                val n = conn.bulkTransfer(bulkIn, dummy, dummy.size, 200)
                if (n <= 0) break
                totalDiscarded += n
            }
            Log.d(TAG, "Discard phase: drained $totalDiscarded bytes")
        } catch (e: Exception) {
            Log.d(TAG, "Discard phase: ${e.message} (non-fatal)")
        }

        // 6. Final start stream
        AppLogger.i(TAG, "Init phase 6: final start-stream command")
        sendCommand(conn, CMD_START_STREAM)
        readStatus(conn)
        val resp2 = readResponse(conn, 1)
        readStatus(conn)
        AppLogger.i(TAG, "Camera ready — streaming enabled")

        consecutiveErrors = 0
        streaming = true
    }

    /**
     * Read one raw frame from the camera.
     * Returns [FRAME_SIZE] bytes of pixel data, or null on error.
     * Must be called from a background thread while [streaming] == true.
     *
     * @throws UsbConnectionException if too many consecutive read failures occur.
     */
    fun readFrame(): ByteArray? {
        val conn = connection ?: return null
        val ep   = bulkIn ?: return null

        // Transfer 1: start marker (12 bytes) + all pixel data (FRAME_SIZE bytes)
        val buf1 = ByteArray(TRANSFER1_SIZE)
        val n1 = conn.bulkTransfer(ep, buf1, TRANSFER1_SIZE, 10000)
        if (n1 != TRANSFER1_SIZE) {
            Log.w(TAG, "Transfer 1 got $n1 bytes, expected $TRANSFER1_SIZE")
            if (n1 <= 0) {
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    AppLogger.e(TAG, "Lost camera: $consecutiveErrors consecutive read failures")
                    throw UsbConnectionException(
                        "Lost communication with camera ($consecutiveErrors consecutive read failures)"
                    )
                }
                return null
            }
        }

        // Transfer 2: end marker (discard)
        val buf2 = ByteArray(MARKER_SIZE)
        val n2 = conn.bulkTransfer(ep, buf2, MARKER_SIZE, 2000)
        if (n2 < 0) {
            Log.w(TAG, "Transfer 2 (end marker) failed with code $n2")
        }

        // Reset consecutive error counter on successful read
        consecutiveErrors = 0

        // Pixel data starts after the 12-byte start marker
        return buf1.copyOfRange(MARKER_SIZE, TRANSFER1_SIZE)
    }

    /**
     * Attempt to recover streaming without a full disconnect/reconnect.
     * Re-sends the start-stream command and drains stale buffers.
     * @return true if the reset succeeded and streaming may resume.
     */
    fun softReset(): Boolean {
        val conn = connection ?: return false
        val ep = bulkIn ?: return false
        AppLogger.i(TAG, "Attempting soft reset (re-sending stream command)…")
        return try {
            // Drain stale data
            val dummy = ByteArray(TRANSFER1_SIZE)
            for (i in 0 until 3) {
                val n = conn.bulkTransfer(ep, dummy, dummy.size, 100)
                if (n <= 0) break
            }
            // Re-send start-stream command
            sendCommand(conn, CMD_START_STREAM)
            readStatus(conn)
            readResponse(conn, 1)
            readStatus(conn)
            Thread.sleep(500)
            consecutiveErrors = 0
            AppLogger.i(TAG, "Soft reset succeeded")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Soft reset failed", e)
            false
        }
    }

    /** Trigger the shutter / NUC calibration. */
    fun triggerShutter() {
        val conn = connection ?: return
        try {
            AppLogger.i(TAG, "Sending shutter/NUC command")
            sendCommand(conn, CMD_SHUTTER)
            readStatus(conn)
        } catch (e: Exception) {
            Log.e(TAG, "Shutter command failed", e)
            AppLogger.e(TAG, "Shutter command failed", e)
        }
    }

    /** Switch to high gain mode (-20°C to 150°C). */
    fun setGainHigh() {
        val conn = connection ?: return
        try {
            sendCommand(conn, CMD_GAIN_HIGH)
            readStatus(conn)
        } catch (e: Exception) {
            Log.e(TAG, "Set gain high failed", e)
        }
    }

    /** Switch to low gain mode (0°C to 550°C). */
    fun setGainLow() {
        val conn = connection ?: return
        try {
            sendCommand(conn, CMD_GAIN_LOW)
            readStatus(conn)
        } catch (e: Exception) {
            Log.e(TAG, "Set gain low failed", e)
        }
    }

    /**
     * Stop streaming and release all USB resources.
     */
    fun disconnect() {
        AppLogger.i(TAG, "Disconnecting camera")
        cancelled = true
        streaming = false
        try {
            val conn = connection ?: return
            // Switch back to inactive alt setting
            try { iface1Alt0?.let { conn.setInterface(it) } } catch (e: Exception) {
                Log.w(TAG, "Failed to reset interface alt setting", e)
            }
            try { iface0?.let { conn.releaseInterface(it) } } catch (e: Exception) {
                Log.w(TAG, "Failed to release interface 0", e)
            }
            try { iface1Alt0?.let { conn.releaseInterface(it) } } catch (e: Exception) {
                Log.w(TAG, "Failed to release interface 1", e)
            }
            conn.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        } finally {
            connection = null
            bulkIn = null
        }
    }

    fun isStreaming(): Boolean = streaming

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sendCommand(conn: UsbDeviceConnection, cmd: ByteArray) {
        val result = conn.controlTransfer(
            RT_VENDOR_OUT_IFACE, REQ_WRITE, 0, 0, cmd, cmd.size, 1000
        )
        if (result < 0) {
            Log.w(TAG, "sendCommand: controlTransfer returned $result")
            throw UsbTransferException("USB command transfer failed (error code: $result)")
        }
    }

    private fun readStatus(conn: UsbDeviceConnection): Byte {
        val buf = ByteArray(1)
        val result = conn.controlTransfer(
            RT_VENDOR_IN_IFACE, REQ_STATUS, 0, 0, buf, 1, 1000
        )
        if (result < 0) {
            Log.w(TAG, "readStatus: controlTransfer returned $result")
            throw UsbTransferException("USB status read failed (error code: $result)")
        }
        return buf[0]
    }

    private fun readResponse(conn: UsbDeviceConnection, length: Int): ByteArray {
        val buf = ByteArray(length)
        val result = conn.controlTransfer(
            RT_VENDOR_IN_IFACE, REQ_READ_RESP, 0, 0, buf, length, 1000
        )
        if (result < 0) {
            Log.w(TAG, "readResponse: controlTransfer returned $result")
            throw UsbTransferException("USB response read failed (error code: $result)")
        }
        return buf
    }
}

/** Exception indicating a USB connection or initialization failure. */
class UsbConnectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Exception indicating a USB control transfer failure. */
class UsbTransferException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

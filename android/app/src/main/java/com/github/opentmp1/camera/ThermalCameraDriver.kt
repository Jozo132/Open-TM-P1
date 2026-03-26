package com.github.opentmp1.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

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
    }

    private var connection: UsbDeviceConnection? = null
    private var iface0: UsbInterface? = null
    private var iface1Alt0: UsbInterface? = null
    private var iface1Alt1: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null

    @Volatile private var streaming = false

    /**
     * Open the USB connection and run the full initialization sequence.
     * Must be called from a background thread.
     */
    fun connect() {
        Log.i(TAG, "Connecting to P1 camera…")

        connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Could not open USB device")

        // Locate interfaces and alternate settings
        iface0 = null
        iface1Alt0 = null
        iface1Alt1 = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
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

        val conn = connection!!

        // Claim both interfaces
        conn.claimInterface(iface0 ?: error("Interface 0 not found"), true)
        val alt0 = iface1Alt0 ?: error("Interface 1 alt-0 not found")
        conn.claimInterface(alt0, true)

        // Initialization sequence (mirrors the desktop Python driver)
        initSequence(conn)
    }

    private fun initSequence(conn: UsbDeviceConnection) {
        // 1. Read device name
        sendCommand(conn, CMD_START_STREAM)  // initial start stream
        readStatus(conn)
        val resp1 = readResponse(conn, 1)
        readStatus(conn)
        Log.d(TAG, "Init start_stream response: 0x${resp1[0].toInt().and(0xFF).toString(16)}")

        // 2. Brief pause
        Thread.sleep(1000)

        // 3. Enable streaming interface (alt setting 1)
        val alt1 = iface1Alt1 ?: error("Interface 1 alt-1 not found")
        conn.setInterface(alt1)
        conn.controlTransfer(RT_VENDOR_OUT_DEV, REQ_STREAM_ENABLE, 0, 1, null, 0, 1000)

        // 4. Wait for camera ready
        Thread.sleep(2000)

        // 5. Discard any partial data waiting in the pipe
        try {
            val dummy = ByteArray(TRANSFER1_SIZE)
            conn.bulkTransfer(bulkIn, dummy, dummy.size, 100)
        } catch (_: Exception) {}

        // 6. Final start stream
        sendCommand(conn, CMD_START_STREAM)
        readStatus(conn)
        val resp2 = readResponse(conn, 1)
        readStatus(conn)
        Log.i(TAG, "Camera ready. Stream response: 0x${resp2[0].toInt().and(0xFF).toString(16)}")

        streaming = true
    }

    /**
     * Read one raw frame from the camera.
     * Returns [FRAME_SIZE] bytes of pixel data, or null on error.
     * Must be called from a background thread while [streaming] == true.
     */
    fun readFrame(): ByteArray? {
        val conn = connection ?: return null
        val ep   = bulkIn ?: return null

        // Transfer 1: start marker (12 bytes) + all pixel data (FRAME_SIZE bytes)
        val buf1 = ByteArray(TRANSFER1_SIZE)
        val n1 = conn.bulkTransfer(ep, buf1, TRANSFER1_SIZE, 10000)
        if (n1 != TRANSFER1_SIZE) {
            Log.w(TAG, "Transfer 1 got $n1 bytes, expected $TRANSFER1_SIZE")
            if (n1 <= 0) return null
        }

        // Transfer 2: end marker (discard)
        val buf2 = ByteArray(MARKER_SIZE)
        conn.bulkTransfer(ep, buf2, MARKER_SIZE, 2000)

        // Pixel data starts after the 12-byte start marker
        return buf1.copyOfRange(MARKER_SIZE, TRANSFER1_SIZE)
    }

    /** Trigger the shutter / NUC calibration. */
    fun triggerShutter() {
        val conn = connection ?: return
        sendCommand(conn, CMD_SHUTTER)
        readStatus(conn)
    }

    /** Switch to high gain mode (-20°C to 150°C). */
    fun setGainHigh() {
        val conn = connection ?: return
        sendCommand(conn, CMD_GAIN_HIGH)
        readStatus(conn)
    }

    /** Switch to low gain mode (0°C to 550°C). */
    fun setGainLow() {
        val conn = connection ?: return
        sendCommand(conn, CMD_GAIN_LOW)
        readStatus(conn)
    }

    /**
     * Stop streaming and release all USB resources.
     */
    fun disconnect() {
        streaming = false
        try {
            val conn = connection ?: return
            // Switch back to inactive alt setting
            iface1Alt0?.let { conn.setInterface(it) }
            iface0?.let  { conn.releaseInterface(it) }
            iface1Alt0?.let { conn.releaseInterface(it) }
            conn.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        } finally {
            connection = null
        }
    }

    fun isStreaming(): Boolean = streaming

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sendCommand(conn: UsbDeviceConnection, cmd: ByteArray) {
        conn.controlTransfer(RT_VENDOR_OUT_IFACE, REQ_WRITE, 0, 0, cmd, cmd.size, 1000)
    }

    private fun readStatus(conn: UsbDeviceConnection): Byte {
        val buf = ByteArray(1)
        conn.controlTransfer(RT_VENDOR_IN_IFACE, REQ_STATUS, 0, 0, buf, 1, 1000)
        return buf[0]
    }

    private fun readResponse(conn: UsbDeviceConnection, length: Int): ByteArray {
        val buf = ByteArray(length)
        conn.controlTransfer(RT_VENDOR_IN_IFACE, REQ_READ_RESP, 0, 0, buf, length, 1000)
        return buf
    }
}

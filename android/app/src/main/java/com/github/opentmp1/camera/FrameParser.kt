package com.github.opentmp1.camera

/**
 * Parses raw frame bytes from the Thermal Master P1 camera into
 * brightness and temperature arrays.
 *
 * Frame layout (P1, 160×120):
 *   Total: 77,440 bytes = 38,720 × uint16 (little-endian)
 *   Reshape to (242 rows × 160 cols):
 *     Rows   0–119  → IR brightness (hardware AGC, 8-bit value in low byte)
 *     Rows 120–121  → Metadata (2 rows)
 *     Rows 122–241  → Raw temperature (16-bit, units = 1/64 K)
 *
 * Temperature conversion:
 *   celsius = raw / 64.0 - 273.15
 *
 * Protocol reference: https://github.com/jvdillon/p3-ir-camera
 */
object FrameParser {

    private const val SENSOR_W = ThermalCameraDriver.SENSOR_W   // 160
    private const val SENSOR_H = ThermalCameraDriver.SENSOR_H   // 120

    /** Total number of rows in the reshaped frame buffer. */
    private const val TOTAL_ROWS = 2 * SENSOR_H + 2             // 242

    /** Scale factor: raw units are 1/64 Kelvin. */
    private const val KELVIN_SCALE = 64.0f

    /** 0 K in Celsius. */
    private const val KELVIN_OFFSET = 273.15f

    /**
     * Parse a raw frame byte-array ([ThermalCameraDriver.FRAME_SIZE] bytes) into
     * two 160×120 float arrays.
     *
     * @param frameBytes Raw pixel bytes (without the 12-byte start marker).
     * @return [ParsedFrame] containing brightness (0–255) and temperature (°C) arrays,
     *         both indexed as [row * SENSOR_W + col] (row-major, 160×120).
     * @throws IllegalArgumentException if [frameBytes] has the wrong size.
     */
    fun parse(frameBytes: ByteArray): ParsedFrame {
        require(frameBytes.size == ThermalCameraDriver.FRAME_SIZE) {
            "Expected ${ThermalCameraDriver.FRAME_SIZE} bytes, got ${frameBytes.size}"
        }

        // Decode 16-bit little-endian values
        val pixels = ShortArray(TOTAL_ROWS * SENSOR_W)
        for (i in pixels.indices) {
            val lo = frameBytes[i * 2].toInt() and 0xFF
            val hi = frameBytes[i * 2 + 1].toInt() and 0xFF
            pixels[i] = ((hi shl 8) or lo).toShort()
        }

        val brightness = FloatArray(SENSOR_W * SENSOR_H)
        val temps      = FloatArray(SENSOR_W * SENSOR_H)

        for (row in 0 until SENSOR_H) {
            val srcBase = row * SENSOR_W
            val dstBase = row * SENSOR_W

            // IR brightness: rows 0 to SENSOR_H-1
            for (col in 0 until SENSOR_W) {
                brightness[dstBase + col] = (pixels[srcBase + col].toInt() and 0xFF).toFloat()
            }

            // Temperature: rows SENSOR_H+2 to 2*SENSOR_H+1
            val thermalRowBase = (SENSOR_H + 2 + row) * SENSOR_W
            for (col in 0 until SENSOR_W) {
                val raw = pixels[thermalRowBase + col].toInt() and 0xFFFF
                temps[dstBase + col] = raw / KELVIN_SCALE - KELVIN_OFFSET
            }
        }

        return ParsedFrame(brightness, temps)
    }

    /**
     * Convert a temperature in Celsius to a display string.
     */
    fun formatTemp(celsius: Float): String = "%.1f°C".format(celsius)
}

/**
 * Holds the parsed frame data for one captured frame.
 *
 * @property brightness IR brightness values (0–255 float), row-major [row * 160 + col].
 * @property temps      Temperature in °C, row-major [row * 160 + col].
 */
data class ParsedFrame(
    val brightness: FloatArray,
    val temps: FloatArray
) {
    val width:  Int = ThermalCameraDriver.SENSOR_W
    val height: Int = ThermalCameraDriver.SENSOR_H

    /** Temperature at pixel (col, row). */
    fun tempAt(col: Int, row: Int): Float = temps[row * width + col]

    /** Brightness at pixel (col, row). */
    fun brightnessAt(col: Int, row: Int): Float = brightness[row * width + col]

    /** Minimum temperature in the frame. */
    fun minTemp(): Float = temps.min()

    /** Maximum temperature in the frame. */
    fun maxTemp(): Float = temps.max()

    /** Temperature at the center pixel. */
    fun centerTemp(): Float = tempAt(width / 2, height / 2)

    /**
     * Check if the frame contains plausible thermal data.
     * A raw value of 0 decodes to -273.15°C (absolute zero), indicating garbage/empty data.
     */
    fun isPlausible(): Boolean = centerTemp() > -200f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedFrame) return false
        return brightness.contentEquals(other.brightness) && temps.contentEquals(other.temps)
    }

    override fun hashCode(): Int {
        var result = brightness.contentHashCode()
        result = 31 * result + temps.contentHashCode()
        return result
    }
}

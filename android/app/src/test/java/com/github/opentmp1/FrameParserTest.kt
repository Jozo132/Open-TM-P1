package com.github.opentmp1

import com.github.opentmp1.camera.FrameParser
import com.github.opentmp1.camera.ThermalCameraDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [FrameParser].
 *
 * These tests validate the parsing logic without requiring a physical camera.
 */
class FrameParserTest {

    companion object {
        private const val SENSOR_W = ThermalCameraDriver.SENSOR_W   // 160
        private const val SENSOR_H = ThermalCameraDriver.SENSOR_H   // 120
        private const val FRAME_SIZE = ThermalCameraDriver.FRAME_SIZE // 77,440
        private const val TOLERANCE = 0.02f  // °C
    }

    // ── Temperature conversion tests ──────────────────────────────────────────

    /**
     * raw_to_celsius(raw) = raw / 64.0 - 273.15
     * Verify known reference values.
     */
    @Test
    fun `temperature conversion is correct for 0 Kelvin`() {
        // raw = 0  → -273.15°C
        val raw = 0
        val expected = -273.15f
        val actual = raw / 64.0f - 273.15f
        assertEquals(expected, actual, TOLERANCE)
    }

    @Test
    fun `temperature conversion is correct for 25 Celsius`() {
        // 25°C = 298.15 K → raw = 298.15 × 64 = 19081.6 ≈ 19082
        val celsius = 25.0f
        val raw = ((celsius + 273.15f) * 64f).toInt()
        val decoded = raw / 64.0f - 273.15f
        assertEquals(celsius, decoded, TOLERANCE)
    }

    @Test
    fun `temperature conversion is correct for 100 Celsius`() {
        val celsius = 100.0f
        val raw = ((celsius + 273.15f) * 64f).toInt()
        val decoded = raw / 64.0f - 273.15f
        assertEquals(celsius, decoded, TOLERANCE)
    }

    // ── Frame parsing tests ───────────────────────────────────────────────────

    @Test
    fun `parse accepts correct frame size`() {
        val bytes = buildTestFrame(irValue = 128, tempCelsius = 25f)
        val frame = FrameParser.parse(bytes)
        assertNotNull(frame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejects wrong frame size`() {
        FrameParser.parse(ByteArray(100))
    }

    @Test
    fun `parse returns correct frame dimensions`() {
        val bytes = buildTestFrame(irValue = 64, tempCelsius = 20f)
        val frame = FrameParser.parse(bytes)
        assertEquals(SENSOR_W, frame.width)
        assertEquals(SENSOR_H, frame.height)
        assertEquals(SENSOR_W * SENSOR_H, frame.brightness.size)
        assertEquals(SENSOR_W * SENSOR_H, frame.temps.size)
    }

    @Test
    fun `parse extracts correct brightness values`() {
        val irValue = 200
        val bytes = buildTestFrame(irValue = irValue, tempCelsius = 20f)
        val frame = FrameParser.parse(bytes)
        // Every brightness pixel should equal irValue (low byte of the 16-bit IR value)
        for (i in frame.brightness.indices) {
            assertEquals("brightness[$i]", irValue.toFloat(), frame.brightness[i], 1f)
        }
    }

    @Test
    fun `parse extracts correct temperature values`() {
        val expectedCelsius = 37.5f
        val bytes = buildTestFrame(irValue = 128, tempCelsius = expectedCelsius)
        val frame = FrameParser.parse(bytes)
        // Every temperature pixel should be close to expectedCelsius
        for (i in frame.temps.indices) {
            assertEquals("temps[$i]", expectedCelsius, frame.temps[i], TOLERANCE)
        }
    }

    @Test
    fun `centerTemp returns value at center pixel`() {
        val expectedCelsius = 42.0f
        val bytes = buildTestFrame(irValue = 128, tempCelsius = expectedCelsius)
        val frame = FrameParser.parse(bytes)
        assertEquals(expectedCelsius, frame.centerTemp(), TOLERANCE)
    }

    @Test
    fun `minTemp and maxTemp work correctly with uniform frame`() {
        val bytes = buildTestFrame(irValue = 100, tempCelsius = 30f)
        val frame = FrameParser.parse(bytes)
        assertEquals(30f, frame.minTemp(), TOLERANCE)
        assertEquals(30f, frame.maxTemp(), TOLERANCE)
    }

    @Test
    fun `minTemp and maxTemp reflect hot spot in frame`() {
        val baseTemp = 20f
        val hotTemp  = 80f
        val bytes = buildTestFrame(irValue = 100, tempCelsius = baseTemp)

        // Inject a hot pixel in thermal row 0, col 0
        // thermal rows start at (SENSOR_H+2) in the full 242-row buffer
        // pixel index = (SENSOR_H + 2) * SENSOR_W = 122 * 160 = 19520
        // byte offset = 19520 * 2 = 39040
        val hotRaw = ((hotTemp + 273.15f) * 64f).toInt()
        val byteIdx = (SENSOR_H + 2) * SENSOR_W * 2
        bytes[byteIdx]     = (hotRaw and 0xFF).toByte()
        bytes[byteIdx + 1] = ((hotRaw shr 8) and 0xFF).toByte()

        val frame = FrameParser.parse(bytes)
        assertEquals(baseTemp, frame.minTemp(), TOLERANCE)
        assertEquals(hotTemp,  frame.maxTemp(), TOLERANCE)
    }

    @Test
    fun `formatTemp produces expected string`() {
        assertEquals("25.0°C",  FrameParser.formatTemp(25.0f))
        assertEquals("-10.5°C", FrameParser.formatTemp(-10.5f))
        assertEquals("100.0°C", FrameParser.formatTemp(100.0f))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a synthetic [FRAME_SIZE]-byte frame with:
     *   - IR brightness rows 0–119 all set to [irValue] (in low byte)
     *   - Temperature rows 122–241 all set to [tempCelsius]
     */
    private fun buildTestFrame(irValue: Int, tempCelsius: Float): ByteArray {
        require(irValue in 0..255)
        val buf = ByteArray(FRAME_SIZE)

        // Temperature raw value
        val tempRaw = ((tempCelsius + 273.15f) * 64f).toInt()

        // Total rows in buffer: 2*120+2 = 242, each row has 160 pixels × 2 bytes
        // IR brightness: rows 0–119
        for (row in 0 until SENSOR_H) {
            for (col in 0 until SENSOR_W) {
                val off = (row * SENSOR_W + col) * 2
                buf[off]     = (irValue and 0xFF).toByte()
                buf[off + 1] = 0  // high byte = 0
            }
        }

        // Metadata rows 120–121: leave as zeros

        // Temperature: rows 122–241
        val thermalRowStart = (SENSOR_H + 2) * SENSOR_W
        for (row in 0 until SENSOR_H) {
            for (col in 0 until SENSOR_W) {
                val off = (thermalRowStart + row * SENSOR_W + col) * 2
                buf[off]     = (tempRaw and 0xFF).toByte()
                buf[off + 1] = ((tempRaw shr 8) and 0xFF).toByte()
            }
        }

        return buf
    }
}

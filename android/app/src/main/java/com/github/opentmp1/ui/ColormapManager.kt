package com.github.opentmp1.ui

import android.graphics.Color

/**
 * Provides colormap lookup tables for thermal image rendering.
 *
 * Each colormap maps a normalized value in [0.0, 1.0] to an ARGB int colour.
 * The tables are pre-computed at 256 steps for fast per-pixel lookup.
 *
 * Available colormaps (matching the desktop reference driver):
 *   - IRONBOW  – classic thermal palette (cold=dark blue → warm=red → hot=white)
 *   - RAINBOW  – full hue rainbow
 *   - GRAYSCALE – simple black-to-white
 *   - HOT      – black → red → orange → yellow → white
 *   - PLASMA   – purple → magenta → orange → yellow
 */
object ColormapManager {

    enum class Colormap(val displayName: String) {
        IRONBOW("Ironbow"),
        RAINBOW("Rainbow"),
        GRAYSCALE("Grayscale"),
        HOT("Hot"),
        PLASMA("Plasma")
    }

    private val tables: Map<Colormap, IntArray> by lazy {
        mapOf(
            Colormap.IRONBOW   to buildIronbow(),
            Colormap.RAINBOW   to buildRainbow(),
            Colormap.GRAYSCALE to buildGrayscale(),
            Colormap.HOT       to buildHot(),
            Colormap.PLASMA    to buildPlasma()
        )
    }

    /** Return a pre-built 256-entry ARGB table for [colormap]. */
    fun getTable(colormap: Colormap): IntArray = tables[colormap]!!

    /** Map a normalised value [0,1] to an ARGB colour using [colormap]. */
    fun map(value: Float, colormap: Colormap): Int {
        val idx = (value.coerceIn(0f, 1f) * 255f).toInt()
        return getTable(colormap)[idx]
    }

    /**
     * Cycle to the next colormap in enum order.
     */
    fun next(current: Colormap): Colormap {
        val values = Colormap.values()
        return values[(current.ordinal + 1) % values.size]
    }

    // ── Table builders ───────────────────────────────────────────────────────

    /** Ironbow: dark blue → purple → red → orange → yellow → white */
    private fun buildIronbow(): IntArray {
        // Key control points: (index, R, G, B)
        val stops = arrayOf(
            intArrayOf(0,   0,   0,   0),
            intArrayOf(32,  0,   0,  80),
            intArrayOf(64,  60,  0, 100),
            intArrayOf(96, 150,  0,  80),
            intArrayOf(128, 220, 30,  10),
            intArrayOf(160, 255, 100,  0),
            intArrayOf(192, 255, 180,  0),
            intArrayOf(224, 255, 230, 100),
            intArrayOf(255, 255, 255, 255)
        )
        return interpolateStops(stops)
    }

    /** Full HSV rainbow from red → green → blue → red. */
    private fun buildRainbow(): IntArray = IntArray(256) { i ->
        Color.HSVToColor(floatArrayOf(i * 270f / 255f, 1f, 1f))
    }

    /** Linear black → white. */
    private fun buildGrayscale(): IntArray = IntArray(256) { i ->
        Color.rgb(i, i, i)
    }

    /** Hot: black → dark-red → red → orange → yellow → white */
    private fun buildHot(): IntArray {
        val stops = arrayOf(
            intArrayOf(0,    0,   0,   0),
            intArrayOf(85,  255,  0,   0),
            intArrayOf(170, 255, 255,  0),
            intArrayOf(255, 255, 255, 255)
        )
        return interpolateStops(stops)
    }

    /** Plasma (purple → magenta → orange → yellow) */
    private fun buildPlasma(): IntArray {
        val stops = arrayOf(
            intArrayOf(0,    13,  8, 135),
            intArrayOf(64,  156, 23, 158),
            intArrayOf(128, 237, 105, 37),
            intArrayOf(192, 253, 196,  39),
            intArrayOf(255, 240, 249,  33)
        )
        return interpolateStops(stops)
    }

    /** Linear interpolation between RGB colour stops. */
    private fun interpolateStops(stops: Array<IntArray>): IntArray {
        val table = IntArray(256)
        for (s in 0 until stops.size - 1) {
            val i0 = stops[s][0];  val r0 = stops[s][1];   val g0 = stops[s][2];  val b0 = stops[s][3]
            val i1 = stops[s+1][0]; val r1 = stops[s+1][1]; val g1 = stops[s+1][2]; val b1 = stops[s+1][3]
            val span = i1 - i0
            for (i in i0..i1) {
                val t = if (span == 0) 0f else (i - i0).toFloat() / span.toFloat()
                val r = lerp(r0, r1, t)
                val g = lerp(g0, g1, t)
                val b = lerp(b0, b1, t)
                table[i] = Color.rgb(r, g, b)
            }
        }
        return table
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
}

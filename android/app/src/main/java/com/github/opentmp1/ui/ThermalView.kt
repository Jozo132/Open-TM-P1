package com.github.opentmp1.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.github.opentmp1.camera.FrameParser
import com.github.opentmp1.camera.ParsedFrame

/**
 * Full-screen view that renders thermal camera frames.
 *
 * Features:
 *   - Renders IR brightness data colourised with a selectable colormap
 *   - Centre-reticule with temperature readout
 *   - Touch-to-measure temperature at any pixel
 *   - Min/max spot markers
 *   - Colorbar legend on the right edge
 */
class ThermalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public state ─────────────────────────────────────────────────────────

    var colormap: ColormapManager.Colormap = ColormapManager.Colormap.IRONBOW
        set(value) { field = value; invalidate() }

    /** If true, the last frame's min/max temperature spots are marked. */
    var showMinMax: Boolean = true
        set(value) { field = value; invalidate() }

    /** If true, the center reticule crosshair is drawn. */
    var showReticule: Boolean = true
        set(value) { field = value; invalidate() }

    /** If true, a colorbar legend is drawn on the right side. */
    var showColorbar: Boolean = true
        set(value) { field = value; invalidate() }

    /** Callback invoked with the touch temperature whenever the user taps. */
    var onTouchTemp: ((Float) -> Unit)? = null

    /** Read-only access to the last frame for diagnostics. */
    val lastFramePublic: ParsedFrame? get() = lastFrame

    // ── Private state ────────────────────────────────────────────────────────

    private val sensorW = 160
    private val sensorH = 120

    private var bitmap: Bitmap? = null
    private var lastFrame: ParsedFrame? = null
    private var touchCol: Int = -1
    private var touchRow: Int = -1

    // Reusable pixel buffer (ARGB)
    private val pixels = IntArray(sensorW * sensorH)

    private val bitmapRect = RectF()
    private val dstRect    = RectF()

    // Paints
    private val reticuleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val reticuleOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val markerMin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerMax = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerTouch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val colorbarPaint = Paint()

    init {
        // Allow touch input
        isClickable = true
        isFocusable = true
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Push a new [ParsedFrame] to the view. Triggers a redraw.
     * Safe to call from any thread.
     */
    fun updateFrame(frame: ParsedFrame) {
        lastFrame = frame
        renderBitmap(frame)
        postInvalidate()
    }

    // ── Touch handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE)
            return super.onTouchEvent(event)

        val frame = lastFrame ?: return true
        val (scaleX, scaleY, offsetX, offsetY) = getBitmapTransform()
        val col = ((event.x - offsetX) / scaleX).toInt().coerceIn(0, sensorW - 1)
        val row = ((event.y - offsetY) / scaleY).toInt().coerceIn(0, sensorH - 1)

        touchCol = col
        touchRow = row
        onTouchTemp?.invoke(frame.tempAt(col, row))
        invalidate()
        return true
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val bmp   = bitmap ?: return
        val frame = lastFrame ?: return

        // Compute letterbox/pillarbox destination rect
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW  = bmp.width.toFloat()
        val bmpH  = bmp.height.toFloat()
        val scale = minOf(viewW / bmpW, viewH / bmpH)
        val dstW  = bmpW * scale
        val dstH  = bmpH * scale
        val left  = (viewW - dstW) / 2f
        val top   = (viewH - dstH) / 2f
        dstRect.set(left, top, left + dstW, top + dstH)
        bitmapRect.set(0f, 0f, bmpW, bmpH)

        canvas.drawBitmap(bmp, null, dstRect, null)

        val scaleX = dstW / bmpW
        val scaleY = dstH / bmpH

        fun colToX(c: Int) = left + c * scaleX + scaleX / 2f
        fun rowToY(r: Int) = top  + r * scaleY + scaleY / 2f

        // Center reticule
        if (showReticule) {
            val cx = colToX(sensorW / 2)
            val cy = rowToY(sensorH / 2)
            drawCrosshair(canvas, cx, cy, 12f, reticuleOuter)
            drawCrosshair(canvas, cx, cy, 12f, reticuleInner)
        }

        // Min/max markers
        if (showMinMax) {
            val minIdx = frame.temps.indices.minByOrNull { frame.temps[it] } ?: 0
            val maxIdx = frame.temps.indices.maxByOrNull { frame.temps[it] } ?: 0
            drawCrosshair(canvas, colToX(minIdx % sensorW), rowToY(minIdx / sensorW), 8f, markerMin)
            drawCrosshair(canvas, colToX(maxIdx % sensorW), rowToY(maxIdx / sensorW), 8f, markerMax)
        }

        // Touch marker
        if (touchCol >= 0 && touchRow >= 0) {
            drawCrosshair(canvas, colToX(touchCol), rowToY(touchRow), 8f, markerTouch)
        }

        // Colorbar
        if (showColorbar) {
            drawColorbar(canvas, left + dstW + 4f, top, 16f, dstH)
        }
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        canvas.drawLine(cx - size, cy, cx + size, cy, paint)
        canvas.drawLine(cx, cy - size, cx, cy + size, paint)
        canvas.drawCircle(cx, cy, size * 0.4f, paint)
    }

    private fun drawColorbar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val table = ColormapManager.getTable(colormap)
        val rect = RectF(x, 0f, x + w, 0f)
        for (i in 0..255) {
            val frac = i / 255f
            rect.top    = y + (1f - frac) * h
            rect.bottom = rect.top + h / 255f + 1f
            colorbarPaint.color = table[i]
            canvas.drawRect(rect, colorbarPaint)
        }
    }

    // ── Bitmap rendering ─────────────────────────────────────────────────────

    private fun renderBitmap(frame: ParsedFrame) {
        val table = ColormapManager.getTable(colormap)

        // Compute min/max brightness for AGC normalisation
        var minB = Float.MAX_VALUE
        var maxB = Float.MIN_VALUE
        for (v in frame.brightness) {
            if (v < minB) minB = v
            if (v > maxB) maxB = v
        }
        val range = (maxB - minB).coerceAtLeast(1f)

        // Map each brightness value to a colormap entry
        for (i in 0 until sensorW * sensorH) {
            val norm = (frame.brightness[i] - minB) / range
            val idx  = (norm * 255f).toInt().coerceIn(0, 255)
            pixels[i] = table[idx]
        }

        // Create or reuse bitmap
        if (bitmap == null || bitmap!!.width != sensorW || bitmap!!.height != sensorH) {
            bitmap = Bitmap.createBitmap(sensorW, sensorH, Bitmap.Config.ARGB_8888)
        }
        bitmap!!.setPixels(pixels, 0, sensorW, 0, 0, sensorW, sensorH)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private data class BitmapTransform(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    private fun getBitmapTransform(): BitmapTransform {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = minOf(viewW / sensorW, viewH / sensorH)
        val dstW  = sensorW * scale
        val dstH  = sensorH * scale
        return BitmapTransform(scale, scale, (viewW - dstW) / 2f, (viewH - dstH) / 2f)
    }

    /**
     * Capture the current view as a [Bitmap] suitable for saving.
     */
    fun captureBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }
}

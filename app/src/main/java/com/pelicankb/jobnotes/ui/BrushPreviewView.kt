package com.pelicankb.jobnotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Tiny live preview for the current brush type and size.
 * It draws a filled shape centered in the view:
 * - PEN / FOUNTAIN / MARKER / PENCIL -> circle (diameter == sizePx, clamped to view)
 * - CALLIGRAPHY -> horizontal oval (to hint a nib)
 */
class BrushPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // neutral preview color; the toolbar color chips decide actual stroke color elsewhere
        // You can tint this using setPreviewColor(int) later if you want.
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density // ~1dp
        alpha = 180
    }

    private var brushNameUpper = "PEN"
    private var sampleSizePx = 16f * resources.displayMetrics.density

    /**
     * Called by MainActivity:
     *   preview.setSample(brushType.name, sizeSlider.progress.dp().toFloat())
     *
     * @param brushName e.g. "PEN", "FOUNTAIN", "CALLIGRAPHY", "PENCIL", "MARKER"
     * @param sizePx desired visual diameter/major-axis size in **pixels**
     */
    fun setSample(brushName: String, sizePx: Float) {
        brushNameUpper = brushName.uppercase()
        sampleSizePx = sizePx.coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // keep some padding from edges
        val maxDrawable = min(w, h) * 0.85f
        val size = min(sampleSizePx, maxDrawable)

        val cx = w / 2f
        val cy = h / 2f

        when (brushNameUpper) {
            "CALLIGRAPHY" -> {
                // Slightly flattened oval (nib hint)
                val ry = size * 0.45f
                val rx = size * 0.75f
                val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawOval(oval, fillPaint)
                canvas.drawOval(oval, strokePaint)
            }
            else -> {
                // round tip preview (pen, pencil, marker, fountain)
                val r = size / 2f
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
            }
        }
    }
}

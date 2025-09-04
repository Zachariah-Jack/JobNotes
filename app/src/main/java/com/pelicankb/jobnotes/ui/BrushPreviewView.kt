package com.pelicankb.jobnotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BrushPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
        strokeWidth = dpToPx(4f)
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    /** Size in dp for the stroke preview line. */
    fun setStrokeWidthDp(sizeDp: Float) {
        paint.strokeWidth = dpToPx(sizeDp)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height * 0.5f
        val pad = dpToPx(12f)
        canvas.drawLine(pad, cy, width - pad, cy, paint)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}

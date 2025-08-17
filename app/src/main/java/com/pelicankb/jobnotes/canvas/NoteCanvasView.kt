package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A simple zoom/pan page canvas.
 * - Draws a white “page” the size of this view.
 * - The parent should have a gray background so areas outside the page show gray.
 * - Pinch to zoom, one-finger drag to pan.
 */
class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Scale & pan state
    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 4f
    private var offsetX = 0f
    private var offsetY = 0f

    // Content (“page”) rect = the white area we draw.
    // For now we make the page exactly the size of this view (fits width by default).
    private val pageRect = RectF()

    // Paints
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0) // subtle hairline border
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
    }

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                // Apply scale and clamp
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)

                // Keep the pinch focus point stable while scaling
                val focusX = detector.focusX
                val focusY = detector.focusY
                val scaleChange = scaleFactor / prevScale
                offsetX = (offsetX - focusX) * scaleChange + focusX
                offsetY = (offsetY - focusY) * scaleChange + focusY

                clampOffsets()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Pan (note: distanceX is the scroll delta, so subtract to move with the finger)
                offsetX -= distanceX
                offsetY -= distanceY
                clampOffsets()
                invalidate()
                return true
            }
        })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Page matches the view size initially (fits screen width as requested)
        pageRect.set(0f, 0f, w.toFloat(), h.toFloat())
        // Reset zoom/pan so the page fits perfectly
        scaleFactor = 1f
        // Center page if needed (here content == view, so offsets end up 0)
        centerOrClamp()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // We rely on the parent’s gray background to show “outside the page”.
        // We only draw the white page + a subtle border at the current transform.
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)

        // White page
        canvas.drawRect(pageRect, pagePaint)
        // Border
        canvas.drawRect(pageRect, borderPaint)

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Make sure parents (if scrollable) don't intercept our gestures
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent.requestDisallowInterceptTouchEvent(false)
        }

        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun clampOffsets() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val cw = pageRect.width() * scaleFactor
        val ch = pageRect.height() * scaleFactor

        // If content larger than viewport: allow panning within bounds.
        // If smaller: keep it centered.
        val minX = if (cw >= vw) vw - cw else (vw - cw) / 2f
        val maxX = if (cw >= vw) 0f else minX
        val minY = if (ch >= vh) vh - ch else (vh - ch) / 2f
        val maxY = if (ch >= vh) 0f else minY

        offsetX = offsetX.coerceIn(minX, maxX)
        offsetY = offsetY.coerceIn(minY, maxY)
    }

    private fun centerOrClamp() {
        // Called on size changes / resets
        clampOffsets()
    }
}

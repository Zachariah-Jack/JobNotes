package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Browser-like canvas:
 *  - One-finger vertical scroll with fling/momentum.
 *  - Horizontal pan only when zoomed in (content wider than screen).
 *  - Pinch to zoom (anchored), double-tap to zoom in/out.
 *  - Auto-extend height when bottom enters lower 20% of the screen.
 *  - Gray background outside the white "page".
 */
class ZoomableCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- Tunables ----
    private val MIN_SCALE = 0.5f            // modest zoom-out (see margins)
    private val MAX_SCALE = 8.0f            // strong zoom-in
    private val H_OVERSCROLL_DP = 0f        // horizontal overscroll when wider than screen
    private val V_OVERSCROLL_DP = 24f       // small vertical overscroll cushion
    private val MIN_INITIAL_HEIGHT_DP = 2400f
    private val BOTTOM_TRIGGER_RATIO = 0.80f
    private val DOUBLE_TAP_STEP = 2.0f      // x2 on double tap (toggle back to ~fit)

    // ---- State ----
    private var contentWidthPx = 0f
    private var contentHeightPx = 0f
    private var scaleFactor = 1.0f
    private var contentTx = 0f
    private var contentTy = 0f

    // Paints
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0F0F0.toInt() // gray outside page
        style = Paint.Style.FILL
    }
    private val bottomEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }

    // Matrices
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    // Gestures
    private val scroller = OverScroller(context)
    private val gestureListener = GestureListener()
    private val gestureDetector = GestureDetector(context, gestureListener).apply {
        // Important: wire double-tap as well
        setOnDoubleTapListener(gestureListener)
    }
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var scalingInProgress = false

    init {
        isClickable = true
        isFocusable = true
    }

    // ---- Layout ----
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Page always starts as wide as the view (fit width).
        contentWidthPx = w.toFloat()

        if (contentHeightPx <= 0f) {
            contentHeightPx = max(h * 3f, dp(MIN_INITIAL_HEIGHT_DP))
            scaleFactor = 1.0f
            contentTx = ((width - contentWidthPx * scaleFactor) / 2f)
            contentTy = 0f
        }

        clampTranslations()
    }

    // ---- Drawing ----
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Gray outside
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Apply scale & translation
        drawMatrix.reset()
        drawMatrix.postScale(scaleFactor, scaleFactor)
        drawMatrix.postTranslate(contentTx, contentTy)
        canvas.save()
        canvas.concat(drawMatrix)

        // Page in content coords
        val page = RectF(0f, 0f, contentWidthPx, contentHeightPx)

        // White page + border + dashed bottom
        canvas.drawRect(page, pagePaint)
        canvas.drawRect(page, pageBorderPaint)
        canvas.drawLine(0f, contentHeightPx, contentWidthPx, contentHeightPx, bottomEdgePaint)

        canvas.restore()
    }

    // ---- Touch ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)

        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!scroller.isFinished) scroller.forceFinished(true)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            performClick()
        }

        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- Gesture listeners ----
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (scalingInProgress) return false

            val scaledW = contentWidthPx * scaleFactor

            // Vertical scroll always allowed (browser-like)
            var dx = 0f
            val dy = distanceY

            // Horizontal pan only if page wider than screen
            if (scaledW > width) {
                dx = distanceX
            }

            contentTx -= dx
            contentTy -= dy

            clampTranslations()
            maybeExtendOnScreenThreshold()
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (scalingInProgress) return false

            val b = computeBounds()
            scroller.fling(
                contentTx.roundToInt(), contentTy.roundToInt(),
                (-velocityX).roundToInt(), (-velocityY).roundToInt(),
                b.minTx.roundToInt(), b.maxTx.roundToInt(),
                b.minTy.roundToInt(), b.maxTy.roundToInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (scaleFactor < 1.25f) {
                (scaleFactor * DOUBLE_TAP_STEP).coerceAtMost(MAX_SCALE)
            } else {
                1.0f
            }

            if (!drawMatrix.invert(inverseMatrix)) return false
            val pts = floatArrayOf(e.x, e.y)
            inverseMatrix.mapPoints(pts)
            val cx = pts[0]
            val cy = pts[1]

            scaleFactor = targetScale
            contentTx = e.x - (cx * scaleFactor)
            contentTy = e.y - (cy * scaleFactor)

            clampTranslations()
            invalidate()
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scalingInProgress = true
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX
            val focusY = detector.focusY

            if (!drawMatrix.invert(inverseMatrix)) return false
            val pts = floatArrayOf(focusX, focusY)
            inverseMatrix.mapPoints(pts)
            val cx = pts[0]
            val cy = pts[1]

            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)

            contentTx = focusX - (cx * scaleFactor)
            contentTy = focusY - (cy * scaleFactor)

            clampTranslations()
            maybeExtendOnScreenThreshold()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scalingInProgress = false
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            contentTx = scroller.currX.toFloat()
            contentTy = scroller.currY.toFloat()
            clampTranslations()
            maybeExtendOnScreenThreshold()
            postInvalidateOnAnimation()
        }
    }

    // ---- Bounds & extension ----
    private data class Bounds(val minTx: Float, val maxTx: Float, val minTy: Float, val maxTy: Float)

    private fun computeBounds(): Bounds {
        val hOver = dp(H_OVERSCROLL_DP)
        val vOver = dp(V_OVERSCROLL_DP)

        val scaledW = contentWidthPx * scaleFactor
        val scaledH = contentHeightPx * scaleFactor

        val (minTx, maxTx) = if (scaledW <= width) {
            val centerTx = (width - scaledW) / 2f
            centerTx to centerTx
        } else {
            (width - scaledW - hOver) to (hOver)
        }

        val (minTy, maxTy) = if (scaledH <= height) {
            val centerTy = (height - scaledH) / 2f
            (centerTy - vOver) to (centerTy + vOver)
        } else {
            (height - scaledH - vOver) to (vOver)
        }

        return Bounds(minTx, maxTx, minTy, maxTy)
    }

    private fun clampTranslations() {
        val b = computeBounds()
        contentTx = contentTx.coerceIn(b.minTx, b.maxTx)
        contentTy = contentTy.coerceIn(b.minTy, b.maxTy)
    }

    /** Extend the page when its bottom is within the lower 20% of the screen. */
    private fun maybeExtendOnScreenThreshold() {
        val bottomScreenY = contentHeightPx * scaleFactor + contentTy
        val triggerY = height * BOTTOM_TRIGGER_RATIO
        if (bottomScreenY <= triggerY) {
            val addContent = height / max(0.001f, scaleFactor)
            contentHeightPx += addContent
            clampTranslations()
        }
    }

    // ---- Utilities ----
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Browser-like canvas (single canvas class):
 *  - One-finger vertical scroll with fling/momentum.
 *  - Horizontal pan only when zoomed in (page wider than screen).
 *  - Pinch to zoom (anchored), double-tap to zoom in/out.
 *  - Auto-extend height when bottom crosses the 20% from bottom line.
 *  - White "page" drawn; gray outside comes from the parent background.
 */
class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Tunables ----
    private val MIN_SCALE = 0.35f            // allow zooming out to see gray margins
    private val MAX_SCALE = 8.0f             // strong zoom-in
    private val H_OVERSCROLL_DP = 0f         // no horizontal overscroll
    private val V_OVERSCROLL_FRAC = 0.30f    // 30% of screen height vertical overscroll
    private val BOTTOM_TRIGGER_RATIO = 0.80f // extend when bottom crosses 80% of screen height
    private val DOUBLE_TAP_STEP = 2.0f       // x2 on double tap (toggle back to ~fit)
    private val EXTEND_COOLDOWN_MS = 200L    // tiny debounce so we don't extend multiple times per crossing

    // ---- Page ("content") state ----
    private var contentWidthPx = 0f          // page width in content coords
    private var contentHeightPx = 0f         // page height in content coords

    // Current transform (screen <- content)
    private var scaleFactor = 1.0f
    private var contentTx = 0f
    private var contentTy = 0f

    // Last-bottom tracker to detect crossings of the trigger line
    private var lastBottomScreenY: Float = Float.NaN
    private var lastExtendAt: Long = 0L

    // Paints
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000   // subtle border
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val bottomEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }

    // Gestures / animation
    private val scroller = OverScroller(context)
    private var scalingInProgress = false
    private val gestureListener = GestureListener()
    private val gestureDetector = GestureDetector(context, gestureListener).apply {
        setOnDoubleTapListener(gestureListener)
    }
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    init {
        isClickable = true
        isFocusable = true
    }

    // ---- Layout ----
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Fit width: page width == view width in content coords
        contentWidthPx = w.toFloat()

        // Initial height: EXACTLY one screen tall (as requested)
        if (contentHeightPx <= 0f) {
            contentHeightPx = h.toFloat()
            scaleFactor = 1.0f
            // center horizontally; top aligned vertically
            contentTx = (width - contentWidthPx * scaleFactor) / 2f
            contentTy = 0f
        }

        clampTranslations()
        lastBottomScreenY = contentHeightPx * scaleFactor + contentTy
    }

    // ---- Drawing ----
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Host view supplies gray outside; we draw the white page at transform
        canvas.save()
        canvas.translate(contentTx, contentTy)
        canvas.scale(scaleFactor, scaleFactor)

        val page = RectF(0f, 0f, contentWidthPx, contentHeightPx)
        canvas.drawRect(page, pagePaint)
        canvas.drawRect(page, pageBorderPaint)
        canvas.drawLine(0f, contentHeightPx, contentWidthPx, contentHeightPx, bottomEdgePaint)

        canvas.restore()
    }

    // ---- Touch / gestures ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // keep parents from intercepting (so we control scroll/zoom)
        parent?.requestDisallowInterceptTouchEvent(true)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!scroller.isFinished) scroller.forceFinished(true)
        }

        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            performClick()
        }
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }

        // IMPORTANT: match SDK signature (e1 nullable, e2 non-null)
        override fun onScroll(
            e1: MotionEvent?,
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
            maybeExtendOnCrossing()
            invalidate()
            return true
        }

        // IMPORTANT: match SDK signature (e1 nullable, e2 non-null)
        override fun onFling(
            e1: MotionEvent?,
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

            // Anchor at tap point: map tap to content coords at current transform
            val cx = (e.x - contentTx) / scaleFactor
            val cy = (e.y - contentTy) / scaleFactor

            scaleFactor = targetScale
            contentTx = e.x - (cx * scaleFactor)
            contentTy = e.y - (cy * scaleFactor)

            clampTranslations()
            maybeExtendOnCrossing()
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

            // Content point under fingers before scale
            val cx = (focusX - contentTx) / scaleFactor
            val cy = (focusY - contentTy) / scaleFactor

            // Apply scale within bounds
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)

            // Keep same content point under fingers after scale
            contentTx = focusX - (cx * scaleFactor)
            contentTy = focusY - (cy * scaleFactor)

            clampTranslations()
            maybeExtendOnCrossing()
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
            maybeExtendOnCrossing()
            postInvalidateOnAnimation()
        }
    }

    // ---- Bounds & auto-extend ----
    private data class Bounds(
        val minTx: Float, val maxTx: Float,
        val minTy: Float, val maxTy: Float
    )

    private fun computeBounds(): Bounds {
        val hOver = dp(H_OVERSCROLL_DP)
        val vOverPx = height * V_OVERSCROLL_FRAC

        val scaledW = contentWidthPx * scaleFactor
        val scaledH = contentHeightPx * scaleFactor

        val (minTx, maxTx) = if (scaledW <= width) {
            val centerTx = (width - scaledW) / 2f
            centerTx to centerTx // center horizontally
        } else {
            (width - scaledW - hOver) to (hOver)
        }

        val (minTy, maxTy) = if (scaledH <= height) {
            val centerTy = (height - scaledH) / 2f
            (centerTy - vOverPx) to (centerTy + vOverPx)
        } else {
            (height - scaledH - vOverPx) to (vOverPx)
        }

        return Bounds(minTx, maxTx, minTy, maxTy)
    }

    private fun clampTranslations() {
        val b = computeBounds()
        contentTx = contentTx.coerceIn(b.minTx, b.maxTx)
        contentTy = contentTy.coerceIn(b.minTy, b.maxTy)
    }

    /**
     * Extend when the page bottom crosses the trigger line (80% of screen height)
     * while moving upward. We detect a *crossing* from above to below that line
     * so we only extend once per pass.
     */
    private fun maybeExtendOnCrossing() {
        val currBottom = contentHeightPx * scaleFactor + contentTy
        val triggerY = height * BOTTOM_TRIGGER_RATIO

        // First run: just seed and return
        if (lastBottomScreenY.isNaN()) {
            lastBottomScreenY = currBottom
            return
        }

        val now = SystemClock.uptimeMillis()
        val crossedDownThroughTrigger = lastBottomScreenY > triggerY && currBottom <= triggerY
        val cooldownOk = (now - lastExtendAt) >= EXTEND_COOLDOWN_MS

        if (crossedDownThroughTrigger && cooldownOk) {
            // Add roughly one screen of content in *content* coords (respects zoom)
            val addContent = height / max(0.001f, scaleFactor)
            contentHeightPx += addContent
            lastExtendAt = now
            clampTranslations()
        }

        lastBottomScreenY = currBottom
    }

    // ---- Utilities ----
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

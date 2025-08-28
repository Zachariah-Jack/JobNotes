package com.pelicankb.jobnotes.drawing

import android.graphics.*
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight, self-contained scrollbar overlay.
 * - Owns paints, geometry, drag state.
 * - Call [draw] each frame.
 * - Call [onTouchEvent]; pass a setter so it can update translationY.
 *
 * Coordinate conventions:
 * - viewW, viewH: view-space size (px)
 * - contentHViewPx: total scrollable content height in VIEW space (after scale)
 * - translationY is the canvas Y translation (content -> view)
 * - scaleFactor used to map thumb drag back to translationY
 */
class ScrollbarOverlay {

    // Visual constants (dp provided by caller via density)
    private fun dp(value: Float, density: Float) = value * density

    // Colors
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000; style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }

    // Runtime state
    private var dragging = false
    private var dragOffsetY = 0f
    private val thumbRect = RectF()

    // Cached layout
    private var marginPx = 0f
    private var widthPx = 0f
    private var arrowH = 0f
    private var minThumbH = 0f

    private fun ensureDims(density: Float) {
        // Only recompute when density changes significantly; negligible cost regardless.
        marginPx = dp(6f, density)
        widthPx = dp(18f, density)
        arrowH = dp(18f, density)
        minThumbH = dp(24f, density)
    }

    private fun trackLeft(viewW: Int, density: Float)  = viewW - marginPx - widthPx
    private fun trackRight(viewW: Int, density: Float) = viewW - marginPx
    private fun trackTop(density: Float)              = 0f + marginPx
    private fun trackBottom(viewH: Int, density: Float)= viewH - marginPx

    private fun computeThumb(
        viewW: Int,
        viewH: Int,
        density: Float,
        scaleFactor: Float,
        translationY: Float,
        contentHViewPx: Float,
    ) {
        ensureDims(density)
        val left = trackLeft(viewW, density)
        val right = trackRight(viewW, density)
        val top = trackTop(density)
        val bottom = trackBottom(viewH, density)
        val trackH = bottom - top

        val contentH = max(1f, contentHViewPx)
        val viewPortH = viewH.toFloat()

        val thumbH = max(minThumbH, trackH * (viewPortH / contentH))

        val maxScroll = max(1f, contentH - viewPortH)
        // Approximate scrollY in VIEW coords for mapping (phase 1)
        val scrollY = -translationY * scaleFactor
        val t = (scrollY / maxScroll).coerceIn(0f, 1f)

        val thumbTop = top + (trackH - thumbH) * t
        val thumbBottom = thumbTop + thumbH
        thumbRect.set(left, thumbTop, right, thumbBottom)
    }

    fun draw(
        canvas: Canvas,
        viewW: Int,
        viewH: Int,
        density: Float,
        scaleFactor: Float,
        translationY: Float,
        contentHViewPx: Float,
    ) {
        ensureDims(density)
        // Track
        val left = trackLeft(viewW, density)
        val right = trackRight(viewW, density)
        val top = trackTop(density)
        val bottom = trackBottom(viewH, density)
        val rx = dp(6f, density)

        canvas.drawRoundRect(left, top, right, bottom, rx, rx, trackPaint)

        // Arrows
        val cx = (left + right) * 0.5f
        val upBottom = top + arrowH
        val up = Path().apply {
            moveTo(cx, top + dp(6f, density))
            lineTo(cx - dp(6f, density), upBottom - dp(6f, density))
            lineTo(cx + dp(6f, density), upBottom - dp(6f, density))
            close()
        }
        canvas.drawPath(up, arrowPaint)

        val dnTop = bottom - arrowH
        val dn = Path().apply {
            moveTo(cx, bottom - dp(6f, density))
            lineTo(cx - dp(6f, density), dnTop + dp(6f, density))
            lineTo(cx + dp(6f, density), dnTop + dp(6f, density))
            close()
        }
        canvas.drawPath(dn, arrowPaint)

        // Thumb
        computeThumb(viewW, viewH, density, scaleFactor, translationY, contentHViewPx)
        canvas.drawRoundRect(thumbRect, rx, rx, thumbPaint)
    }

    /**
     * Handles interactions. Returns true if consumed.
     * setTranslationY(newTy) must update the view's translationY in CONTENT->VIEW mapping.
     */
    fun onTouchEvent(
        ev: MotionEvent,
        viewW: Int,
        viewH: Int,
        density: Float,
        scaleFactor: Float,
        translationY: Float,
        contentHViewPx: Float,
        setTranslationY: (Float) -> Unit,
        invalidate: () -> Unit
    ): Boolean {
        ensureDims(density)
        val left = trackLeft(viewW, density)
        val right = trackRight(viewW, density)
        val top = trackTop(density)
        val bottom = trackBottom(viewH, density)
        val withinBar = ev.x >= left && ev.x <= right && ev.y >= top && ev.y <= bottom

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!withinBar) return false

                // Arrow boxes
                val upBottom = top + arrowH
                val dnTop = bottom - arrowH
                if (ev.y <= upBottom) {
                    // Scroll up one notch
                    setTranslationY((translationY + dp(48f, density)).coerceAtMost(0f))
                    invalidate()
                    return true
                }
                if (ev.y >= dnTop) {
                    // Scroll down one notch
                    setTranslationY(translationY - dp(48f, density))
                    invalidate()
                    return true
                }

                // Thumb drag
                computeThumb(viewW, viewH, density, scaleFactor, translationY, contentHViewPx)
                dragging = true
                dragOffsetY = when {
                    ev.y < thumbRect.top || ev.y > thumbRect.bottom -> thumbRect.height() / 2f // jump to tap
                    else -> ev.y - thumbRect.top
                }
                // Apply immediate jump positioning via MOVE flow:
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false

                val trackTop = top + arrowH
                val trackBottom = bottom - arrowH
                val trackH = trackBottom - trackTop
                val thumbH = max(minThumbH, thumbRect.height())
                val minTop = trackTop
                val maxTop = trackBottom - thumbH

                val desiredTop = (ev.y - dragOffsetY).coerceIn(minTop, maxTop)
                val t = if (maxTop > minTop) (desiredTop - minTop) / (maxTop - minTop) else 0f

                val maxScroll = max(1f, contentHViewPx - viewH)
                val newScrollY = t * maxScroll  // view coords
                val newTy = -(newScrollY / max(0.0001f, scaleFactor))
                setTranslationY(newTy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    return true
                }
                return withinBar
            }
        }
        return false
    }
}

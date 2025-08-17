package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Host layout that keeps the entire gesture stream inside the canvas child
 * and prevents parents (ScrollView, CoordinatorLayout, etc.) from hijacking drags.
 */
class CanvasHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept so our parent never gets the chance to steal touches.
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val child = getChildAt(0)
        // Forward the event to the canvas view.
        return child?.dispatchTouchEvent(event) == true || super.onTouchEvent(event)
    }
}

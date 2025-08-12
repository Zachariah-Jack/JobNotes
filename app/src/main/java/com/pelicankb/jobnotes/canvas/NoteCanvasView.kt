package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

// Keep enum here to avoid duplicate file conflicts.
enum class ToolType { PEN, HIGHLIGHTER, ERASER, SELECT }

private data class Stroke(
    val path: Path,
    val paint: Paint
)

private data class PlacedBitmap(
    val bitmap: Bitmap,
    val dst: RectF
)

class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- State ----
    private var currentTool: ToolType = ToolType.PEN
    private var stylusOnly = false
    private var stylusButtonAsEraser = true
    private var eraseByStroke = false // currently unused; stubbed for compatibility
    private var highlighterStraight = false

    // Active drawing
    private var activePath: Path? = null
    private var activePaint: Paint? = null
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // Document content
    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    private val images = mutableListOf<PlacedBitmap>()

    // Brushes
    private val penPaintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val highlighterPaintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        alpha = 120 // ~47% (we’ll expose a setter below)
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val eraserPaintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // CLEAR acts like an area eraser
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = 28f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Utility: snapshot a Paint so completed strokes don't change later
    private fun copyPaint(p: Paint): Paint = Paint(p)

    // ---- Public API ----

    fun newDocument() {
        strokes.clear()
        redoStack.clear()
        images.clear()
        activePath = null
        invalidate()
    }

    fun setTool(tool: ToolType) { currentTool = tool }

    fun setStylusOnly(enabled: Boolean) { stylusOnly = enabled }

    fun setStylusButtonAsEraser(enabled: Boolean) { stylusButtonAsEraser = enabled }

    fun setEraseByStroke(enabled: Boolean) { eraseByStroke = enabled /* no-op for now */ }

    fun setPenColor(color: Int) { penPaintBase.color = color }

    fun setHighlighterColor(color: Int) { highlighterPaintBase.color = color }

    fun setPenWidth(px: Float) { penPaintBase.strokeWidth = px }

    fun setHighlighterWidth(px: Float) { highlighterPaintBase.strokeWidth = px }

    fun setHighlighterOpacity(percent: Int) {
        val p = percent.coerceIn(0, 100)
        highlighterPaintBase.alpha = ((p / 100f) * 255f).roundToInt()
        invalidate()
    }

    fun setHighlighterStraight(enabled: Boolean) {
        highlighterStraight = enabled
    }

    fun setEraserWidth(px: Float) { eraserPaintBase.strokeWidth = px }

    fun setEraserRadiusPx(px: Float) { eraserPaintBase.strokeWidth = px } // alias for width

    fun setCalligraphyThinRatio(@Suppress("UNUSED_PARAMETER") v: Float) { /* no-op */ }

    fun setCalligraphyResponse(@Suppress("UNUSED_PARAMETER") v: Float) { /* no-op */ }

    fun clearLayer() {
        strokes.clear()
        redoStack.clear()
        invalidate()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.lastIndex))
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            strokes.add(redoStack.removeAt(redoStack.lastIndex))
            invalidate()
        }
    }

    fun fitToContent(@Suppress("UNUSED_PARAMETER") animate: Boolean = false) { /* no-op */ }

    // Insert a bitmap roughly centered and scaled to ~80% of view bounds
    fun insertBitmapAtCenter(bitmap: Bitmap) {
        if (width == 0 || height == 0) {
            post { insertBitmapAtCenter(bitmap) }
            return
        }
        val maxW = width * 0.8f
        val maxH = height * 0.8f
        val scale = min(maxW / bitmap.width.toFloat(), maxH / bitmap.height.toFloat())
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        images.add(PlacedBitmap(bitmap, RectF(left, top, left + dw, top + dh)))
        invalidate()
    }

    // === Added to satisfy MainActivity ===
    fun snapshotBitmap(): Bitmap {
        val w = if (width > 0) width else 1
        val h = if (height > 0) height else 1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c) // reuse onDraw for a full snapshot
        return bmp
    }

    fun loadFromBitmap(bitmap: Bitmap) {
        // Clear current content and add the bitmap centered
        strokes.clear()
        redoStack.clear()
        images.clear()
        insertBitmapAtCenter(bitmap)
    }
    // === end of added methods ===

    // ---- Rendering & input ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)           // white background

        images.forEach { canvas.drawBitmap(it.bitmap, null, it.dst, null) }
        strokes.forEach { canvas.drawPath(it.path, it.paint) }

        activePath?.let { p ->
            activePaint?.let { paint -> canvas.drawPath(p, paint) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Block finger if stylus-only
        if (stylusOnly && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return false
        }

        // Stylus primary button temporarily activates eraser
        val usingEraserNow =
            stylusButtonAsEraser &&
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
                    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        val tool = if (usingEraserNow) ToolType.ERASER else currentTool
        val basePaint = when (tool) {
            ToolType.PEN -> penPaintBase
            ToolType.HIGHLIGHTER -> highlighterPaintBase
            ToolType.ERASER -> eraserPaintBase
            ToolType.SELECT -> penPaintBase // SELECT is a no-op here
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                activePaint = copyPaint(basePaint)
                activePath = Path().apply { moveTo(event.x, event.y) }
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = activePath ?: return false
                val dx = Math.abs(event.x - lastX)
                val dy = Math.abs(event.y - lastY)
                if (dx >= 2f || dy >= 2f) {
                    p.quadTo(lastX, lastY, (event.x + lastX) / 2f, (event.y + lastY) / 2f)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val paint = activePaint
                var finished = activePath

                // If straight highlighter mode is ON, replace the path with a straight segment
                if (currentTool == ToolType.HIGHLIGHTER && highlighterStraight && paint != null) {
                    val p2 = Path().apply {
                        moveTo(downX, downY)
                        lineTo(event.x, event.y)
                    }
                    finished = p2
                } else {
                    finished?.lineTo(event.x, event.y)
                }

                activePath = null
                activePaint = null

                if (finished != null && paint != null) {
                    strokes.add(Stroke(finished, paint))
                }
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

package com.pelicankb.jobnotes

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { STYLUS, HIGHLIGHTER, ERASER, SELECT }
    enum class SelectionMode { RECT, LASSO }

    private data class Stroke(
        val path: Path,
        val paint: Paint,
        val tool: Tool
    ) {
        val bounds: RectF = RectF().also { path.computeBounds(it, true) }
    }

    private val strokes = mutableListOf<Stroke>()
    private val undone = mutableListOf<Stroke>()

    private var tool: Tool = Tool.STYLUS
    private var selectionMode: SelectionMode = SelectionMode.RECT

    private var penColor: Int = Color.BLACK
    private var highlighterColor: Int = Color.YELLOW
    private var penWidthPx: Float = 6f
    private var highlighterWidthPx: Float = 18f
    private var eraserWidthPx: Float = 28f

    private var currentPath: Path? = null
    private var currentPaint: Paint? = null
    private var lastX = 0f
    private var lastY = 0f

    private var selectionRect: RectF? = null

    // Offscreen buffer so eraser (CLEAR) and undo/redo are reliable
    private var bufferBitmap: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bufferBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufferCanvas = Canvas(bufferBitmap!!)
        rebuildBuffer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bufferBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }

        // Selection overlay
        val sel = selectionRect
        if (tool == Tool.SELECT && sel != null) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x6633B5E5 // translucent blue
                style = Paint.Style.FILL
            }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF33B5E5.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(sel, fill)
            canvas.drawRect(sel, border)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                undone.clear()
                when (tool) {
                    Tool.STYLUS -> beginStroke(newPenPaint())
                    Tool.HIGHLIGHTER -> beginStroke(newHighlighterPaint())
                    Tool.ERASER -> beginStroke(newEraserPaint())
                    Tool.SELECT -> { beginSelection(x, y); return true }
                }
                lastX = x; lastY = y
                currentPath?.moveTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (tool) {
                    Tool.SELECT -> { updateSelection(x, y); return true }
                    else -> {
                        val path = currentPath ?: return false
                        val midX = (x + lastX) / 2f
                        val midY = (y + lastY) / 2f
                        path.quadTo(lastX, lastY, midX, midY)
                        bufferCanvas?.drawPath(path, currentPaint ?: return false)
                        lastX = x; lastY = y
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (tool) {
                    Tool.SELECT -> { finalizeSelection(); return true }
                    else -> {
                        val path = currentPath ?: return false
                        // Already drawn during move; just commit stroke record
                        addStroke(path, currentPaint ?: return false)
                        currentPath = null
                        currentPaint = null
                        invalidate()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginStroke(paint: Paint) {
        currentPath = Path()
        currentPaint = paint
    }

    private fun addStroke(path: Path, paint: Paint) {
        val copyPath = Path(path)
        val copyPaint = Paint(paint)
        val s = Stroke(copyPath, copyPaint, tool)
        s.path.computeBounds(s.bounds, true)
        strokes.add(s)
    }

    // Selection (lasso is treated like rect for now)
    private fun beginSelection(x: Float, y: Float) {
        selectionRect = RectF(x, y, x, y)
        invalidate()
    }

    private fun updateSelection(x: Float, y: Float) {
        val r = selectionRect ?: return
        r.right = x
        r.bottom = y
        invalidate()
    }

    private fun finalizeSelection() {
        selectionRect?.let { r ->
            val l = min(r.left, r.right)
            val t = min(r.top, r.bottom)
            val rt = max(r.left, r.right)
            val b = max(r.top, r.bottom)
            r.set(l, t, rt, b)
        }
        invalidate()
    }

    private fun newPenPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = penColor
        style = Paint.Style.STROKE
        strokeWidth = penWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun newHighlighterPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // force ~40% alpha to mimic a marker
        val base = highlighterColor and 0x00FFFFFF
        color = base or 0x66000000
        style = Paint.Style.STROKE
        strokeWidth = highlighterWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun newEraserPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = eraserWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun rebuildBuffer() {
        val c = bufferCanvas ?: return
        val b = bufferBitmap ?: return
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), clear)
        for (s in strokes) c.drawPath(s.path, s.paint)
        invalidate()
    }

    // --- public API ---

    fun setTool(t: Tool) {
        tool = t
        currentPath = null
        currentPaint = null
    }

    fun setSelectionMode(mode: SelectionMode) {
        selectionMode = mode // (lasso currently behaves like rect)
    }

    fun clearSelection() {
        selectionRect = null
        invalidate()
    }

    fun deleteSelection() {
        val r = selectionRect ?: return
        val it = strokes.iterator()
        var removed = false
        while (it.hasNext()) {
            val s = it.next()
            if (RectF.intersects(s.bounds, r)) {
                it.remove()
                removed = true
            }
        }
        if (removed) {
            undone.clear()
            rebuildBuffer()
        }
        selectionRect = null
    }

    fun setPenColor(color: Int) { penColor = color }
    fun setHighlighterColor(color: Int) { highlighterColor = color }
    fun setPenWidthPx(px: Float) { penWidthPx = px }
    fun setHighlighterWidthPx(px: Float) { highlighterWidthPx = px }
    fun setEraserWidthPx(px: Float) { eraserWidthPx = px }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undone.add(strokes.removeAt(strokes.size - 1))
            rebuildBuffer()
        }
    }

    fun redo() {
        if (undone.isNotEmpty()) {
            strokes.add(undone.removeAt(undone.size - 1))
            rebuildBuffer()
        }
    }
}

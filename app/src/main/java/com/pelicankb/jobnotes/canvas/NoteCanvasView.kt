package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class NoteCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { STYLUS, HIGHLIGHTER, ERASER }
    enum class StylusBrush { FOUNTAIN, MARKER }
    enum class HighlightMode { FREEFORM, STRAIGHT }
    enum class EraserMode { STROKE, AREA }
    private enum class Layer { INK, HL }

    // ---------------- State ----------------
    private var tool = Tool.STYLUS

    private var stylusBrush = StylusBrush.FOUNTAIN
    private var stylusSize = 8f
    private var stylusColor = Color.BLACK

    private var highlighterSize = 24f
    private var highlighterColor = Color.YELLOW
    private var highlighterOpacity = 0.5f // 0..1
    private var highlightMode = HighlightMode.FREEFORM

    private var eraserMode = EraserMode.STROKE
    private var eraserSize = 30f
    private var eraseHighlightsOnly = false

    // Two layers for selective erasing
    private var inkBitmap: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private var hlBitmap: Bitmap? = null
    private var hlCanvas: Canvas? = null

    // Live paints
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = stylusColor
        strokeWidth = stylusSize
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = highlighterColor
        alpha = (255 * highlighterOpacity).toInt()
        strokeWidth = highlighterSize
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = eraserSize
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val eraserFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Live gesture
    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // For AREA eraser (dot stamping)
    private val areaDots = ArrayList<PointF>()

    // ---------------- History (command list) ----------------
    private data class PaintParams(
        val color: Int,
        val alpha: Int,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join
    )

    private sealed class Op {
        data class DrawPath(val layer: Layer, val path: Path, val params: PaintParams) : Op()
        data class DrawStraightHl(val startX: Float, val endX: Float, val y: Float, val params: PaintParams) : Op()
        data class ErasePath(val path: Path, val strokeWidth: Float, val highlightsOnly: Boolean) : Op()
        data class EraseDots(val dots: List<PointF>, val radius: Float, val highlightsOnly: Boolean) : Op()
        object Clear : Op()
    }

    private val ops = mutableListOf<Op>()
    private var cursor = -1 // index of last applied op; -1 means empty

    var onStackChanged: (() -> Unit)? = null

    fun canUndo(): Boolean = cursor >= 0
    fun canRedo(): Boolean = cursor < ops.size - 1

    fun undo() {
        if (!canUndo()) return
        cursor--
        rebuildFromHistory()
        onStackChanged?.invoke()
    }

    fun redo() {
        if (!canRedo()) return
        cursor++
        rebuildFromHistory()
        onStackChanged?.invoke()
    }

    private fun push(op: Op) {
        // Drop any redo tail
        if (cursor < ops.lastIndex) {
            ops.subList(cursor + 1, ops.size).clear()
        }
        ops.add(op)
        cursor = ops.lastIndex
        onStackChanged?.invoke()
    }

    private fun toParams(p: Paint) = PaintParams(
        color = p.color,
        alpha = p.alpha,
        strokeWidth = p.strokeWidth,
        cap = p.strokeCap,
        join = p.strokeJoin
    )

    private fun buildPaint(params: PaintParams): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = params.color
            alpha = params.alpha
            strokeWidth = params.strokeWidth
            strokeCap = params.cap
            strokeJoin = params.join
        }

    private fun rebuildFromHistory() {
        val ic = inkCanvas ?: return
        val hc = hlCanvas ?: return
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        hlBitmap?.eraseColor(Color.TRANSPARENT)
        for (i in 0..cursor) {
            val op = ops[i]
            when (op) {
                is Op.DrawPath -> {
                    val p = buildPaint(op.params)
                    when (op.layer) {
                        Layer.INK -> ic.drawPath(op.path, p)
                        Layer.HL -> hc.drawPath(op.path, p)
                    }
                }
                is Op.DrawStraightHl -> {
                    val p = buildPaint(op.params)
                    hc.drawLine(op.startX, op.y, op.endX, op.y, p)
                }
                is Op.ErasePath -> {
                    val p = Paint(eraserPaint)
                    p.strokeWidth = op.strokeWidth
                    if (op.highlightsOnly) {
                        hc.drawPath(op.path, p)
                    } else {
                        ic.drawPath(op.path, p)
                        hc.drawPath(op.path, p)
                    }
                }
                is Op.EraseDots -> {
                    val fill = Paint(eraserFillPaint)
                    if (op.highlightsOnly) {
                        for (pt in op.dots) hc.drawCircle(pt.x, pt.y, op.radius, fill)
                    } else {
                        for (pt in op.dots) {
                            ic.drawCircle(pt.x, pt.y, op.radius, fill)
                            hc.drawCircle(pt.x, pt.y, op.radius, fill)
                        }
                    }
                }
                Op.Clear -> {
                    inkBitmap?.eraseColor(Color.TRANSPARENT)
                    hlBitmap?.eraseColor(Color.TRANSPARENT)
                }
            }
        }
        invalidate()
    }

    // ---------------- Lifecycle ----------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        fun newBitmap(): Bitmap =
            Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)

        inkBitmap?.recycle()
        hlBitmap?.recycle()

        inkBitmap = newBitmap()
        hlBitmap = newBitmap()
        inkCanvas = Canvas(inkBitmap!!)
        hlCanvas = Canvas(hlBitmap!!)

        // Repaint from history on size (e.g., rotation)
        rebuildFromHistory()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        inkBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        hlBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    // ---------------- Input ----------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downY = y
                lastX = x
                lastY = y
                path.reset()
                path.moveTo(x, y)
                areaDots.clear()

                if (tool == Tool.ERASER && eraserMode == EraserMode.AREA) {
                    stampEraseDot(x, y)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val midX = (lastX + x) / 2f
                val midY = (lastY + y) / 2f
                path.quadTo(lastX, lastY, midX, midY)
                lastX = x
                lastY = y

                when (tool) {
                    Tool.STYLUS -> inkCanvas?.drawPath(path, penPaint)
                    Tool.HIGHLIGHTER -> {
                        if (highlightMode == HighlightMode.FREEFORM) {
                            hlCanvas?.drawPath(path, hlPaint)
                        }
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            erasePathLive(path)
                        } else {
                            stampEraseDot(x, y)
                        }
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (tool) {
                    Tool.STYLUS -> {
                        // Commit stroke to history
                        push(Op.DrawPath(Layer.INK, Path(path), toParams(penPaint)))
                    }
                    Tool.HIGHLIGHTER -> {
                        if (highlightMode == HighlightMode.FREEFORM) {
                            push(Op.DrawPath(Layer.HL, Path(path), toParams(hlPaint)))
                        } else {
                            // Straight horizontal
                            hlCanvas?.drawLine(downX, downY, x, downY, hlPaint)
                            push(
                                Op.DrawStraightHl(
                                    startX = downX, endX = x, y = downY, params = toParams(hlPaint)
                                )
                            )
                        }
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            push(
                                Op.ErasePath(
                                    path = Path(path),
                                    strokeWidth = eraserSize,
                                    highlightsOnly = eraseHighlightsOnly
                                )
                            )
                        } else {
                            // area dots
                            if (areaDots.isNotEmpty()) {
                                push(
                                    Op.EraseDots(
                                        dots = ArrayList(areaDots),
                                        radius = eraserSize / 2f,
                                        highlightsOnly = eraseHighlightsOnly
                                    )
                                )
                            }
                        }
                    }
                }
                path.reset()
                areaDots.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun erasePathLive(p: Path) {
        val cStroke = Paint(eraserPaint).apply { strokeWidth = eraserSize }
        if (eraseHighlightsOnly) {
            hlCanvas?.drawPath(p, cStroke)
        } else {
            inkCanvas?.drawPath(p, cStroke)
            hlCanvas?.drawPath(p, cStroke)
        }
    }

    private fun stampEraseDot(x: Float, y: Float) {
        val r = eraserSize / 2f
        areaDots.add(PointF(x, y))
        val fill = eraserFillPaint
        if (eraseHighlightsOnly) {
            hlCanvas?.drawCircle(x, y, r, fill)
        } else {
            inkCanvas?.drawCircle(x, y, r, fill)
            hlCanvas?.drawCircle(x, y, r, fill)
        }
    }

    // ---------------- Public API ----------------
    fun setTool(t: Tool) { tool = t }

    fun setStylusBrush(b: StylusBrush) {
        stylusBrush = b
        when (b) {
            StylusBrush.FOUNTAIN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 255
            }
            StylusBrush.MARKER -> {
                penPaint.strokeCap = Paint.Cap.SQUARE
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 230
            }
        }
        invalidate()
    }

    fun setStylusSize(px: Float) {
        stylusSize = px.coerceAtLeast(1f)
        penPaint.strokeWidth = stylusSize
        invalidate()
    }

    fun setStylusColor(color: Int) {
        stylusColor = color
        penPaint.color = stylusColor
        invalidate()
    }

    fun setHighlighterSize(px: Float) {
        highlighterSize = px.coerceAtLeast(1f)
        hlPaint.strokeWidth = highlighterSize
        invalidate()
    }

    fun setHighlighterOpacity(opacity01: Float) {
        val op = opacity01.coerceIn(0f, 1f)
        highlighterOpacity = op
        hlPaint.alpha = (255 * op).toInt()
        invalidate()
    }

    fun setHighlighterColor(color: Int) {
        highlighterColor = color
        hlPaint.color = color
        invalidate()
    }

    fun setHighlightMode(mode: HighlightMode) {
        highlightMode = mode
    }

    fun setEraserMode(mode: EraserMode) {
        eraserMode = mode
    }

    fun setEraserSize(px: Float) {
        eraserSize = px.coerceAtLeast(1f)
        eraserPaint.strokeWidth = eraserSize
        invalidate()
    }

    fun setEraseHighlightsOnly(only: Boolean) {
        eraseHighlightsOnly = only
    }

    fun clearAll() {
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        hlBitmap?.eraseColor(Color.TRANSPARENT)
        push(Op.Clear)
        invalidate()
    }
}

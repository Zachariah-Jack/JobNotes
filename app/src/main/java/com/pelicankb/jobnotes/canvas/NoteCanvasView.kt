package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class NoteCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // -------- Public enums / API --------
    enum class Tool { STYLUS, HIGHLIGHTER, ERASER, SELECT }
    enum class StylusBrush { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    enum class HighlightMode { FREEFORM, STRAIGHT }
    enum class EraserMode { STROKE, AREA }
    enum class SelectMode { RECT, LASSO }

    private enum class Layer { INK, HL }
    private enum class Handle {
        NONE, MOVE, TL, TR, BL, BR, L, R, T, B
    }

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

    private var selectMode = SelectMode.RECT

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

    // Live gesture path (drawing/erasing)
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
        val join: Paint.Join,
        val maskFilter: MaskFilter? = null,
        val pathEffect: PathEffect? = null
    )

    private sealed class Op {
        data class DrawPath(val layer: Layer, val path: Path, val params: PaintParams) : Op()
        data class DrawStraightHl(
            val startX: Float, val endX: Float, val y: Float, val params: PaintParams
        ) : Op()
        data class ErasePath(
            val path: Path, val strokeWidth: Float, val highlightsOnly: Boolean
        ) : Op()
        data class EraseDots(
            val dots: List<PointF>, val radius: Float, val highlightsOnly: Boolean
        ) : Op()
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
        join = p.strokeJoin,
        maskFilter = p.maskFilter,
        pathEffect = p.pathEffect
    )

    private fun buildPaint(params: PaintParams): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = params.color
            alpha = params.alpha
            strokeWidth = params.strokeWidth
            strokeCap = params.cap
            strokeJoin = params.join
            maskFilter = params.maskFilter
            pathEffect = params.pathEffect
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

    // ---------------- Selection state ----------------
    private var selectionActive = false
    private var selectionRect = RectF()
    private var selectionOriginalRect = RectF()
    private var selecting = false
    private var activeHandle = Handle.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var previewMatrix = Matrix()

    private data class SelectedEntry(val index: Int, val drawPath: Path?, val drawLine: FloatArray?, val layer: Layer?, val params: PaintParams?)
    private val selected = mutableListOf<SelectedEntry>()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val previewOverlayAlpha = 180

    private fun dp(x: Float): Float = x * resources.displayMetrics.density
    private val handleSize get() = dp(10f)
    private val handleTouchPad get() = dp(18f)

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

        // Selection overlay
        if (tool == Tool.SELECT && selectionActive) {
            // Preview selected content with current transform
            if (!previewMatrix.isIdentity) {
                canvas.save()
                canvas.concat(previewMatrix)
                for (s in selected) {
                    val p = s.params?.let { buildPaint(it) } ?: continue
                    p.alpha = previewOverlayAlpha
                    when {
                        s.drawPath != null && s.layer != null -> {
                            if (s.layer == Layer.INK) canvas.drawPath(s.drawPath, p)
                            else canvas.drawPath(s.drawPath, p)
                        }
                        s.drawLine != null -> {
                            val (sx, ex, y) = s.drawLine
                            canvas.drawLine(sx, y, ex, y, p)
                        }
                    }
                }
                canvas.restore()
            }

            // Border + handles
            canvas.drawRect(selectionRect, borderPaint)
            drawHandles(canvas)
        }
    }

    private fun drawHandles(c: Canvas) {
        fun drawHandle(cx: Float, cy: Float) {
            c.drawRect(cx - handleSize, cy - handleSize, cx + handleSize, cy + handleSize, handlePaint)
        }
        val r = selectionRect
        drawHandle(r.left, r.top)      // TL
        drawHandle(r.right, r.top)     // TR
        drawHandle(r.left, r.bottom)   // BL
        drawHandle(r.right, r.bottom)  // BR
        drawHandle((r.left + r.right) / 2f, r.top)    // T
        drawHandle((r.left + r.right) / 2f, r.bottom) // B
        drawHandle(r.left, (r.top + r.bottom) / 2f)   // L
        drawHandle(r.right, (r.top + r.bottom) / 2f)  // R
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

                if (tool == Tool.SELECT) {
                    handleSelectDown(x, y)
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
                        // straight is committed on UP
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            erasePathLive(path)
                        } else {
                            stampEraseDot(x, y)
                        }
                    }
                    Tool.SELECT -> handleSelectMove(x, y)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (tool) {
                    Tool.STYLUS -> {
                        push(Op.DrawPath(Layer.INK, Path(path), toParams(penPaint)))
                    }
                    Tool.HIGHLIGHTER -> {
                        if (highlightMode == HighlightMode.FREEFORM) {
                            push(Op.DrawPath(Layer.HL, Path(path), toParams(hlPaint)))
                        } else {
                            // Straight horizontal for now (we will generalize to arbitrary angle later)
                            hlCanvas?.drawLine(downX, downY, x, downY, hlPaint)
                            push(Op.DrawStraightHl(downX, x, downY, toParams(hlPaint)))
                        }
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            push(Op.ErasePath(Path(path), eraserSize, eraseHighlightsOnly))
                        } else if (areaDots.isNotEmpty()) {
                            push(Op.EraseDots(ArrayList(areaDots), eraserSize / 2f, eraseHighlightsOnly))
                        }
                    }
                    Tool.SELECT -> handleSelectUp(x, y)
                }
                path.reset()
                areaDots.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    // ---- Select: down/move/up helpers ----
    private fun handleSelectDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y
        previewMatrix.reset()

        if (selectionActive && selectionRect.contains(x, y)) {
            activeHandle = hitHandle(x, y)
            selectionOriginalRect.set(selectionRect)
            selecting = false
            return
        }

        // Start new selection
        activeHandle = Handle.NONE
        selecting = true
        selectionActive = true
        selectionRect.set(x, y, x, y)
        selectionOriginalRect.set(selectionRect)
        selected.clear()
    }

    private fun handleSelectMove(x: Float, y: Float) {
        if (!selectionActive) return

        if (selecting) {
            selectionRect.set(
                min(downX, x),
                min(downY, y),
                max(downX, x),
                max(downY, y)
            )
            selectionOriginalRect.set(selectionRect)
            return
        }

        // Moving/resizing existing selection
        val dx = x - touchStartX
        val dy = y - touchStartY

        when (activeHandle) {
            Handle.MOVE -> {
                previewMatrix.reset()
                previewMatrix.postTranslate(dx, dy)
                // Update rect visually during drag
                selectionRect.set(
                    selectionOriginalRect.left + dx,
                    selectionOriginalRect.top + dy,
                    selectionOriginalRect.right + dx,
                    selectionOriginalRect.bottom + dy
                )
            }
            Handle.TL, Handle.TR, Handle.BL, Handle.BR, Handle.L, Handle.R, Handle.T, Handle.B -> {
                // Resize with FILL (non-uniform ok)
                val new = RectF(selectionOriginalRect)
                when (activeHandle) {
                    Handle.TL -> { new.left += dx; new.top += dy }
                    Handle.TR -> { new.right += dx; new.top += dy }
                    Handle.BL -> { new.left += dx; new.bottom += dy }
                    Handle.BR -> { new.right += dx; new.bottom += dy }
                    Handle.L  -> { new.left += dx }
                    Handle.R  -> { new.right += dx }
                    Handle.T  -> { new.top += dy }
                    Handle.B  -> { new.bottom += dy }
                    else -> {}
                }
                // Prevent inverted rect
                if (new.width() < 1f || new.height() < 1f) return
                selectionRect.set(new)

                // Build matrix that maps original->current selection rect
                previewMatrix.reset()
                val m = Matrix()
                val src = RectF(selectionOriginalRect)
                val dst = RectF(selectionRect)
                m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL)
                previewMatrix.set(m)
            }
            else -> {}
        }
    }

    private fun handleSelectUp(x: Float, y: Float) {
        if (!selectionActive) return

        if (selecting) {
            // Finalize selection rectangle
            normalize(selectionRect)
            selectionOriginalRect.set(selectionRect)
            selected.clear()
            pickSelected(selectionRect, selected)
            selecting = false
            activeHandle = Handle.MOVE // convenient default for next drag
            return
        }

        // Commit move/resize by mutating selected ops
        if (!previewMatrix.isIdentity && selected.isNotEmpty()) {
            applyTransformToSelection(previewMatrix)
            previewMatrix.reset()
            // Keep selection active with its new rect as baseline for next interaction
            selectionOriginalRect.set(selectionRect)
            rebuildFromHistory()
        }

        activeHandle = Handle.NONE
    }

    private fun hitHandle(x: Float, y: Float): Handle {
        val r = selectionRect
        fun hit(cx: Float, cy: Float): Boolean =
            abs(x - cx) <= handleTouchPad && abs(y - cy) <= handleTouchPad

        // corners
        if (hit(r.left, r.top)) return Handle.TL
        if (hit(r.right, r.top)) return Handle.TR
        if (hit(r.left, r.bottom)) return Handle.BL
        if (hit(r.right, r.bottom)) return Handle.BR
        // edges
        if (hit((r.left + r.right) / 2f, r.top)) return Handle.T
        if (hit((r.left + r.right) / 2f, r.bottom)) return Handle.B
        if (hit(r.left, (r.top + r.bottom) / 2f)) return Handle.L
        if (hit(r.right, (r.top + r.bottom) / 2f)) return Handle.R
        // otherwise inside -> move
        return if (r.contains(x, y)) Handle.MOVE else Handle.NONE
    }

    private fun normalize(r: RectF) {
        val l = min(r.left, r.right)
        val t = min(r.top, r.bottom)
        val rgt = max(r.left, r.right)
        val btm = max(r.top, r.bottom)
        r.set(l, t, rgt, btm)
    }

    private fun pickSelected(rect: RectF, out: MutableList<SelectedEntry>) {
        for (i in 0..cursor) {
            when (val op = ops[i]) {
                is Op.DrawPath -> {
                    val b = RectF()
                    op.path.computeBounds(b, true)
                    if (RectF.intersects(b, rect)) {
                        out += SelectedEntry(
                            index = i,
                            drawPath = Path(op.path),
                            drawLine = null,
                            layer = op.layer,
                            params = op.params
                        )
                    }
                }
                is Op.DrawStraightHl -> {
                    // AABB check against the line segment (horizontal for now)
                    val y = op.y
                    val sx = min(op.startX, op.endX)
                    val ex = max(op.startX, op.endX)
                    val hit = (y >= rect.top && y <= rect.bottom &&
                            ex >= rect.left && sx <= rect.right)
                    if (hit) {
                        out += SelectedEntry(
                            index = i,
                            drawPath = null,
                            drawLine = floatArrayOf(op.startX, op.endX, op.y),
                            layer = Layer.HL,
                            params = op.params
                        )
                    }
                }
                else -> { /* ignore erasers & clear */ }
            }
        }
    }

    private fun applyTransformToSelection(m: Matrix) {
        // Mutate the underlying ops in place for the selected entries
        // NOTE: This first cut does not make selection transforms undoable yet.
        for (s in selected) {
            when (val op = ops[s.index]) {
                is Op.DrawPath -> {
                    val newPath = Path(op.path)
                    newPath.transform(m)
                    ops[s.index] = Op.DrawPath(op.layer, newPath, op.params)
                }
                is Op.DrawStraightHl -> {
                    val pts = floatArrayOf(op.startX, op.y, op.endX, op.y)
                    m.mapPoints(pts)
                    val newY = pts[1] // mapped y (both should match if purely translate/scale)
                    ops[s.index] = Op.DrawStraightHl(pts[0], pts[2], newY, op.params)
                }
                else -> {}
            }
        }
    }

    // ---------------- Drawing/Eraser helpers ----------------
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
    fun setTool(t: Tool) {
        tool = t
        // When switching away from select, keep selection overlay off unless returning
        if (t != Tool.SELECT) {
            // leave selection visible if you prefer; for now, keep it visible
        }
        invalidate()
    }

    fun setStylusBrush(b: StylusBrush) {
        stylusBrush = b
        penPaint.maskFilter = null
        penPaint.pathEffect = null

        when (b) {
            StylusBrush.FOUNTAIN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 255
            }
            StylusBrush.CALLIGRAPHY -> {
                penPaint.strokeCap = Paint.Cap.BUTT
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 255
                // mild flattening effect
                penPaint.pathEffect = CornerPathEffect(2f)
            }
            StylusBrush.PEN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.MITER
                penPaint.alpha = 255
            }
            StylusBrush.PENCIL -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 200
                penPaint.maskFilter = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
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

    fun setSelectMode(mode: SelectMode) {
        selectMode = mode
        // LASSO will be implemented next
    }

    fun clearSelection() {
        selectionActive = false
        selected.clear()
        previewMatrix.reset()
        invalidate()
    }

    fun clearAll() {
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        hlBitmap?.eraseColor(Color.TRANSPARENT)
        push(Op.Clear)
        invalidate()
    }
}

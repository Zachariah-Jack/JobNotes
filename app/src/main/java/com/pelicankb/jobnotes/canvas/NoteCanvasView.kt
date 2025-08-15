package com.pelicankb.jobnotes.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class NoteCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Public tool enums ---------------------------------------------------
    enum class Tool { STYLUS, HIGHLIGHTER, ERASER, SELECT }
    enum class StylusBrush { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    enum class HighlightMode { FREEFORM, STRAIGHT }
    enum class EraserMode { STROKE, AREA }
    enum class SelectionMode { RECT, LASSO }
    private enum class Layer { INK, HL }

    // ---- Drawing state -------------------------------------------------------
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

    var selectionMode = SelectionMode.RECT
        private set

    // Two compositing layers so eraser can target highlights only
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

    private val pencilMask = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)

    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = highlighterColor
        alpha = (255 * highlighterOpacity).toInt()
        strokeWidth = highlighterSize
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }

    private val hlPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = highlighterColor
        alpha = (255 * highlighterOpacity).toInt()
        strokeWidth = highlighterSize
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
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

    // Gesture
    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // For AREA eraser (dot stamping)
    private val areaDots = ArrayList<PointF>()

    // ---- History (vector ops; we redraw bitmaps from this list) -------------
    private data class PaintParams(
        val color: Int,
        val alpha: Int,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join
    )

    private sealed class Op {
        data class DrawPath(val layer: Layer, val path: Path, val params: PaintParams) : Op()
        data class ErasePath(val path: Path, val strokeWidth: Float, val highlightsOnly: Boolean) : Op()
        data class EraseDots(val dots: List<PointF>, val radius: Float, val highlightsOnly: Boolean) : Op()
        data class TransformPaths(val indices: List<Int>, val before: List<Path>, val after: List<Path>) : Op()
        object Clear : Op()
    }

    private val ops = mutableListOf<Op>()
    private var cursor = -1 // index of last applied op; -1 means empty

    var onStackChanged: (() -> Unit)? = null

    fun canUndo(): Boolean = cursor >= 0
    fun canRedo(): Boolean = cursor < ops.size - 1

    fun undo() {
        if (!canUndo()) return
        val op = ops[cursor]
        if (op is Op.TransformPaths) {
            applySnapshotToOps(op.indices, op.before)
        }
        cursor--
        rebuildFromHistory()
        onStackChanged?.invoke()
    }

    fun redo() {
        if (!canRedo()) return
        val next = ops[cursor + 1]
        if (next is Op.TransformPaths) {
            applySnapshotToOps(next.indices, next.after)
        }
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
            when (val op = ops[i]) {
                is Op.DrawPath -> {
                    val p = buildPaint(op.params)
                    when (op.layer) {
                        Layer.INK -> ic.drawPath(op.path, p)
                        Layer.HL -> hc.drawPath(op.path, p)
                    }
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
                        for (pt in op.dots) hlCanvas?.drawCircle(pt.x, pt.y, op.radius, fill)
                    } else {
                        for (pt in op.dots) {
                            inkCanvas?.drawCircle(pt.x, pt.y, op.radius, fill)
                            hlCanvas?.drawCircle(pt.x, pt.y, op.radius, fill)
                        }
                    }
                }
                is Op.TransformPaths -> {
                    // no drawing; these ops mutate paths and are handled in undo/redo hooks
                }
                Op.Clear -> {
                    inkBitmap?.eraseColor(Color.TRANSPARENT)
                    hlBitmap?.eraseColor(Color.TRANSPARENT)
                }
            }
        }
        invalidate()
    }

    private fun applySnapshotToOps(indices: List<Int>, snapshots: List<Path>) {
        for (i in indices.indices) {
            val opIndex = indices[i]
            val snap = snapshots[i]
            val op = ops[opIndex]
            if (op is Op.DrawPath) {
                op.path.set(snap)
            }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------
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

        rebuildFromHistory()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        inkBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        hlBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Highlighter straight-line live preview
        straightPreviewStart?.let { s ->
            straightPreviewEnd?.let { e ->
                canvas.drawLine(s.x, s.y, e.x, e.y, hlPreviewPaint)
            }
        }

        // Selection / marquee visuals
        drawSelectionOverlay(canvas)
    }

    // ---- Input ---------------------------------------------------------------
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

                // Selection tool special handling
                if (tool == Tool.SELECT) {
                    handleSelectDown(x, y)
                    invalidate()
                    return true
                }

                if (tool == Tool.ERASER && eraserMode == EraserMode.AREA) {
                    stampEraseDot(x, y)
                }
                // When drawing, clear selection so it doesn't get in the way
                if (tool != Tool.SELECT) clearSelection()
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (tool == Tool.SELECT) {
                    handleSelectMove(x, y)
                    return true
                }

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
                        } else {
                            // live straight-line preview
                            straightPreviewStart = PointF(downX, downY)
                            straightPreviewEnd = PointF(x, y)
                        }
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            erasePathLive(path)
                        } else {
                            stampEraseDot(x, y)
                        }
                    }
                    Tool.SELECT -> { /* handled above */ }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == Tool.SELECT) {
                    handleSelectUp(x, y)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                when (tool) {
                    Tool.STYLUS -> {
                        push(Op.DrawPath(Layer.INK, Path(path), toParams(penPaint)))
                    }
                    Tool.HIGHLIGHTER -> {
                        if (highlightMode == HighlightMode.FREEFORM) {
                            push(Op.DrawPath(Layer.HL, Path(path), toParams(hlPaint)))
                        } else {
                            // Commit the straight line at any angle
                            val p = Path().apply {
                                moveTo(downX, downY)
                                lineTo(x, y)
                            }
                            hlCanvas?.drawPath(p, hlPaint)
                            push(Op.DrawPath(Layer.HL, p, toParams(hlPaint)))
                        }
                        // clear preview
                        straightPreviewStart = null
                        straightPreviewEnd = null
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
                    Tool.SELECT -> { /* handled above */ }
                }
                path.reset()
                areaDots.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    // ---- Selection implementation -------------------------------------------
    private enum class DragKind {
        NONE, NEW_RECT, LASSO, MOVE,
        RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR,
        RESIZE_L, RESIZE_R, RESIZE_T, RESIZE_B
    }

    private var dragKind = DragKind.NONE
    private var selectingRect: RectF? = null      // marquee while dragging
    private var lassoPath: Path? = null           // freeform while dragging
    private val selectedIndices = mutableSetOf<Int>()
    private var selectionRect: RectF? = null      // union of selected ops

    private var originalsForTransform: MutableMap<Int, Path>? = null
    private var origSelectionRect: RectF? = null

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = dp(1f)
    }
    private val handleSize = dp(14f)
    private val minRect = dp(12f)

    private fun handleSelectDown(x: Float, y: Float) {
        // If we already have a selection, check handles / move hit-testing
        selectionRect?.let { rect ->
            val hit = hitHandle(x, y, rect)
            if (hit != DragKind.NONE) {
                dragKind = hit
                prepareTransform()
                return
            }
            if (rect.contains(x, y)) {
                dragKind = DragKind.MOVE
                prepareTransform()
                return
            }
        }

        // Otherwise begin new selection
        if (selectionMode == SelectionMode.RECT) {
            selectingRect = RectF(x, y, x, y)
            dragKind = DragKind.NEW_RECT
        } else {
            lassoPath = Path().apply { moveTo(x, y) }
            dragKind = DragKind.LASSO
        }
    }

    private fun handleSelectMove(x: Float, y: Float) {
        when (dragKind) {
            DragKind.NEW_RECT -> {
                selectingRect?.let {
                    it.right = x; it.bottom = y
                }
            }
            DragKind.LASSO -> {
                lassoPath?.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
            }
            DragKind.MOVE -> {
                val dx = x - downX
                val dy = y - downY
                origSelectionRect?.let { from ->
                    val to = RectF(from.left + dx, from.top + dy, from.right + dx, from.bottom + dy)
                    applyLiveTransform(from, to)
                }
            }
            DragKind.RESIZE_TL, DragKind.RESIZE_TR, DragKind.RESIZE_BL, DragKind.RESIZE_BR,
            DragKind.RESIZE_L, DragKind.RESIZE_R, DragKind.RESIZE_T, DragKind.RESIZE_B -> {
                origSelectionRect?.let { from ->
                    val to = RectF(from)
                    when (dragKind) {
                        DragKind.RESIZE_TL -> { to.left = x; to.top = y }
                        DragKind.RESIZE_TR -> { to.right = x; to.top = y }
                        DragKind.RESIZE_BL -> { to.left = x; to.bottom = y }
                        DragKind.RESIZE_BR -> { to.right = x; to.bottom = y }
                        DragKind.RESIZE_L -> { to.left = x }
                        DragKind.RESIZE_R -> { to.right = x }
                        DragKind.RESIZE_T -> { to.top = y }
                        DragKind.RESIZE_B -> { to.bottom = y }
                        else -> {}
                    }
                    normalizeRect(to)
                    ensureMinSize(to)
                    applyLiveTransform(from, to)
                }
            }
            else -> {}
        }
        lastX = x
        lastY = y
        invalidate()
    }

    private fun handleSelectUp(x: Float, y: Float) {
        when (dragKind) {
            DragKind.NEW_RECT -> {
                selectingRect?.let { r ->
                    normalizeRect(r)
                    ensureMinSize(r)
                    commitRectSelection(r)
                }
            }
            DragKind.LASSO -> {
                lassoPath?.let { lp ->
                    commitLassoSelection(lp)
                }
            }
            DragKind.MOVE, DragKind.RESIZE_TL, DragKind.RESIZE_TR, DragKind.RESIZE_BL, DragKind.RESIZE_BR,
            DragKind.RESIZE_L, DragKind.RESIZE_R, DragKind.RESIZE_T, DragKind.RESIZE_B -> {
                // finalize a transform op for undo/redo
                val indices = selectedIndices.toList().sorted()
                val before = originalsForTransform?.let { map ->
                    indices.map { Path(map[it]!!) }
                } ?: emptyList()
                val after = indices.map { idx ->
                    val op = ops[idx] as Op.DrawPath
                    Path(op.path)
                }
                if (indices.isNotEmpty() && before.isNotEmpty()) {
                    push(Op.TransformPaths(indices, before, after))
                }
            }
            else -> {}
        }

        // Clear in‑progress marquee/lasso + temp caches
        selectingRect = null
        lassoPath = null
        originalsForTransform = null
        origSelectionRect = null
        dragKind = DragKind.NONE
        invalidate()
    }

    private fun prepareTransform() {
        origSelectionRect = RectF(selectionRect)
        originalsForTransform = mutableMapOf<Int, Path>().also { map ->
            for (idx in selectedIndices) {
                val op = ops[idx]
                if (op is Op.DrawPath) map[idx] = Path(op.path)
            }
        }
    }

    private fun applyLiveTransform(from: RectF, to: RectF) {
        val m = Matrix().apply { setRectToRect(from, to, Matrix.ScaleToFit.FILL) }
        // Reset all selected paths to originals and transform
        originalsForTransform?.forEach { (idx, origPath) ->
            val op = ops[idx]
            if (op is Op.DrawPath) {
                val p = Path(origPath)
                p.transform(m)
                op.path.set(p)
            }
        }
        selectionRect = RectF(to)
        rebuildFromHistory()
    }

    private fun commitRectSelection(rect: RectF) {
        selectedIndices.clear()
        val r = RectF(rect)
        val strokeInflate = 2f
        for (i in 0..cursor) {
            val op = ops[i]
            if (op is Op.DrawPath) {
                val b = RectF()
                op.path.computeBounds(b, true)
                b.inset(-strokeInflate - op.params.strokeWidth / 2f, -strokeInflate - op.params.strokeWidth / 2f)
                if (RectF.intersects(b, r)) {
                    selectedIndices.add(i)
                }
            }
        }
        selectionRect = unionBounds(selectedIndices)
        lassoPath = null
        selectingRect = null
        dragKind = DragKind.NONE
        invalidate()
    }

    private fun commitLassoSelection(lp: Path) {
        selectedIndices.clear()
        val lassoRegion = Region().apply {
            setPath(lp, Region(0, 0, width, height))
        }
        for (i in 0..cursor) {
            val op = ops[i]
            if (op is Op.DrawPath) {
                val b = RectF()
                op.path.computeBounds(b, true)
                val clip = Region(b.left.toInt() - 2, b.top.toInt() - 2, b.right.toInt() + 2, b.bottom.toInt() + 2)
                val pr = Region().apply { setPath(op.path, clip) }
                val test = Region(pr)
                val nonEmpty = test.op(lassoRegion, Region.Op.INTERSECT)
                if (nonEmpty && !test.isEmpty) {
                    selectedIndices.add(i)
                }
            }
        }
        selectionRect = unionBounds(selectedIndices)
        lassoPath = null
        selectingRect = null
        dragKind = DragKind.NONE
        invalidate()
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        // in-progress new rect
        selectingRect?.let { r ->
            val nr = RectF(r); normalizeRect(nr)
            canvas.drawRect(nr, selectionPaint)
            return
        }
        // in-progress lasso
        lassoPath?.let { lp ->
            canvas.drawPath(lp, selectionPaint)
            return
        }
        // committed selection
        selectionRect?.let { rect ->
            canvas.drawRect(rect, selectionPaint)
            // handles
            for (h in handleRects(rect).values) {
                canvas.drawRect(h, handleFill)
                canvas.drawRect(h, handleStroke)
            }
        }
    }

    private fun hitHandle(x: Float, y: Float, rect: RectF): DragKind {
        val hs = handleRects(rect)
        for ((k, r) in hs) {
            if (r.contains(x, y)) return k
        }
        return DragKind.NONE
    }

    private fun handleRects(rect: RectF): Map<DragKind, RectF> {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val hs = handleSize
        fun box(cx: Float, cy: Float): RectF = RectF(cx - hs / 2, cy - hs / 2, cx + hs / 2, cy + hs / 2)
        return mapOf(
            DragKind.RESIZE_TL to box(rect.left, rect.top),
            DragKind.RESIZE_TR to box(rect.right, rect.top),
            DragKind.RESIZE_BL to box(rect.left, rect.bottom),
            DragKind.RESIZE_BR to box(rect.right, rect.bottom),
            DragKind.RESIZE_L to box(rect.left, cy),
            DragKind.RESIZE_R to box(rect.right, cy),
            DragKind.RESIZE_T to box(cx, rect.top),
            DragKind.RESIZE_B to box(cx, rect.bottom),
        )
    }

    private fun unionBounds(indices: Set<Int>): RectF? {
        var out: RectF? = null
        for (idx in indices) {
            val op = ops[idx]
            if (op is Op.DrawPath) {
                val b = RectF()
                op.path.computeBounds(b, true)
                if (out == null) out = RectF(b) else out!!.union(b)
            }
        }
        return out
    }

    private fun normalizeRect(r: RectF) {
        val l = min(r.left, r.right)
        val t = min(r.top, r.bottom)
        val rgt = max(r.left, r.right)
        val btm = max(r.top, r.bottom)
        r.set(l, t, rgt, btm)
    }

    private fun ensureMinSize(r: RectF) {
        if (r.width() < minRect) {
            val grow = (minRect - r.width()) / 2f
            r.left -= grow; r.right += grow
        }
        if (r.height() < minRect) {
            val grow = (minRect - r.height()) / 2f
            r.top -= grow; r.bottom += grow
        }
    }

    // ---- Erasing helpers -----------------------------------------------------
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

    // ---- Public API ----------------------------------------------------------
    fun setTool(t: Tool) {
        tool = t
        if (t != Tool.SELECT) {
            // Hide overlays if switching away
            selectingRect = null
            lassoPath = null
            dragKind = DragKind.NONE
        }
        invalidate()
    }

    fun setStylusBrush(b: StylusBrush) {
        stylusBrush = b
        // lightweight visual differences for now; we can deepen later
        when (b) {
            StylusBrush.FOUNTAIN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 255
                penPaint.maskFilter = null
            }
            StylusBrush.CALLIGRAPHY -> {
                penPaint.strokeCap = Paint.Cap.BUTT
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 240
                penPaint.maskFilter = null
            }
            StylusBrush.PEN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 255
                penPaint.maskFilter = null
            }
            StylusBrush.PENCIL -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 200
                penPaint.maskFilter = pencilMask
            }
            StylusBrush.MARKER -> {
                penPaint.strokeCap = Paint.Cap.SQUARE
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 230
                penPaint.maskFilter = null
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
        hlPreviewPaint.strokeWidth = highlighterSize
        invalidate()
    }

    fun setHighlighterOpacity(opacity01: Float) {
        val op = opacity01.coerceIn(0f, 1f)
        highlighterOpacity = op
        val a = (255 * op).toInt()
        hlPaint.alpha = a
        hlPreviewPaint.alpha = a
        invalidate()
    }

    fun setHighlighterColor(color: Int) {
        highlighterColor = color
        hlPaint.color = color
        hlPreviewPaint.color = color
        invalidate()
    }

    fun setHighlightMode(mode: HighlightMode) {
        highlightMode = mode
        // clear preview if switching out of straight
        if (mode == HighlightMode.FREEFORM) {
            straightPreviewStart = null
            straightPreviewEnd = null
        }
    }

    fun setEraserMode(mode: EraserMode) { eraserMode = mode }

    fun setEraserSize(px: Float) {
        eraserSize = px.coerceAtLeast(1f)
        eraserPaint.strokeWidth = eraserSize
        invalidate()
    }

    fun setEraseHighlightsOnly(only: Boolean) { eraseHighlightsOnly = only }

    fun clearAll() {
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        hlBitmap?.eraseColor(Color.TRANSPARENT)
        push(Op.Clear)
        clearSelection()
        invalidate()
    }

    fun setSelectionMode(mode: SelectionMode) {
        selectionMode = mode
        // when switching mode, drop any in-progress marquee
        selectingRect = null
        lassoPath = null
        invalidate()
    }

    fun clearSelection() {
        selectingRect = null
        lassoPath = null
        dragKind = DragKind.NONE
        selectedIndices.clear()
        selectionRect = null
        originalsForTransform = null
        origSelectionRect = null
        invalidate()
    }

    // ---- Utility -------------------------------------------------------------
    private var straightPreviewStart: PointF? = null
    private var straightPreviewEnd: PointF? = null

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

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

    // ------------ Public tool APIs ------------
    enum class Tool { STYLUS, HIGHLIGHTER, ERASER, SELECT }

    // Expanded brush set (we’ll tune personalities later)
    enum class StylusBrush { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }

    enum class HighlightMode { FREEFORM, STRAIGHT }
    enum class EraserMode { STROKE, AREA }

    enum class SelectMode { LASSO, RECT }

    // ------------ Internal ------------
    private enum class Layer { INK, HL }

    // App state
    private var tool = Tool.STYLUS
    private var selectMode = SelectMode.RECT

    // Stylus
    private var stylusBrush = StylusBrush.FOUNTAIN
    private var stylusSize = 8f
    private var stylusColor = Color.BLACK

    // Highlighter
    private var highlighterSize = 24f
    private var highlighterColor = Color.YELLOW
    private var highlighterOpacity = 0.5f // 0..1
    private var highlightMode = HighlightMode.FREEFORM

    // Eraser
    private var eraserMode = EraserMode.STROKE
    private var eraserSize = 30f
    private var eraseHighlightsOnly = false

    // Two compositing layers
    private var inkBitmap: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private var hlBitmap: Bitmap? = null
    private var hlCanvas: Canvas? = null

    // Live paint objects
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
    private val eraserStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    // Gesture state
    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // For AREA eraser (dot stamping)
    private val areaDots = ArrayList<PointF>()

    // -------- Selection (new) --------
    private val selectionPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val clearOnViewPaint = Paint().apply {
        // Used to “punch a hole” on the view’s canvas while previewing a cut
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val srcRectLocal = RectF() // helper (0..w,0..h)

    // Active selection session
    private data class Selection(
        val ink: Bitmap?,               // cut-out ink layer
        val hl: Bitmap?,                // cut-out highlighter layer
        val srcPath: Path,              // selection shape (view coords)
        val srcBounds: RectF,           // tight bounds of srcPath
        var dstRect: RectF              // current destination rectangle (for move/resize)
    )
    private var activeSel: Selection? = null
    private var resizingHandle: Handle? = null
    private var dragging = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var baseDstRect = RectF()

    private enum class Handle { TL, TR, BL, BR }

    private val HANDLE_HIT = 28f     // px radius for hit-testing handles
    private val HANDLE_SIZE = 10f    // px radius for drawing handles
    private val MIN_SEL_SIZE = 8f    // px minimal selection size

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
        /** New: “Cut & Paste” produced by selection commit (works with lasso or rect). */
        data class CutPaste(
            val ink: Bitmap?,         // may be null if nothing from this layer
            val hl: Bitmap?,          // may be null if nothing from this layer
            val srcPath: Path,        // region that was cut (view coords)
            val srcBounds: RectF,     // precomputed bounds of srcPath
            val dstRect: RectF        // destination rectangle where content was pasted
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
        if (cursor < ops.lastIndex) ops.subList(cursor + 1, ops.size).clear()
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
                    if (op.layer == Layer.INK) ic.drawPath(op.path, p) else hc.drawPath(op.path, p)
                }
                is Op.DrawStraightHl -> {
                    val p = buildPaint(op.params)
                    hc.drawLine(op.startX, op.y, op.endX, op.y, p)
                }
                is Op.ErasePath -> {
                    val p = Paint(eraserStrokePaint).apply { strokeWidth = op.strokeWidth }
                    if (op.highlightsOnly) {
                        hc.drawPath(op.path, p)
                    } else {
                        ic.drawPath(op.path, p); hc.drawPath(op.path, p)
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
                is Op.CutPaste -> {
                    // Clear source region using the original path
                    ic.save(); ic.clipPath(op.srcPath); ic.drawRect(op.srcBounds, Paint().apply {
                        style = Paint.Style.FILL
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }); ic.restore()

                    hc.save(); hc.clipPath(op.srcPath); hc.drawRect(op.srcBounds, Paint().apply {
                        style = Paint.Style.FILL
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }); hc.restore()

                    // Paste captured pixels into destination rect
                    op.ink?.let { ic.drawBitmap(it, null, op.dstRect, null) }
                    op.hl?.let { hc.drawBitmap(it, null, op.dstRect, null) }
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

        rebuildFromHistory()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (activeSel == null) {
            // Normal
            inkBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            hlBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        } else {
            // Draw with a "hole" where the selection came from (so preview doesn't double)
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            inkBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            hlBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

            val sel = activeSel!!
            canvas.drawPath(sel.srcPath, clearOnViewPaint)

            // Draw the floating selection content
            sel.ink?.let { canvas.drawBitmap(it, null, sel.dstRect, null) }
            sel.hl?.let { canvas.drawBitmap(it, null, sel.dstRect, null) }

            // Draw bounding box + 4 corner handles
            canvas.drawRect(sel.dstRect, selectionPreviewPaint)
            drawHandle(canvas, sel.dstRect.left, sel.dstRect.top)
            drawHandle(canvas, sel.dstRect.right, sel.dstRect.top)
            drawHandle(canvas, sel.dstRect.left, sel.dstRect.bottom)
            drawHandle(canvas, sel.dstRect.right, sel.dstRect.bottom)

            canvas.restoreToCount(sc)
        }

        // Live drawing feedback (stylus/highlighter freeform / select marquee)
        if (tool == Tool.HIGHLIGHTER && highlightMode == HighlightMode.STRAIGHT && path.isEmpty.not()) {
            // Straight line preview (we'll generalize angle later)
            // For now we don't preview; commits on ACTION_UP. (Planned: live preview with any angle)
        } else if (tool == Tool.SELECT && path.isEmpty.not()) {
            // While making selection, show lasso/rect preview
            canvas.drawPath(path, selectionPreviewPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, HANDLE_SIZE, handlePaint)
    }

    // ---------------- Input ----------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                if (tool == Tool.SELECT) {
                    if (activeSel != null) {
                        // Transform existing selection (drag or resize) OR commit & start new
                        val sel = activeSel!!
                        resizingHandle = hitTestHandle(x, y, sel.dstRect)
                        if (resizingHandle != null) {
                            baseDstRect.set(sel.dstRect)
                            dragLastX = x
                            dragLastY = y
                            dragging = false
                            return true
                        }
                        if (sel.dstRect.contains(x, y)) {
                            dragging = true
                            dragLastX = x
                            dragLastY = y
                            return true
                        }
                        // Outside -> commit previous selection, start a new marquee
                        commitSelection()
                    }

                    // Begin new marquee (lasso/rect)
                    downX = x; downY = y
                    lastX = x; lastY = y
                    path.reset()
                    if (selectMode == SelectMode.LASSO) {
                        path.moveTo(x, y)
                    } else {
                        // RECT: we draw a dynamic rect Path during MOVE
                        // Start as a degenerate rect
                        val r = RectF(x, y, x, y)
                        rectPath(r, path)
                    }
                    invalidate()
                    return true
                }

                // Drawing/erasing start
                downX = x; downY = y
                lastX = x; lastY = y
                path.reset(); path.moveTo(x, y)
                areaDots.clear()
                if (tool == Tool.ERASER && eraserMode == EraserMode.AREA) stampEraseDot(x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val midX = (lastX + x) / 2f
                val midY = (lastY + y) / 2f

                if (tool == Tool.SELECT) {
                    activeSel?.let { sel ->
                        if (resizingHandle != null) {
                            // Resize: move one corner; opposite corner anchored
                            val r = RectF(baseDstRect)
                            when (resizingHandle) {
                                Handle.TL -> { r.left = x; r.top = y }
                                Handle.TR -> { r.right = x; r.top = y }
                                Handle.BL -> { r.left = x; r.bottom = y }
                                Handle.BR -> { r.right = x; r.bottom = y }
                                null -> {}
                            }
                            normalizeRect(r)
                            enforceMinSize(r)
                            sel.dstRect.set(r)
                            invalidate()
                            return true
                        }
                        if (dragging) {
                            val dx = x - dragLastX
                            val dy = y - dragLastY
                            sel.dstRect.offset(dx, dy)
                            dragLastX = x; dragLastY = y
                            invalidate()
                            return true
                        }
                    }

                    // Making marquee
                    if (selectMode == SelectMode.LASSO) {
                        path.quadTo(lastX, lastY, midX, midY)
                    } else {
                        val r = RectF(downX, downY, x, y)
                        rectPath(r, path)
                    }
                    lastX = x; lastY = y
                    invalidate()
                    return true
                }

                // Stylus / highlighter / eraser live drawing
                path.quadTo(lastX, lastY, midX, midY)
                lastX = x; lastY = y

                when (tool) {
                    Tool.STYLUS -> inkCanvas?.drawPath(path, penPaint)
                    Tool.HIGHLIGHTER -> if (highlightMode == HighlightMode.FREEFORM) {
                        hlCanvas?.drawPath(path, hlPaint)
                    }
                    Tool.ERASER -> {
                        if (eraserMode == EraserMode.STROKE) {
                            erasePathLive(path)
                        } else {
                            stampEraseDot(x, y)
                        }
                    }
                    Tool.SELECT -> {} // handled above
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == Tool.SELECT) {
                    if (activeSel != null) {
                        // Finish drag/resize gesture
                        dragging = false
                        resizingHandle = null
                        invalidate()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    // Finalize marquee -> create floating selection
                    val created = createSelectionFromCurrentPath()
                    if (!created) {
                        path.reset()
                        invalidate()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                // Commit draw/erase/highlight to history
                when (tool) {
                    Tool.STYLUS -> push(Op.DrawPath(Layer.INK, Path(path), toParams(penPaint)))
                    Tool.HIGHLIGHTER -> {
                        if (highlightMode == HighlightMode.FREEFORM) {
                            push(Op.DrawPath(Layer.HL, Path(path), toParams(hlPaint)))
                        } else {
                            // TODO (planned): live any-angle preview; for now, horizontal commit stays.
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
                    Tool.SELECT -> {}
                }
                path.reset(); areaDots.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Selection helpers ---
    private fun normalizeRect(r: RectF) {
        val l = min(r.left, r.right)
        val t = min(r.top, r.bottom)
        val rt = max(r.left, r.right)
        val b = max(r.top, r.bottom)
        r.set(l, t, rt, b)
    }

    private fun enforceMinSize(r: RectF) {
        if (r.width() < MIN_SEL_SIZE) r.right = r.left + MIN_SEL_SIZE
        if (r.height() < MIN_SEL_SIZE) r.bottom = r.top + MIN_SEL_SIZE
    }

    private fun hitTestHandle(x: Float, y: Float, r: RectF): Handle? {
        fun hit(cx: Float, cy: Float) =
            abs(x - cx) <= HANDLE_HIT && abs(y - cy) <= HANDLE_HIT
        return when {
            hit(r.left, r.top) -> Handle.TL
            hit(r.right, r.top) -> Handle.TR
            hit(r.left, r.bottom) -> Handle.BL
            hit(r.right, r.bottom) -> Handle.BR
            else -> null
        }
    }

    private fun rectPath(r: RectF, out: Path) {
        normalizeRect(r)
        out.reset()
        out.addRect(r, Path.Direction.CW)
    }

    private fun createSelectionFromCurrentPath(): Boolean {
        // Build the selection Path
        val selPath = Path(path)
        if (selectMode == SelectMode.LASSO) selPath.close() // required for clip

        val b = RectF()
        selPath.computeBounds(b, true)
        normalizeRect(b)
        if (b.width() < MIN_SEL_SIZE || b.height() < MIN_SEL_SIZE) return false

        // Extract pixels from each layer using clipPath
        val inkCut = extractBitmapFromPath(inkBitmap, selPath, b)
        val hlCut = extractBitmapFromPath(hlBitmap, selPath, b)

        // Nothing selected? bail
        if ((inkCut == null || inkCut.isEmpty()) && (hlCut == null || hlCut.isEmpty())) return false

        // Set floating selection
        activeSel = Selection(
            ink = inkCut,
            hl = hlCut,
            srcPath = Path(selPath), // copy
            srcBounds = RectF(b),
            dstRect = RectF(b) // start at same location
        )
        path.reset()
        invalidate()
        return true
    }

    private fun extractBitmapFromPath(src: Bitmap?, clipPath: Path, bounds: RectF): Bitmap? {
        if (src == null) return null
        val w = max(1, bounds.width().toInt())
        val h = max(1, bounds.height().toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-bounds.left, -bounds.top)
        c.clipPath(clipPath)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun Bitmap.isEmpty(): Boolean {
        // Cheap check: scan a few pixels; if all transparent, treat as empty
        val stepX = max(1, width / 8)
        val stepY = max(1, height / 8)
        for (yy in 0 until height step stepY) {
            for (xx in 0 until width step stepX) {
                if ((getPixel(xx, yy) ushr 24) != 0) return false
            }
        }
        return true
    }

    private fun commitSelection() {
        val sel = activeSel ?: return
        // Record as a single history op so Undo/Redo just works
        push(
            Op.CutPaste(
                ink = sel.ink,
                hl = sel.hl,
                srcPath = Path(sel.srcPath),
                srcBounds = RectF(sel.srcBounds),
                dstRect = RectF(sel.dstRect)
            )
        )
        // Apply by rebuilding (ensures consistency with history)
        rebuildFromHistory()
        activeSel = null
    }

    // ---------------- Utilities for other tools ----------------
    private fun erasePathLive(p: Path) {
        val cStroke = Paint(eraserStrokePaint).apply { strokeWidth = eraserSize }
        if (eraseHighlightsOnly) {
            hlCanvas?.drawPath(p, cStroke)
        } else {
            inkCanvas?.drawPath(p, cStroke); hlCanvas?.drawPath(p, cStroke)
        }
    }

    private fun stampEraseDot(x: Float, y: Float) {
        val r = eraserSize / 2f
        areaDots.add(PointF(x, y))
        val fill = eraserFillPaint
        if (eraseHighlightsOnly) {
            hlCanvas?.drawCircle(x, y, r, fill)
        } else {
            inkCanvas?.drawCircle(x, y, r, fill); hlCanvas?.drawCircle(x, y, r, fill)
        }
    }

    // ---------------- Public API ----------------
    fun setTool(t: Tool) {
        // If leaving select mode with a floating selection, commit it
        if (tool == Tool.SELECT && t != Tool.SELECT && activeSel != null) {
            commitSelection()
        }
        tool = t
        invalidate()
    }

    fun setSelectMode(mode: SelectMode) { selectMode = mode }

    fun setStylusBrush(b: StylusBrush) {
        stylusBrush = b
        // Reset special effects each time
        penPaint.pathEffect = null
        penPaint.maskFilter = null

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
            }
            StylusBrush.PEN -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.alpha = 255
            }
            StylusBrush.PENCIL -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 200
            }
            StylusBrush.MARKER -> {
                penPaint.strokeCap = Paint.Cap.SQUARE
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.alpha = 230
            }
        }
        penPaint.strokeWidth = stylusSize
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

    fun setHighlightMode(mode: HighlightMode) { highlightMode = mode }

    fun setEraserMode(mode: EraserMode) { eraserMode = mode }

    fun setEraserSize(px: Float) {
        eraserSize = px.coerceAtLeast(1f)
        eraserStrokePaint.strokeWidth = eraserSize
        invalidate()
    }

    fun setEraseHighlightsOnly(only: Boolean) { eraseHighlightsOnly = only }

    fun clearAll() {
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        hlBitmap?.eraseColor(Color.TRANSPARENT)
        push(Op.Clear)
        invalidate()
    }
}

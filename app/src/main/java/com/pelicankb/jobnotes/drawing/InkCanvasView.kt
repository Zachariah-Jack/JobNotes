package com.pelicankb.jobnotes.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.random.Random
import android.view.GestureDetector


/**
 * InkCanvasView
 *
 * Paste behavior:
 *  - Call [armPastePlacement]. While armed, the *next stylus DOWN* on the canvas:
 *      1) Pastes the clipboard centered at the tap.
 *      2) Immediately enters a TRANSLATE transform so the user can drag to place in one gesture.
 *  - Two-finger gestures (pinch/pan) cancel a pending paste (so you won't paste by accident).
 *  - [cancelPastePlacement] is provided for the Activity to disarm manually if desired.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ===== Public API =====

    fun setBrush(type: BrushType) { baseBrush = type }
    fun setStrokeWidthDp(sizeDp: Float) { baseWidthPx = dpToPx(sizeDp) }
    fun setColor(color: Int) { baseColor = color }
    /** Eraser option (applies to both eraser modes). Can be toggled mid‑stroke. */
    fun setEraserHighlighterOnly(hlOnly: Boolean) { eraserHLOnly = hlOnly }

    /** Turn off selection tool; keep or drop the current selection visuals. */
    fun setSelectionToolNone(keepSelection: Boolean = true) {
        selectionTool = SelTool.NONE
        selectionSticky = false
        selectionInteractive = false
        if (!keepSelection) {
            clearSelection()
        } else {
            clearMarquee()
            invalidate()
        }
    }

    fun undo() {
        if (strokes.isEmpty()) return
        val last = strokes.removeAt(strokes.lastIndex)
        when (last.type) {
            BrushType.ERASER_STROKE -> {
                last.erased?.let { removed ->
                    removed.sortedBy { it.index }.forEach { e ->
                        if (e.index <= strokes.size) strokes.add(e.index, e.stroke)
                        else strokes.add(e.stroke)
                    }
                }
                redoStack.add(last)
            }
            else -> redoStack.add(last)
        }
        rebuildCommitted()
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val op = redoStack.removeAt(redoStack.lastIndex)
        when (op.type) {
            BrushType.ERASER_STROKE -> {
                op.erased?.forEach { e -> strokes.remove(e.stroke) }
                strokes.add(op)
            }
            else -> strokes.add(op)
        }
        rebuildCommitted()
        invalidate()
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        current = null
        committedInk?.eraseColor(Color.TRANSPARENT)
        committedHL?.eraseColor(Color.TRANSPARENT)
        scratchInk?.eraseColor(Color.TRANSPARENT)
        scratchHL?.eraseColor(Color.TRANSPARENT)
        clearSelection()
        invalidate()
    }

    // ---- Selection API exposed to Activity ----
    enum class SelectionPolicy { STROKE_WISE, REGION_INSIDE }

    fun setSelectionPolicy(policy: SelectionPolicy) {
        selectionPolicy = policy
        lastAppliedSelectionPath?.let { applySelection(Path(it)) }
        invalidate()
    }
    fun getSelectionPolicy(): SelectionPolicy = selectionPolicy

    fun enterSelectionLasso() {
        selectionTool = SelTool.LASSO
        selectionSticky = true
        selectionInteractive = true
        cancelActiveOps()
        invalidate()
    }
    fun enterSelectionRect() {
        selectionTool = SelTool.RECT
        selectionSticky = true
        selectionInteractive = true
        cancelActiveOps()
        invalidate()
    }

    fun hasSelection(): Boolean = selectedStrokes.isNotEmpty()
    fun hasClipboard(): Boolean = clipboard.isNotEmpty()

    fun clearSelection() {
        cancelTransform()
        // make absolutely sure nothing stays hidden
        for (s in selectedStrokes) s.hidden = false
        selectedStrokes.clear()
        selectedBounds = null
        selectionInteractive = false
        overlayActive = false
        clearMarquee()
        invalidate()
    }

    fun deleteSelection() {
        if (selectedStrokes.isEmpty()) return
        cancelTransform()

        val op = StrokeOp(
            type = BrushType.ERASER_STROKE,
            color = 0,
            baseWidth = 1f,
            erased = mutableListOf()
        )
        for (i in strokes.size - 1 downTo 0) {
            val s = strokes[i]
            if (selectedStrokes.contains(s)) {
                op.erased!!.add(ErasedEntry(i, s))
                strokes.removeAt(i)
            }
        }
        strokes.add(op)

        selectedStrokes.clear()
        selectedBounds = null
        selectionInteractive = false
        overlayActive = false
        rebuildCommitted()
        invalidate()
    }

    fun copySelection(): Boolean {
        if (selectedStrokes.isEmpty()) return false
        clipboard.clear()
        for (s in strokes) if (selectedStrokes.contains(s)) clipboard.add(deepCopy(s))
        selectedBounds?.let { clipboardBounds = RectF(it) }
        return clipboard.isNotEmpty()
    }

    fun cutSelection(): Boolean {
        val ok = copySelection()
        if (ok) deleteSelection()
        return ok
    }

    /** Old behavior (kept for compatibility): immediate paste with a small offset. */
    fun pasteClipboard(): Boolean {
        if (clipboard.isEmpty()) return false
        cancelTransform()
        selectedStrokes.clear()

        val dx = dpToPx(24f)
        val dy = dpToPx(16f)
        val copies = clipboard.map { copy ->
            val c = deepCopy(copy)
            translateStroke(c, dx, dy)
            c
        }
        strokes.addAll(copies)               // appended at top -> above old erasers
        selectedStrokes.addAll(copies)
        clipboardBounds?.let { selectedBounds = RectF(it).apply { offset(dx, dy) } }
        selectionInteractive = true

        rebuildCommitted()
        invalidate()
        return true
    }

    fun armPastePlacement(): Boolean {
        if (clipboard.isEmpty()) return false
        pasteArmed = true
        // Place ghost in view center until stylus hover moves it
        pastePreviewCx = width * 0.5f / max(1f, scaleFactor) - translationX / max(1f, scaleFactor)
        pastePreviewCy = height * 0.5f / max(1f, scaleFactor) - translationY / max(1f, scaleFactor)
        pastePreviewVisible = true
        invalidate()
        return true
    }


    /** Optional: let the Activity cancel paste mode explicitly. */
    fun cancelPastePlacement() {
        if (pasteArmed) {
            pasteArmed = false
            invalidate()
        }
    }

    // ---- Save/restore (for rotation survival) ----
    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(1) // version
        out.writeInt(strokes.size)
        for (op in strokes) {
            out.writeInt(op.type.ordinal)
            out.writeInt(op.color)
            out.writeFloat(op.baseWidth)
            out.writeBoolean(op.eraseHLOnly)
            out.writeInt(op.points.size)
            for (p in op.points) {
                out.writeFloat(p.x)
                out.writeFloat(p.y)
            }
        }
        out.flush()
        return baos.toByteArray()
    }

    fun deserialize(data: ByteArray) {
        try {
            val `in` = DataInputStream(ByteArrayInputStream(data))
            val ver = `in`.readInt()
            if (ver != 1) return
            val n = `in`.readInt()
            strokes.clear()
            repeat(n) {
                val typeOrdinal = `in`.readInt()
                val all = enumValues<BrushType>()
                val type = if (typeOrdinal in all.indices) all[typeOrdinal] else BrushType.PEN

                val color = `in`.readInt()
                val width = `in`.readFloat()
                val hlOnly = `in`.readBoolean()
                val ptsN = `in`.readInt()
                val pts = MutableList(ptsN) { Point(`in`.readFloat(), `in`.readFloat()) }
                val op = StrokeOp(type, color, width, pts.toMutableList())
                op.eraseHLOnly = hlOnly
                strokes.add(op)
            }
            redoStack.clear()
            current = null
            cancelTransform()
            selectedStrokes.clear()
            selectedBounds = null
            selectionInteractive = false
            overlayActive = false
            clearMarquee()
            rebuildCommitted()
            invalidate()
        } catch (_: Throwable) { /* ignore bad payloads */ }
    }

    // ===== Stroke model =====

    data class Point(val x: Float, val y: Float)
    private data class ErasedEntry(val index: Int, val stroke: StrokeOp)

    /** NOTE: regular class (identity equality). */
    private class StrokeOp(
        val type: BrushType,
        var color: Int,
        var baseWidth: Float,
        val points: MutableList<Point> = mutableListOf(),

        // caches
        var stampPhase: Float = 0f,
        var lastDir: Float? = null,

        // geometry snapshots for union-based tools
        var calligPath: Path? = null,
        var fountainPath: Path? = null,

        // eraser-specific
        var eraseHLOnly: Boolean = false,
        var erased: MutableList<ErasedEntry>? = null,

        // transform helper
        var hidden: Boolean = false
    )

    private val strokes = mutableListOf<StrokeOp>()
    private val redoStack = mutableListOf<StrokeOp>()
    private var current: StrokeOp? = null

    // ===== “Next stroke” configuration =====
    private var baseBrush: BrushType = BrushType.PEN
    private var baseColor: Int = Color.BLACK
    private var baseWidthPx: Float = dpToPx(4f)
    private var eraserHLOnly: Boolean = false

    // ===== Layers =====
    private var committedInk: Bitmap? = null
    private var canvasInk: Canvas? = null
    private var committedHL: Bitmap? = null
    private var canvasHL: Canvas? = null
    private var scratchInk: Bitmap? = null
    private var scratchCanvasInk: Canvas? = null
    private var scratchHL: Bitmap? = null
    private var scratchCanvasHL: Canvas? = null

    // ===== Transform (pan/zoom) =====
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var fingerPanning = false

    private var scaleFactor = 1f
    private var translationX = 0f
    private var translationY = 0f
    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f


    // Hand/pan tool: when ON, stylus drag pans instead of drawing
    private var panMode = false
    fun setPanMode(enable: Boolean) { panMode = enable }

    // Policy: if true, only stylus/eraser tools can draw; if false, fingers can draw too
    private var stylusOnly = false
    fun setStylusOnly(enable: Boolean) { stylusOnly = enable }

    // Track when pinch is in progress to avoid starting strokes
    private var scalingInProgress = false



    // Input state
    private var drawing = false              // ink/eraser stroke
    private var selectingGesture = false     // currently dragging marquee
    private var transforming = false         // moving/resizing existing selection
    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    // Calligraphy nib
    private val nibAngleRad = Math.toRadians(45.0).toFloat()

    // ===== Selection state =====
    private enum class SelTool { NONE, LASSO, RECT }
    private var selectionTool: SelTool = SelTool.NONE
    private var selectionSticky: Boolean = true

    /** Only interactive when selection tool is armed (or we intentionally enable it after paste). */
    private var selectionInteractive: Boolean = false

    private var selectionPolicy: SelectionPolicy = SelectionPolicy.STROKE_WISE

    // marquee during drag
    private var marqueePath: Path? = null
    private var marqueeStartX = 0f
    private var marqueeStartY = 0f
    private val marqueeRect = RectF()
    private var lastAppliedSelectionPath: Path? = null

    // lasso performance buffers
    private val lassoPts = mutableListOf<Point>()
    private fun lassoTol(): Float = max(1.5f, 2.5f / scaleFactor)

    // selection result
    private val selectedStrokes = LinkedHashSet<StrokeOp>()
    private var selectedBounds: RectF? = null

    // selection visuals
    private val marqueeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = dpToPx(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val marqueeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1F2196F3  // translucent blue
    }
    private val selectionOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2196F3.toInt()
        strokeWidth = dpToPx(2f)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val selectionHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x332196F3
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2196F3.toInt()
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dpToPx(1.5f)
    }

    private enum class Handle { NONE, INSIDE, N, S, E, W, NE, NW, SE, SW }
    private enum class TransformKind { TRANSLATE, SCALE_X, SCALE_Y, SCALE_UNIFORM }

    private var transformKind: TransformKind? = null
    private var transformAnchorX = 0f
    private var transformAnchorY = 0f
    private var downX = 0f
    private var downY = 0f
    private var startBounds = RectF()
    private val savedPoints: MutableMap<StrokeOp, List<Point>> = HashMap()
    private val savedBaseWidth: MutableMap<StrokeOp, Float> = HashMap()

    private val handleSizeDp = 14f
    private val handleTouchPadDp = 18f

    // --- Paste "ghost" preview ---
    private var pasteArmed = false         // (you already have this)
    private var pastePreviewVisible = false
    private var pastePreviewCx = 0f
    private var pastePreviewCy = 0f
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF000000.toInt()
        strokeWidth = dpToPx(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }



    private val clipboard = mutableListOf<StrokeOp>()
    private var clipboardBounds: RectF? = null

    // Transform‑time overlay
    private var overlayActive = false

    // ===== View lifecycle =====

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (pasteArmed && isStylus(event, event.actionIndex)) {
            if (event.action == MotionEvent.ACTION_HOVER_MOVE) {
                val (cx, cy) = toContent(event.x, event.y)
                pastePreviewCx = cx
                pastePreviewCy = cy
                pastePreviewVisible = true
                invalidate()
                return true
            }
        }
        return super.onHoverEvent(event)
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        fun newBitmap() = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        committedInk = newBitmap().also { canvasInk = Canvas(it) }
        committedHL  = newBitmap().also { canvasHL = Canvas(it) }
        scratchInk   = newBitmap().also { scratchCanvasInk = Canvas(it) }
        scratchHL    = newBitmap().also { scratchCanvasHL  = Canvas(it) }

        rebuildCommitted()
        scratchInk?.eraseColor(Color.TRANSPARENT)
        scratchHL?.eraseColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withSave {
            translate(translationX, translationY)
            scale(scaleFactor, scaleFactor)

            // base layers
            committedHL?.let { drawBitmap(it, 0f, 0f, null) }
            scratchHL?.let { drawBitmap(it, 0f, 0f, null) }
            committedInk?.let { drawBitmap(it, 0f, 0f, null) }
            scratchInk?.let { drawBitmap(it, 0f, 0f, null) }

            // --- ghost paste preview (before overlays) ---
            if (pasteArmed && pastePreviewVisible && clipboard.isNotEmpty()) {
                drawClipboardGhost(this, pastePreviewCx, pastePreviewCy)
            }


            // overlay (moving selection on top of everything)
            if (overlayActive && selectedStrokes.isNotEmpty()) {
                drawSelectionOverlay(this)
            }

            // marquee
            marqueePath?.let { path ->
                drawPath(path, marqueeFill)
                drawPath(path, marqueeOutline)
            }

            // selection overlay & handles (visuals)
            if (selectedStrokes.isNotEmpty()) {
                drawSelectionHighlights(this)
                selectedBounds?.let { r ->
                    drawRect(r, selectionOutline)
                    if (selectionInteractive) {
                        drawHandles(this, r)
                    }
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }


    // ===== Input =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always feed detectors
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = event.actionIndex

                // Single-finger (not stylus): pan or move selection; never draw
                if (!isStylus(event, idx) && event.pointerCount == 1 && !scalingInProgress) {
                    val (cx, cy) = toContent(event.getX(idx), event.getY(idx))

                    // Finger can transform selection: handles first, then inside box
                    if (selectedStrokes.isNotEmpty() && selectionInteractive) {
                        val h = detectHandle(cx, cy)
                        if (h != Handle.NONE) {
                            beginTransform(h, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        }
                        val inside = selectedBounds?.contains(cx, cy) == true
                        if (inside) {
                            beginTransform(Handle.INSIDE, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        }
                    }

                    // Otherwise, start finger panning
                    activePointerId = event.getPointerId(idx)
                    lastPanFocusX = event.getX(idx)
                    lastPanFocusY = event.getY(idx)
                    fingerPanning = true
                    drawing = false
                    selectingGesture = false
                    transforming = false
                    return true
                }

                // Stylus or other case
                val canDrawNow = acceptsPointer(event, idx) &&
                        event.pointerCount == 1 &&
                        !scalingInProgress

                if (canDrawNow) {
                    // Stylus hand/pan tool
                    if (panMode) {
                        activePointerId = event.getPointerId(idx)
                        lastPanFocusX = event.getX(idx)
                        lastPanFocusY = event.getY(idx)
                        drawing = false
                        selectingGesture = false
                        transforming = false
                        return true
                    }

                    val (cx, cy) = toContent(event.getX(idx), event.getY(idx))

                    // Tap to place paste; then translate immediately
                    if (pasteArmed && clipboard.isNotEmpty()) {
                        performPasteAt(cx, cy)
                        pasteArmed = false
                        beginTransform(Handle.INSIDE, cx, cy)
                        transforming = true
                        activePointerId = event.getPointerId(idx)
                        return true
                    }

                    // Transform selection (handles first, then inside; otherwise maybe clear)
                    if (selectedStrokes.isNotEmpty() && selectionInteractive) {
                        val h = detectHandle(cx, cy)
                        if (h != Handle.NONE) {
                            beginTransform(h, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        }
                        val inside = selectedBounds?.contains(cx, cy) == true
                        if (inside) {
                            beginTransform(Handle.INSIDE, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        } else if (selectionTool != SelTool.NONE && selectionSticky) {
                            clearSelection()
                        }
                    }

                    // Either start selection marquee or a stroke
                    if (selectionTool != SelTool.NONE) {
                        startSelection(event, idx)
                    } else {
                        startStroke(event, idx)
                    }
                } else {
                    // Not starting: finger while stylusOnly, or multi-touch
                    drawing = false
                    selectingGesture = false
                    transforming = false
                    activePointerId = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Any second pointer cancels a pending paste
                if (event.pointerCount >= 2 && pasteArmed) pasteArmed = false

                // Cancel one-pointer modes
                drawing = false
                selectingGesture = false
                transforming = false
                activePointerId = -1
                fingerPanning = false

                // Two-finger pan baseline
                lastPanFocusX = averageX(event)
                lastPanFocusY = averageY(event)
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    // 1‑finger finger pan
                    fingerPanning && activePointerId != -1 -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val x = event.getX(i)
                            val y = event.getY(i)
                            translationX += (x - lastPanFocusX)
                            translationY += (y - lastPanFocusY)
                            lastPanFocusX = x
                            lastPanFocusY = y
                            // Vertical-only pan when zoom == 1×
                            if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
                            invalidate()
                        }
                    }

                    // Stylus hand‑pan tool
                    panMode && activePointerId != -1 -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val x = event.getX(i)
                            val y = event.getY(i)
                            translationX += (x - lastPanFocusX)
                            translationY += (y - lastPanFocusY)
                            lastPanFocusX = x
                            lastPanFocusY = y
                            // Lock horizontal pan at 1×
                            if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
                            invalidate()
                        }
                    }

                    // Drawing
                    drawing -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))
                            extendStroke(cx, cy)
                            invalidate()
                        }
                    }

                    // Selection marquee
                    selectingGesture -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))
                            extendSelection(cx, cy)
                            invalidate()
                        }
                    }

                    // Transform selection
                    transforming -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))
                            updateTransform(cx, cy)
                            invalidate()
                        }
                    }

                    // Two‑finger pan (any tool)
                    event.pointerCount >= 2 -> {
                        val fx = averageX(event)
                        val fy = averageY(event)
                        translationX += fx - lastPanFocusX
                        translationY += fy - lastPanFocusY
                        lastPanFocusX = fx
                        lastPanFocusY = fy
                        // Lock horizontal pan at 1×
                        if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    when {
                        selectingGesture -> finishSelection()
                        transforming -> finishTransform()
                        else -> finishStroke()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerPanning = false
                if (panMode) activePointerId = -1
                when {
                    selectingGesture -> finishSelection()
                    transforming -> finishTransform()
                    else -> finishStroke()
                }
                performClick()
            }
        }
        return true
    }




    private fun zoomTo(targetScale: Float, focusViewX: Float, focusViewY: Float) {
        val (cx, cy) = toContent(focusViewX, focusViewY)
        val clamped = targetScale.coerceIn(0.5f, 4f)
        scaleFactor = clamped
        // Keep the tapped content point under the same screen coordinate
        translationX = focusViewX - clamped * cx
        translationY = focusViewY - clamped * cy
        // At 1×, lock horizontal scroll to show page edges consistently
        if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
        invalidate()
    }

    private fun startStroke(ev: MotionEvent, idx: Int) {
        redoStack.clear()

        val (x, y) = toContent(ev.getX(idx), ev.getY(idx))
        current = StrokeOp(
            type = baseBrush,
            color = baseColor,
            baseWidth = baseWidthPx
        ).also {
            it.points.add(Point(x, y))
            it.lastDir = null
            it.calligPath = null
            it.fountainPath = null
            it.eraseHLOnly = eraserHLOnly
            if (baseBrush == BrushType.ERASER_STROKE) it.erased = mutableListOf()
        }

        when (baseBrush) {
            BrushType.HIGHLIGHTER_FREEFORM,
            BrushType.HIGHLIGHTER_STRAIGHT -> scratchHL?.eraseColor(Color.TRANSPARENT)
            BrushType.ERASER_AREA -> {}
            else -> scratchInk?.eraseColor(Color.TRANSPARENT)
        }

        lastX = x
        lastY = y
        drawing = true
        selectingGesture = false
        transforming = false
        activePointerId = ev.getPointerId(idx)
    }

    // ---- selection input ----
    private fun startSelection(ev: MotionEvent, idx: Int) {
        redoStack.clear()

        val (x, y) = toContent(ev.getX(idx), ev.getY(idx))
        marqueeStartX = x
        marqueeStartY = y

        when (selectionTool) {
            SelTool.LASSO -> {
                lassoPts.clear()
                lassoPts.add(Point(x, y))
                marqueePath = Path().apply { moveTo(x, y) }
            }
            SelTool.RECT -> {
                marqueeRect.set(x, y, x, y)
                marqueePath = Path().apply { addRect(marqueeRect, Path.Direction.CW) }
            }
            SelTool.NONE -> {}
        }

        drawing = false
        selectingGesture = true
        transforming = false
        activePointerId = ev.getPointerId(idx)
    }

    private fun extendSelection(x: Float, y: Float) {
        when (selectionTool) {
            SelTool.LASSO -> {
                val last = lassoPts.last()
                val d2 = (x - last.x) * (x - last.x) + (y - last.y) * (y - last.y)
                if (d2 >= lassoTol() * lassoTol()) {
                    lassoPts.add(Point(x, y))
                    marqueePath?.lineTo(x, y)
                }
            }
            SelTool.RECT -> {
                marqueeRect.set(
                    min(marqueeStartX, x),
                    min(marqueeStartY, y),
                    max(marqueeStartX, x),
                    max(marqueeStartY, y)
                )
                marqueePath?.rewind()
                marqueePath?.addRect(marqueeRect, Path.Direction.CW)
            }
            SelTool.NONE -> {}
        }
    }

    private fun finishSelection() {
        if (!selectingGesture) return
        selectingGesture = false
        activePointerId = -1

        val selPath = when (selectionTool) {
            SelTool.LASSO -> Path(marqueePath ?: Path()).apply { close() }
            SelTool.RECT  -> Path().apply { addRect(marqueeRect, Path.Direction.CW) }
            SelTool.NONE  -> null
        } ?: return

        lastAppliedSelectionPath = Path(selPath)
        applySelection(selPath)

        clearMarquee()
        invalidate()
    }

    private fun applySelection(selPath: Path) {
        val clip = Region(0, 0, width, height)
        val selRegion = Region()
        selRegion.setPath(selPath, clip)
        val selBox = android.graphics.Rect()
        selRegion.getBounds(selBox)

        selectedStrokes.clear()
        for (s in strokes) {
            if (s.hidden) continue
            if (s.type == BrushType.ERASER_AREA || s.type == BrushType.ERASER_STROKE) continue

            // quick reject by stroke bounds vs selection bounding box
            val b = strokeBounds(s) ?: continue
            if (!RectF(selBox).intersect(b)) continue

            when (selectionPolicy) {
                SelectionPolicy.STROKE_WISE   -> if (strokeIntersectsRegion(s, selRegion)) selectedStrokes.add(s)
                SelectionPolicy.REGION_INSIDE -> if (strokeInsideRegion(s, selRegion)) selectedStrokes.add(s)
            }
        }
        selectedBounds = computeSelectionBounds()
        selectionInteractive = true  // remains interactive while selection tool is armed (or after paste)
    }

    // ===== Transform (drag/scale) =====

    private fun beginTransform(handle: Handle, cx: Float, cy: Float) {
        val r = selectedBounds ?: return
        downX = cx
        downY = cy
        startBounds.set(r)
        savedPoints.clear()
        savedBaseWidth.clear()
        for (s in selectedStrokes) {
            savedPoints[s] = s.points.map { Point(it.x, it.y) }
            savedBaseWidth[s] = s.baseWidth
            s.hidden = true           // hide from base while dragging
        }

        transformKind = when (handle) {
            Handle.INSIDE -> { transformAnchorX = 0f; transformAnchorY = 0f; TransformKind.TRANSLATE }
            Handle.N      -> { transformAnchorX = r.centerX(); transformAnchorY = r.bottom; TransformKind.SCALE_Y }
            Handle.S      -> { transformAnchorX = r.centerX(); transformAnchorY = r.top;    TransformKind.SCALE_Y }
            Handle.W      -> { transformAnchorX = r.right;  transformAnchorY = r.centerY(); TransformKind.SCALE_X }
            Handle.E      -> { transformAnchorX = r.left;   transformAnchorY = r.centerY(); TransformKind.SCALE_X }
            Handle.NW     -> { transformAnchorX = r.right;  transformAnchorY = r.bottom;    TransformKind.SCALE_UNIFORM }
            Handle.NE     -> { transformAnchorX = r.left;   transformAnchorY = r.bottom;    TransformKind.SCALE_UNIFORM }
            Handle.SW     -> { transformAnchorX = r.right;  transformAnchorY = r.top;       TransformKind.SCALE_UNIFORM }
            Handle.SE     -> { transformAnchorX = r.left;   transformAnchorY = r.top;       TransformKind.SCALE_UNIFORM }
            else -> null
        }

        overlayActive = true

        // Build base ONCE (with all erasers applied) for correct background during drag
        rebuildCommitted()
        invalidate()
    }

    private fun updateTransform(cx: Float, cy: Float) {
        val kind = transformKind ?: return
        val r = startBounds

        val minScale = 0.1f
        val sx: Float
        val sy: Float

        when (kind) {
            TransformKind.TRANSLATE -> {
                val dx = cx - downX
                val dy = cy - downY
                applyTranslate(dx, dy)
                selectedBounds = RectF(r).apply { offset(dx, dy) }
                return
            }
            TransformKind.SCALE_X -> {
                val startW = max(1e-3f, r.width())
                val newW = abs(cx - transformAnchorX)
                sx = max(minScale, newW / startW)
                sy = 1f
            }
            TransformKind.SCALE_Y -> {
                val startH = max(1e-3f, r.height())
                val newH = abs(cy - transformAnchorY)
                sx = 1f
                sy = max(minScale, newH / startH)
            }
            TransformKind.SCALE_UNIFORM -> {
                val startW = max(1e-3f, r.width())
                val startH = max(1e-3f, r.height())
                val nx = abs(cx - transformAnchorX) / startW
                val ny = abs(cy - transformAnchorY) / startH
                val s = max(minScale, max(nx, ny)) // proportional
                sx = s; sy = s
            }
        }

        applyScale(sx, sy, transformAnchorX, transformAnchorY)

        val m = Matrix().apply {
            reset()
            postTranslate(-transformAnchorX, -transformAnchorY)
            postScale(sx, sy)
            postTranslate(transformAnchorX, transformAnchorY)
        }
        selectedBounds = RectF().apply { set(r); m.mapRect(this) }
    }

    private fun finishTransform() {
        transforming = false
        transformKind = null
        savedPoints.clear()
        savedBaseWidth.clear()

        // bring moved selection to the top so past erasers don’t punch through
        moveSelectionToTop()

        // unhide strokes and drop overlay
        for (s in selectedStrokes) s.hidden = false
        overlayActive = false
        // Clamp selection within the visible canvas bounds
        selectedBounds?.let { r ->
            var dx = 0f; var dy = 0f
            if (r.left < 0f) dx = -r.left
            if (r.right > width) dx = min(dx, width - r.right)
            if (r.top < 0f) dy = -r.top
            if (r.bottom > height) dy = min(dy, height - r.bottom)
            if (dx != 0f || dy != 0f) {
                // Offset current points directly (not via savedPoints)
                for (s in selectedStrokes) {
                    for (i in s.points.indices) {
                        val p = s.points[i]
                        s.points[i] = Point(p.x + dx, p.y + dy)
                    }
                    s.calligPath = null
                    s.fountainPath = null
                }
                selectedBounds?.offset(dx, dy)
            }
        }


        rebuildCommitted()
        invalidate()
    }

    private fun cancelTransform() {
        if (!transforming) return
        for ((s, pts) in savedPoints) {
            s.points.clear()
            s.points.addAll(pts)
            s.calligPath = null
            s.fountainPath = null
            savedBaseWidth[s]?.let { s.baseWidth = it }
            s.hidden = false
        }
        transforming = false
        transformKind = null
        savedPoints.clear()
        savedBaseWidth.clear()
        overlayActive = false
        rebuildCommitted()
        invalidate()
    }

    private fun applyTranslate(dx: Float, dy: Float) {
        for ((s, pts0) in savedPoints) {
            s.points.clear()
            pts0.forEach { s.points.add(Point(it.x + dx, it.y + dy)) }
            s.calligPath = null
            s.fountainPath = null
        }
        // widths unchanged on pure translate
    }

    private fun applyScale(sx: Float, sy: Float, ax: Float, ay: Float) {
        val avgScale = ((abs(sx) + abs(sy)) * 0.5f).coerceAtLeast(0.1f)
        for ((s, pts0) in savedPoints) {
            s.points.clear()
            for (p in pts0) {
                val nx = ax + (p.x - ax) * sx
                val ny = ay + (p.y - ay) * sy
                s.points.add(Point(nx, ny))
            }
            // Non‑cumulative width scaling from snapshot
            savedBaseWidth[s]?.let { orig -> s.baseWidth = (orig * avgScale).coerceAtLeast(0.2f) }
            s.calligPath = null
            s.fountainPath = null
        }
    }

    // ===== Paint helpers =====

    private fun newStrokePaint(color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            xfermode = null
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            xfermode = null
        }

    private val clearPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // ===== Simple segment renderers =====

    private fun drawPenSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp
    ) {
        val p = newStrokePaint(op.color, op.baseWidth)
        canvas.drawLine(x0, y0, x1, y1, p)
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = null
    }

    private fun drawMarkerSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, @Suppress("UNUSED_PARAMETER") dist: Float
    ) {
        markerPaint.color = op.color
        markerPaint.strokeWidth = op.baseWidth * 1.30f
        markerPaint.maskFilter = BlurMaskFilter(op.baseWidth * 0.22f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawLine(x0, y0, x1, y1, markerPaint)
    }

    // ===== PENCIL (graphite stamp) =====

    private val pencilStampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = true
        xfermode = null
    }
    private var pencilStamp: Bitmap? = null

    private fun ensurePencilStamp(): Bitmap {
        var bmp = pencilStamp
        if (bmp != null && !bmp.isRecycled) return bmp
        val size = 64
        val rnd = FloatArray(size * size) { Random.nextFloat() }
        fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val win = 2 * r + 1
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                var acc = 0f
                for (x in -r..r) acc += src[y * w + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = acc / win
                    val x0 = (x - r).coerceIn(0, w - 1)
                    val x2 = (x + r + 1).coerceIn(0, w - 1)
                    acc += src[y * w + x2] - src[y * w + x0]
                }
            }
            for (x in 0 until w) {
                var acc = 0f
                for (y in -r..r) acc += tmp[(y.coerceIn(0, h - 1)) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = acc / win
                    val y0 = (y - r).coerceIn(0, h - 1)
                    val y2 = (y + r + 1).coerceIn(0, h - 1)
                    acc += tmp[y2 * w + x] - tmp[y0 * w + x]
                }
            }
            return out
        }
        val n1 = blur(rnd, size, size, 1)
        val n2 = blur(rnd, size, size, 2)
        val n3 = blur(rnd, size, size, 4)

        val out = ByteArray(size * size)
        val cx = (size - 1) * 0.5f
        val cy = (size - 1) * 0.5f
        val maxR = min(cx, cy)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                var n = 0.50f * n1[i] + 0.35f * n2[i] + 0.15f * n3[i]
                n = (n * 1.15f - 0.20f).coerceIn(0f, 1f)
                val dx = (x - cx) / maxR
                val dy = (y - cy) / maxR
                val r2 = dx * dx + dy * dy
                val fall = if (r2 >= 1f) 0f else exp(-3.2f * r2).toFloat()
                val a = (fall * n).coerceIn(0f, 1f)
                out[i] = (a * 255f).toInt().toByte()
            }
        }
        bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        pencilStamp = bmp
        return bmp
    }

    private fun drawPencilSegment(
        canvas: Canvas,
        x0: Float, y0: Float, x1: Float, y1: Float,
        op: StrokeOp,
        dist: Float
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = dist
        if (len <= 1e-3f) return

        val inv = 1f / len
        val tx = dx * inv
        val ty = dy * inv
        val nx = -ty
        val ny =  tx

        val spacing = max(0.20f * op.baseWidth, 1.2f)
        val base = op.baseWidth.coerceAtLeast(0.75f)
        val longAxis = base * 1.15f
        val shortAxis = base * 0.80f
        val angleDeg = atan2(ty, tx) * 180f / Math.PI.toFloat()

        fun alphaForSpeed(s: Float): Int {
            val baseA = 120f / (1f + 0.06f * s) + 20f
            val a = baseA * 1.50f
            return a.roundToInt().coerceIn(30, 140)
        }

        val bmp = ensurePencilStamp()
        pencilStampPaint.color = op.color
        pencilStampPaint.shader = null

        var t = op.stampPhase
        while (t < len) {
            val px = x0 + dx * (t / len)
            val py = y0 + dy * (t / len)

            val jN = (Random.nextFloat() - 0.5f) * (base * 0.18f)
            val jT = (Random.nextFloat() - 0.5f) * (base * 0.12f)
            val jx = px + nx * jN + tx * jT
            val jy = py + ny * jN + ty * jT

            pencilStampPaint.alpha = alphaForSpeed(len)

            val scaleLong = longAxis * (0.95f + 0.10f * Random.nextFloat())
            val scaleShort = shortAxis * (0.90f + 0.10f * Random.nextFloat())
            val halfW = scaleLong * 0.5f
            val halfH = scaleShort * 0.5f

            val dst = RectF(jx - halfW, jy - halfH, jx + halfW, jy + halfH)
            val rot = angleDeg + (Random.nextFloat() - 0.5f) * 20f

            canvas.save()
            canvas.rotate(rot, jx, jy)
            canvas.drawBitmap(bmp, null, dst, pencilStampPaint)
            canvas.restore()

            t += spacing
        }
        op.stampPhase = t - len
    }

    // ===== Calligraphy & Fountain (union paths) =====

    private val workCalligPath = Path().apply { fillType = Path.FillType.WINDING }
    private val tmpPath = Path()
    private val segPath = Path()

    private fun buildCalligraphyPath(op: StrokeOp): Path {
        workCalligPath.rewind()
        workCalligPath.fillType = Path.FillType.WINDING
        val step = max(0.33f * op.baseWidth, 0.9f)
        val pts = resample(op.points, step)
        if (pts.size < 2) return workCalligPath
        fun rawDirAt(i: Int): Float = when (i) {
            0 -> atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
            pts.lastIndex -> atan2(pts[i].y - pts[i - 1].y, pts[i].x - pts[i - 1].x)
            else -> atan2(pts[i + 1].y - pts[i - 1].y, pts[i + 1].x - pts[i - 1].x)
        }
        val dirs = FloatArray(pts.size)
        var dirLPF = rawDirAt(0)
        dirs[0] = dirLPF
        for (i in 1 until pts.size) {
            val raw = rawDirAt(i)
            val d = shortestAngleDiff(raw, dirLPF)
            dirLPF += d * 0.35f
            dirs[i] = dirLPF
        }
        val nx = -sin(nibAngleRad)
        val ny =  cos(nibAngleRad)
        var hasAccum = false
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]
            val p1 = pts[i]
            val w0 = calligWidth(op, dirs[i - 1])
            val w1 = calligWidth(op, dirs[i])
            val hw0 = max(0.5f, 0.5f * w0)
            val hw1 = max(0.5f, 0.5f * w1)
            val l0x = p0.x + nx * hw0
            val l0y = p0.y + ny * hw0
            val r0x = p0.x - nx * hw0
            val r0y = p0.y - ny * hw0
            val l1x = p1.x + nx * hw1
            val l1y = p1.y + ny * hw1
            val r1x = p1.x - nx * hw1
            val r1y = p1.y - ny * hw1
            segPath.rewind()
            segPath.moveTo(l0x, l0y)
            segPath.lineTo(l1x, l1y)
            segPath.lineTo(r1x, r1y)
            segPath.lineTo(r0x, r0y)
            segPath.close()
            if (!hasAccum) {
                workCalligPath.set(segPath)
            } else {
                tmpPath.set(workCalligPath)
                workCalligPath.rewind()
                workCalligPath.fillType = Path.FillType.WINDING
                workCalligPath.op(tmpPath, segPath, Path.Op.UNION)
            }
            hasAccum = true
        }
        return workCalligPath
    }

    private fun buildFountainPath(op: StrokeOp): Path {
        workCalligPath.rewind()
        workCalligPath.fillType = Path.FillType.WINDING
        val step = max(0.33f * op.baseWidth, 0.9f)
        val pts = resample(op.points, step)
        if (pts.size < 2) return workCalligPath
        fun rawDirAt(i: Int): Float = when (i) {
            0 -> atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
            pts.lastIndex -> atan2(pts[i].y - pts[i - 1].y, pts[i].x - pts[i - 1].x)
            else -> atan2(pts[i + 1].y - pts[i - 1].y, pts[i + 1].x - pts[i - 1].x)
        }
        val dirs = FloatArray(pts.size)
        var dirLPF = rawDirAt(0)
        dirs[0] = dirLPF
        for (i in 1 until pts.size) {
            val raw = rawDirAt(i)
            val d = shortestAngleDiff(raw, dirLPF)
            dirLPF += d * 0.35f
            dirs[i] = dirLPF
        }
        val halfW = max(0.5f, 0.5f * op.baseWidth)
        var hasAccum = false
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]
            val p1 = pts[i]
            val n0x = -sin(dirs[i - 1]); val n0y = cos(dirs[i - 1])
            val n1x = -sin(dirs[i]);     val n1y = cos(dirs[i])
            val l0x = p0.x + n0x * halfW
            val l0y = p0.y + n0y * halfW
            val r0x = p0.x - n0x * halfW
            val r0y = p0.y - n0y * halfW
            val l1x = p1.x + n1x * halfW
            val l1y = p1.y + n1y * halfW
            val r1x = p1.x - n1x * halfW
            val r1y = p1.y - n1y * halfW
            segPath.rewind()
            segPath.moveTo(l0x, l0y)
            segPath.lineTo(l1x, l1y)
            segPath.lineTo(r1x, r1y)
            segPath.lineTo(r0x, r0y)
            segPath.close()
            if (!hasAccum) {
                workCalligPath.set(segPath)
            } else {
                tmpPath.set(workCalligPath)
                workCalligPath.rewind()
                workCalligPath.fillType = Path.FillType.WINDING
                workCalligPath.op(tmpPath, segPath, Path.Op.UNION)
            }
            hasAccum = true
        }
        return workCalligPath
    }

    /** Width varies with nib angle. */
    private fun calligWidth(op: StrokeOp, dir: Float): Float {
        val rel = abs(sin(dir - nibAngleRad))
        val minW = max(0.7f, op.baseWidth * 0.06f)
        val maxW = max(minW + 1f, op.baseWidth * 1.05f)
        return minW + (maxW - minW) * rel
    }
    private fun shortestAngleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d >  Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }
    private fun resample(src: List<Point>, step: Float): List<Point> {
        if (src.isEmpty()) return emptyList()
        val s = max(0.1f, step)
        val out = ArrayList<Point>(max(2, src.size))
        var ax = src[0].x
        var ay = src[0].y
        out.add(Point(ax, ay))
        var remaining = s
        for (i in 1 until src.size) {
            var bx = src[i].x
            var by = src[i].y
            var dx = bx - ax
            var dy = by - ay
            var seg = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (seg <= 1e-6f) continue
            while (seg >= remaining) {
                val t = remaining / seg
                ax += dx * t
                ay += dy * t
                out.add(Point(ax, ay))
                dx = bx - ax
                dy = by - ay
                seg = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                remaining = s
            }
            remaining -= seg
            ax = bx
            ay = by
        }
        if (out.size == 1 || out.last().x != src.last().x || out.last().y != src.last().y) {
            out.add(src.last())
        }
        return out
    }

    // ===== Rebuild committed layers =====

    private fun rebuildCommitted() {
        val ci = canvasInk ?: return
        val ch = canvasHL ?: return
        committedInk?.eraseColor(Color.TRANSPARENT)
        committedHL?.eraseColor(Color.TRANSPARENT)

        for (op in strokes) {
            if (op.hidden) continue
            when (op.type) {
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(op.color, op.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        ch.drawLine(a.x, a.y, b.x, b.y, p)
                    }
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(op.color, op.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                    ch.drawLine(pts.first().x, pts.first().y, pts.last().x, pts.last().y, p)
                }
                BrushType.CALLIGRAPHY -> {
                    val path = op.calligPath ?: Path(buildCalligraphyPath(op)).also { op.calligPath = it }
                    canvasInk?.drawPath(path, fillPaint(op.color))
                }
                BrushType.FOUNTAIN -> {
                    val path = op.fountainPath ?: Path(buildFountainPath(op)).also { op.fountainPath = it }
                    canvasInk?.drawPath(path, fillPaint(op.color))
                }
                BrushType.PEN -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(op.color, op.baseWidth)
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        ci.drawLine(a.x, a.y, b.x, b.y, p)
                    }
                }
                BrushType.MARKER -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    val p = markerPaint.apply {
                        color = op.color
                        strokeWidth = op.baseWidth * 1.30f
                        maskFilter = BlurMaskFilter(op.baseWidth * 0.22f, BlurMaskFilter.Blur.NORMAL)
                    }
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        ci.drawLine(a.x, a.y, b.x, b.y, p)
                    }
                }
                BrushType.PENCIL -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        val dist = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
                        drawPencilSegment(ci, a.x, a.y, b.x, b.y, op, dist)
                    }
                }
                BrushType.ERASER_AREA -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    val half = op.baseWidth * 0.5f
                    val clearInk = !op.eraseHLOnly
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val steps = max(1, (dist / max(1f, half)).toInt())
                        for (k in 0..steps) {
                            val t = k / steps.toFloat()
                            val px = a.x + dx * t
                            val py = a.y + dy * t
                            if (clearInk) ci.drawCircle(px, py, half, clearPaint)
                            ch.drawCircle(px, py, half, clearPaint)
                        }
                    }
                }
                BrushType.ERASER_STROKE -> { /* no-op on rebuild; already applied */ }
            }
        }
    }

    // --- ghost paste preview renderer ---
    private fun drawClipboardGhost(canvas: Canvas, cx: Float, cy: Float) {
        val src = clipboardBounds ?: return
        val dx = cx - src.centerX()
        val dy = cy - src.centerY()

        // 1) highlighters first (semi-transparent)
        for (s in clipboard) {
            when (s.type) {
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val pts = s.points; if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth).apply {
                        strokeCap = Paint.Cap.SQUARE
                        alpha = 120
                    }
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        canvas.drawLine(a.x + dx, a.y + dy, b.x + dx, b.y + dy, p)
                    }
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val pts = s.points; if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth).apply {
                        strokeCap = Paint.Cap.SQUARE
                        alpha = 120
                    }
                    canvas.drawLine(pts.first().x + dx, pts.first().y + dy, pts.last().x + dx, pts.last().y + dy, p)
                }
                else -> {}
            }
        }
        // 2) ink types on top (semi-transparent)
        for (s in clipboard) {
            when (s.type) {
                BrushType.CALLIGRAPHY -> {
                    val path = s.calligPath ?: buildCalligraphyPath(s).also { s.calligPath = it }
                    val ph = Path(path).apply { offset(dx, dy) }
                    val fill = fillPaint(s.color).apply { alpha = 140 }
                    canvas.drawPath(ph, fill)
                    canvas.drawPath(ph, ghostStroke)
                }
                BrushType.FOUNTAIN -> {
                    val path = s.fountainPath ?: buildFountainPath(s).also { s.fountainPath = it }
                    val ph = Path(path).apply { offset(dx, dy) }
                    val fill = fillPaint(s.color).apply { alpha = 140 }
                    canvas.drawPath(ph, fill)
                    canvas.drawPath(ph, ghostStroke)
                }
                BrushType.PEN, BrushType.MARKER, BrushType.PENCIL -> {
                    val pts = s.points; if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth).apply { alpha = 140 }
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        canvas.drawLine(a.x + dx, a.y + dy, b.x + dx, b.y + dy, p)
                    }
                }
                else -> { /* ignore erasers */ }
            }
        }

        // Outline bbox
        val bb = RectF(src).apply { offset(dx, dy) }
        canvas.drawRect(bb, ghostStroke)
    }


    // ===== Selection overlay drawing =====

    private fun drawSelectionOverlay(canvas: Canvas) {
        // 1) highlighters first (to mirror base layering)
        for (s in selectedStrokes) {
            when (s.type) {
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                    for (i in 1 until pts.size) canvas.drawLine(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y, p)
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                    canvas.drawLine(pts.first().x, pts.first().y, pts.last().x, pts.last().y, p)
                }
                else -> {}
            }
        }
        // 2) ink types on top
        for (s in selectedStrokes) {
            when (s.type) {
                BrushType.CALLIGRAPHY -> {
                    val path = s.calligPath ?: Path(buildCalligraphyPath(s)).also { s.calligPath = it }
                    canvas.drawPath(path, fillPaint(s.color))
                }
                BrushType.FOUNTAIN -> {
                    val path = s.fountainPath ?: Path(buildFountainPath(s)).also { s.fountainPath = it }
                    canvas.drawPath(path, fillPaint(s.color))
                }
                BrushType.PEN -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth)
                    for (i in 1 until pts.size) canvas.drawLine(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y, p)
                }
                BrushType.MARKER -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    markerPaint.color = s.color
                    markerPaint.strokeWidth = s.baseWidth * 1.30f
                    markerPaint.maskFilter = BlurMaskFilter(s.baseWidth * 0.22f, BlurMaskFilter.Blur.NORMAL)
                    for (i in 1 until pts.size) canvas.drawLine(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y, markerPaint)
                }
                BrushType.PENCIL -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        val dist = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

                        drawPencilSegment(canvas, a.x, a.y, b.x, b.y, s, dist)
                    }
                }
                BrushType.ERASER_AREA, BrushType.ERASER_STROKE -> { /* never selected */ }
                BrushType.HIGHLIGHTER_FREEFORM, BrushType.HIGHLIGHTER_STRAIGHT -> {} // already drawn
            }
        }
    }

    // ===== Selection visuals & helpers =====

    private fun drawSelectionHighlights(canvas: Canvas) {
        for (s in selectedStrokes) {
            val p = strokeAreaPath(s) ?: continue
            canvas.drawPath(p, selectionHighlight)
        }
    }

    private fun drawHandles(canvas: Canvas, r: RectF) {
        val sz = dpToPx(handleSizeDp)
        fun box(cx: Float, cy: Float) {
            val half = sz / 2f
            val rect = RectF(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRect(rect, handleFill)
            canvas.drawRect(rect, handleStroke)
        }
        box(r.left, r.top)
        box(r.right, r.top)
        box(r.left, r.bottom)
        box(r.right, r.bottom)
        box(r.centerX(), r.top)
        box(r.centerX(), r.bottom)
        box(r.left, r.centerY())
        box(r.right, r.centerY())
    }

    private fun detectHandle(x: Float, y: Float): Handle {
        val r = selectedBounds ?: return Handle.NONE
        val pad = dpToPx(handleTouchPadDp)

        fun near(px: Float, py: Float) = (abs(px - x) <= pad && abs(py - y) <= pad)
        if (near(r.left, r.top)) return Handle.NW
        if (near(r.right, r.top)) return Handle.NE
        if (near(r.left, r.bottom)) return Handle.SW
        if (near(r.right, r.bottom)) return Handle.SE

        if (abs(y - r.top) <= pad && x >= r.left - pad && x <= r.right + pad) return Handle.N
        if (abs(y - r.bottom) <= pad && x >= r.left - pad && x <= r.right + pad) return Handle.S
        if (abs(x - r.left) <= pad && y >= r.top - pad && y <= r.bottom + pad) return Handle.W
        if (abs(x - r.right) <= pad && y >= r.top - pad && y <= r.bottom + pad) return Handle.E

        return if (r.contains(x, y)) Handle.INSIDE else Handle.NONE
    }

    // ===== Hit testing / selection predicates =====

    private fun hitStroke(s: StrokeOp, x: Float, y: Float, tol: Float): Boolean {
        val path = s.calligPath ?: s.fountainPath
        if (path != null) {
            val r = RectF()
            path.computeBounds(r, true)
            r.inset(-tol, -tol)
            if (r.contains(x, y)) {
                val region = Region()
                val clip = Region(
                    floor(r.left).toInt(), floor(r.top).toInt(),
                    ceil(r.right).toInt(), ceil(r.bottom).toInt()
                )
                region.setPath(path, clip)
                if (region.contains(x.toInt(), y.toInt())) return true
            }
        }
        val pts = s.points
        if (pts.size < 2) return false
        val radius = max(tol, s.baseWidth * 0.5f + 6f)
        val r2 = radius * radius
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val d2 = distPointSeg2(x, y, a.x, a.y, b.x, b.y)
            if (d2 <= r2) return true
        }
        return false
    }

    private fun strokeIntersectsRegion(s: StrokeOp, selRegion: Region): Boolean {
        val area = strokeAreaPath(s) ?: return false
        val pb = RectF()
        area.computeBounds(pb, true)
        val clip = Region(
            floor(pb.left).toInt(), floor(pb.top).toInt(),
            ceil(pb.right).toInt(), ceil(pb.bottom).toInt()
        )
        if (clip.isEmpty) return false
        val r = Region()
        r.setPath(area, clip)
        val inter = Region(r)
        return inter.op(selRegion, Region.Op.INTERSECT) && !inter.isEmpty
    }

    private fun strokeInsideRegion(s: StrokeOp, selRegion: Region): Boolean {
        val area = strokeAreaPath(s) ?: return false
        val pb = RectF()
        area.computeBounds(pb, true)
        val clip = Region(
            floor(pb.left).toInt(), floor(pb.top).toInt(),
            ceil(pb.right).toInt(), ceil(pb.bottom).toInt()
        )
        if (clip.isEmpty) return false
        val r = Region()
        r.setPath(area, clip)
        val remainder = Region(r)
        val hasRemainder = remainder.op(selRegion, Region.Op.DIFFERENCE) && !remainder.isEmpty
        return !hasRemainder
    }

    private fun strokeAreaPath(s: StrokeOp): Path? {
        return when (s.type) {
            BrushType.CALLIGRAPHY -> s.calligPath ?: buildCalligraphyPath(s)
            BrushType.FOUNTAIN -> s.fountainPath ?: buildFountainPath(s)
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                val pts = s.points
                if (pts.size < 2) return null
                val a = pts.first(); val b = pts.last()
                buildThickSegmentPath(a.x, a.y, b.x, b.y, s.baseWidth * 0.5f, squareCaps = true)
            }
            BrushType.HIGHLIGHTER_FREEFORM,
            BrushType.MARKER,
            BrushType.PEN,
            BrushType.PENCIL -> {
                val pts = s.points
                if (pts.size < 2) return null
                buildPolylineArea(pts, s.baseWidth * 0.5f)
            }
            BrushType.ERASER_AREA,
            BrushType.ERASER_STROKE -> null
        }
    }

    private fun strokeBounds(s: StrokeOp): RectF? {
        val p = strokeAreaPath(s) ?: return null
        val r = RectF()
        p.computeBounds(r, true)
        return r
    }

    private fun buildPolylineArea(pts: List<Point>, halfWidth: Float): Path {
        val out = Path()
        var hasAny = false
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = buildThickSegmentPath(a.x, a.y, b.x, b.y, halfWidth, squareCaps = false)
            if (!hasAny) { out.set(seg); hasAny = true }
            else {
                tmpPath.set(out)
                out.rewind()
                out.fillType = Path.FillType.WINDING
                out.op(tmpPath, seg, Path.Op.UNION)
            }
        }
        if (pts.isNotEmpty()) {
            val disc = RectF()
            fun unionDisc(cx: Float, cy: Float) {
                segPath.rewind()
                disc.set(cx - halfWidth, cy - halfWidth, cx + halfWidth, cy + halfWidth)
                segPath.addOval(disc, Path.Direction.CW)
                tmpPath.set(out)
                out.rewind()
                out.fillType = Path.FillType.WINDING
                out.op(tmpPath, segPath, Path.Op.UNION)
            }
            unionDisc(pts.first().x, pts.first().y)
            unionDisc(pts.last().x, pts.last().y)
        }
        return out
    }

    private fun buildThickSegmentPath(
        x0: Float, y0: Float, x1: Float, y1: Float, halfWidth: Float, squareCaps: Boolean
    ): Path {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f)
        val nx = -dy / len
        val ny =  dx / len

        val l0x = x0 + nx * halfWidth; val l0y = y0 + ny * halfWidth
        val r0x = x0 - nx * halfWidth; val r0y = y0 - ny * halfWidth
        val l1x = x1 + nx * halfWidth; val l1y = y1 + ny * halfWidth
        val r1x = x1 - nx * halfWidth; val r1y = y1 - ny * halfWidth

        segPath.rewind()
        segPath.moveTo(l0x, l0y)
        segPath.lineTo(l1x, l1y)
        segPath.lineTo(r1x, r1y)
        segPath.lineTo(r0x, r0y)
        segPath.close()

        if (squareCaps) {
            val tx = dx / len
            val ty = dy / len
            val ex0x = x0 - tx * halfWidth; val ex0y = y0 - ty * halfWidth
            val ex1x = x1 + tx * halfWidth; val ex1y = y1 + ty * halfWidth

            val cap0 = Path()
            cap0.moveTo(ex0x + nx * halfWidth, ex0y + ny * halfWidth)
            cap0.lineTo(l0x, l0y)
            cap0.lineTo(r0x, r0y)
            cap0.lineTo(ex0x - nx * halfWidth, ex0y - ny * halfWidth)
            cap0.close()

            val cap1 = Path()
            cap1.moveTo(l1x, l1y)
            cap1.lineTo(ex1x + nx * halfWidth, ex1y + ny * halfWidth)
            cap1.lineTo(ex1x - nx * halfWidth, ex1y - ny * halfWidth)
            cap1.lineTo(r1x, r1y)
            cap1.close()

            val tmp = Path(segPath)
            segPath.rewind()
            segPath.fillType = Path.FillType.WINDING
            segPath.op(tmp, cap0, Path.Op.UNION)
            tmp.set(segPath)
            segPath.rewind()
            segPath.fillType = Path.FillType.WINDING
            segPath.op(tmp, cap1, Path.Op.UNION)
        }
        return Path(segPath)
    }

    private fun distPointSeg2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax; val aby = by - ay
        val apx = px - ax; val apy = py - ay
        val ab2 = abx * abx + aby * aby
        val t = if (ab2 <= 1e-6f) 0f else ((apx * abx + apy * aby) / ab2).coerceIn(0f, 1f)
        val cx = ax + t * abx; val cy = ay + t * aby
        val dx = px - cx; val dy = py - cy
        return dx * dx + dy * dy
    }

    // ===== Utilities & gestures =====

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun toContent(viewX: Float, viewY: Float): Pair<Float, Float> {
        val cx = (viewX - translationX) / scaleFactor
        val cy = (viewY - translationY) / scaleFactor
        return cx to cy
    }

    private fun isStylus(ev: MotionEvent, pointerIndex: Int): Boolean {
        val tool = ev.getToolType(pointerIndex)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun acceptsPointer(ev: MotionEvent, pointerIndex: Int): Boolean {
        // Allow everything if stylusOnly == false; otherwise require stylus/eraser tool type
        return !stylusOnly || isStylus(ev, pointerIndex)
    }


    private fun averageX(ev: MotionEvent): Float {
        var sum = 0f; for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return sum / ev.pointerCount
    }
    private fun averageY(ev: MotionEvent): Float {
        var sum = 0f; for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 4f)

            val fx = detector.focusX
            val fy = detector.focusY
            val dx = fx - translationX
            val dy = fy - translationY
            translationX = fx - dx * (scaleFactor / prev)
            translationY = fy - dy * (scaleFactor / prev)
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            drawing = false
            selectingGesture = false
            transforming = false
            activePointerId = -1
            scalingInProgress = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scalingInProgress = false
            // If we ended at 1×, ensure horizontal pan is locked
            if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scaleFactor < 1.5f) 2f else 1f
            zoomTo(target, e.x, e.y)
            return true
        }
    }


    private fun cancelActiveOps() {
        when {
            drawing -> finishStroke()
            selectingGesture -> finishSelection()
            transforming -> finishTransform()
        }
    }

    private fun clearMarquee() {
        marqueePath = null
        marqueeRect.setEmpty()
        lassoPts.clear()
    }

    private fun computeSelectionBounds(): RectF? {
        var have = false
        val out = RectF()
        for (s in selectedStrokes) {
            val b = strokeBounds(s) ?: continue
            if (!have) { out.set(b); have = true } else out.union(b)
        }
        return if (have) out else null
    }

    // ===== Drawing: extend & finish stroke =====

    private fun extendStroke(x: Float, y: Float) {
        val op = current ?: return
        val sx = lastX
        val sy = lastY

        val dx = x - sx
        val dy = y - sy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist <= 0.01f) return

        op.points.add(Point(x, y))

        when (op.type) {
            BrushType.HIGHLIGHTER_FREEFORM -> {
                val c = scratchCanvasHL ?: return
                val p = newStrokePaint(op.color, op.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                c.drawLine(sx, sy, x, y, p)
            }
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                val c = scratchCanvasHL ?: return
                scratchHL?.eraseColor(Color.TRANSPARENT)
                val p = newStrokePaint(op.color, op.baseWidth).apply { strokeCap = Paint.Cap.SQUARE }
                val a = op.points.first()
                c.drawLine(a.x, a.y, x, y, p)
            }
            BrushType.ERASER_AREA -> {
                val clearInk = !eraserHLOnly
                val half = op.baseWidth * 0.5f
                val steps = max(1, (dist / max(1f, half)).toInt())
                val ci = canvasInk
                val ch = canvasHL
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val px = sx + dx * t
                    val py = sy + dy * t
                    if (clearInk) ci?.drawCircle(px, py, half, clearPaint)
                    ch?.drawCircle(px, py, half, clearPaint)
                }
            }
            BrushType.ERASER_STROKE -> {
                val radius = max(6f, op.baseWidth * 0.5f)
                val hlOnly = eraserHLOnly
                var removedOne = false
                for (i in strokes.size - 1 downTo 0) {
                    val s = strokes[i]
                    if (s === op) continue
                    val isHL = (s.type == BrushType.HIGHLIGHTER_FREEFORM || s.type == BrushType.HIGHLIGHTER_STRAIGHT)
                    if (hlOnly && !isHL) continue
                    if (!hlOnly && (s.type == BrushType.ERASER_AREA || s.type == BrushType.ERASER_STROKE)) continue
                    if (hitStroke(s, x, y, radius)) {
                        strokes.removeAt(i)
                        op.erased?.add(ErasedEntry(i, s))
                        removedOne = true
                        break
                    }
                }
                if (removedOne) rebuildCommitted()
            }
            BrushType.CALLIGRAPHY -> {
                val c = scratchCanvasInk ?: return
                c.drawPath(buildCalligraphyPath(op), fillPaint(op.color))
            }
            BrushType.FOUNTAIN -> {
                val c = scratchCanvasInk ?: return
                c.drawPath(buildFountainPath(op), fillPaint(op.color))
            }
            BrushType.PENCIL -> {
                val c = scratchCanvasInk ?: return
                drawPencilSegment(c, sx, sy, x, y, op, dist)
            }
            BrushType.MARKER -> {
                val c = scratchCanvasInk ?: return
                drawMarkerSegment(c, sx, sy, x, y, op, dist)
            }
            BrushType.PEN -> {
                val c = scratchCanvasInk ?: return
                drawPenSegment(c, sx, sy, x, y, op)
            }
        }

        lastX = x
        lastY = y
    }

    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke ->
            if (stroke.type == BrushType.ERASER_AREA || stroke.type == BrushType.ERASER_STROKE) {
                stroke.eraseHLOnly = eraserHLOnly
            }
            when (stroke.type) {
                BrushType.CALLIGRAPHY -> stroke.calligPath = Path(buildCalligraphyPath(stroke))
                BrushType.FOUNTAIN    -> stroke.fountainPath = Path(buildFountainPath(stroke))
                else -> { /* no snapshot */ }
            }
            strokes.add(stroke)
        }
        current = null
        drawing = false
        activePointerId = -1

        rebuildCommitted()
        scratchInk?.eraseColor(Color.TRANSPARENT)
        scratchHL?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // ===== Paste placement =====

    private fun performPasteAt(cx: Float, cy: Float) {
        val src = clipboardBounds ?: return
        cancelTransform()
        selectedStrokes.clear()

        val dx = cx - src.centerX()
        val dy = cy - src.centerY()

        val copies = clipboard.map { copy ->
            val c = deepCopy(copy)
            translateStroke(c, dx, dy)
            c
        }
        strokes.addAll(copies) // pasted to top
        selectedStrokes.addAll(copies)
        selectedBounds = RectF(src).apply { offset(dx, dy) }
        selectionInteractive = true

        // hide ghost preview after placing
        pastePreviewVisible = false


        rebuildCommitted()
        invalidate()
    }

    // ===== Helpers: deep copy, translation & z‑order for clipboard/selection =====

    private fun deepCopy(s: StrokeOp): StrokeOp {
        return StrokeOp(
            type = s.type,
            color = s.color,
            baseWidth = s.baseWidth,
            points = s.points.map { Point(it.x, it.y) }.toMutableList(),
            stampPhase = s.stampPhase,
            lastDir = s.lastDir,
            calligPath = s.calligPath?.let { Path(it) },
            fountainPath = s.fountainPath?.let { Path(it) },
            eraseHLOnly = s.eraseHLOnly
        )
    }

    private fun translateStroke(s: StrokeOp, dx: Float, dy: Float) {
        for (i in s.points.indices) {
            val p = s.points[i]
            s.points[i] = Point(p.x + dx, p.y + dy)
        }
        s.calligPath = null
        s.fountainPath = null
    }

    private fun moveSelectionToTop() {
        if (selectedStrokes.isEmpty()) return
        val ordered = strokes.filter { selectedStrokes.contains(it) }
        if (ordered.isEmpty()) return
        val set = selectedStrokes
        val it = strokes.iterator()
        while (it.hasNext()) {
            if (set.contains(it.next())) it.remove()
        }
        strokes.addAll(ordered)
    }
}

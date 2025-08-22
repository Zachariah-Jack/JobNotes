package com.pelicankb.jobnotes.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.random.Random

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

    fun undo() {
        if (strokes.isEmpty()) return
        val last = strokes.removeAt(strokes.lastIndex)
        when (last.type) {
            BrushType.ERASER_STROKE -> {
                // Put back what we removed (at original indices)
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
                // Remove again
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
        invalidate()
    }

    // ===== Stroke model =====

    data class Point(val x: Float, val y: Float)
    private data class ErasedEntry(val index: Int, val stroke: StrokeOp)

    private data class StrokeOp(
        val type: BrushType,
        val color: Int,
        val baseWidth: Float,
        val points: MutableList<Point> = mutableListOf(),

        // segment stamping / caches
        var stampPhase: Float = 0f,
        var lastDir: Float? = null,

        // geometry snapshots for union-based tools
        var calligPath: Path? = null,
        var fountainPath: Path? = null,

        // eraser-specific
        var eraseHLOnly: Boolean = false,            // recorded for rebuild
        var erased: MutableList<ErasedEntry>? = null // only for stroke eraser
    )

    private val strokes = mutableListOf<StrokeOp>()
    private val redoStack = mutableListOf<StrokeOp>()
    private var current: StrokeOp? = null

    // ===== “Next stroke” configuration =====
    private var baseBrush: BrushType = BrushType.PEN
    private var baseColor: Int = Color.BLACK
    private var baseWidthPx: Float = dpToPx(4f)
    private var eraserHLOnly: Boolean = false       // LIVE flag (can change mid‑stroke)

    // ===== Layers =====
    // Ink (pens, pencil, marker, fountain, calligraphy)
    private var committedInk: Bitmap? = null
    private var canvasInk: Canvas? = null

    // Highlighter layer
    private var committedHL: Bitmap? = null
    private var canvasHL: Canvas? = null

    // Scratch (live preview)
    private var scratchInk: Bitmap? = null
    private var scratchCanvasInk: Canvas? = null
    private var scratchHL: Bitmap? = null
    private var scratchCanvasHL: Canvas? = null

    // ===== Transform (pan/zoom) =====
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var scaleFactor = 1f
    private var translationX = 0f
    private var translationY = 0f
    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f

    // Stylus drawing state
    private var drawing = false
    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    // Calligraphy nib angle (fixed)
    private val nibAngleRad = Math.toRadians(45.0).toFloat()
    private val nibAngleDeg = Math.toDegrees(nibAngleRad.toDouble()).toFloat()

    // ===== View lifecycle =====

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

            // Order chosen so highlighter looks "behind ink" even while previewing:
            // 1) committed HL
            // 2) scratch HL (preview)  ← still beneath ink
            // 3) committed INK
            // 4) scratch INK (preview)
            committedHL?.let { drawBitmap(it, 0f, 0f, null) }
            scratchHL?.let { drawBitmap(it, 0f, 0f, null) }
            committedInk?.let { drawBitmap(it, 0f, 0f, null) }
            scratchInk?.let { drawBitmap(it, 0f, 0f, null) }
        }
    }

    // ===== Input =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isStylus(event, event.actionIndex)) {
                    startStroke(event, event.actionIndex)
                } else {
                    drawing = false
                    activePointerId = -1
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                drawing = false
                activePointerId = -1
                lastPanFocusX = averageX(event)
                lastPanFocusY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val (cx, cy) = toContent(event.getX(idx), event.getY(idx))
                        extendStroke(cx, cy)
                        invalidate()
                    }
                } else if (event.pointerCount >= 2) {
                    val fx = averageX(event)
                    val fy = averageY(event)
                    translationX += fx - lastPanFocusX
                    translationY += fy - lastPanFocusY
                    lastPanFocusX = fx
                    lastPanFocusY = fy
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finishStroke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishStroke()
        }
        return true
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
            it.eraseHLOnly = eraserHLOnly     // record at start (used by rebuild)
            if (baseBrush == BrushType.ERASER_STROKE) it.erased = mutableListOf()
        }

        // Reset relevant scratches
        when (baseBrush) {
            BrushType.HIGHLIGHTER_FREEFORM,
            BrushType.HIGHLIGHTER_STRAIGHT -> scratchHL?.eraseColor(Color.TRANSPARENT)
            BrushType.ERASER_AREA -> { /* clears committed live; no scratch */ }
            else -> scratchInk?.eraseColor(Color.TRANSPARENT)
        }

        lastX = x
        lastY = y
        drawing = true
        activePointerId = ev.getPointerId(idx)
    }

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
            // ========== Highlighter ==========
            BrushType.HIGHLIGHTER_FREEFORM -> {
                // accumulate segments on HL scratch
                val c = scratchCanvasHL ?: return
                val p = newStrokePaint(op.color, op.baseWidth).apply {
                    strokeCap = Paint.Cap.SQUARE
                }
                c.drawLine(sx, sy, x, y, p)
            }
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                // clear preview each move, draw single segment from anchor
                val c = scratchCanvasHL ?: return
                scratchHL?.eraseColor(Color.TRANSPARENT)
                val p = newStrokePaint(op.color, op.baseWidth).apply {
                    strokeCap = Paint.Cap.SQUARE
                }
                val a = op.points.first()
                c.drawLine(a.x, a.y, x, y, p)
            }

            // ========== Eraser: AREA ==========
            BrushType.ERASER_AREA -> {
                // Use the LIVE toggle (so user can flip mid‑stroke).
                val clearInk = !eraserHLOnly
                val half = op.baseWidth * 0.5f
                val steps = max(1, (dist / max(1f, half)).toInt())
                val ci = canvasInk
                val ch = canvasHL
                clearPaint.strokeWidth = 0f
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val px = sx + dx * t
                    val py = sy + dy * t
                    if (clearInk) ci?.drawCircle(px, py, half, clearPaint)
                    ch?.drawCircle(px, py, half, clearPaint)
                }
            }

            // ========== Eraser: STROKE ==========
            BrushType.ERASER_STROKE -> {
                // remove topmost hit stroke immediately (use LIVE toggle)
                val radius = max(6f, op.baseWidth * 0.5f)
                val hlOnly = eraserHLOnly
                var removedOne = false
                for (i in strokes.size - 1 downTo 0) {
                    val s = strokes[i]
                    if (s === op) continue
                    // filter by family
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
                if (removedOne) {
                    rebuildCommitted()  // live feedback
                }
            }

            // ========== Ink tools ==========
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
            // Record the final HL-only choice for deterministic rebuild.
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

    /** Width: hairline when aligned with nib; bold when perpendicular. */
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

    // ===== Rebuild committed layers from the timeline =====

    private fun rebuildCommitted() {
        val ci = canvasInk ?: return
        val ch = canvasHL ?: return
        committedInk?.eraseColor(Color.TRANSPARENT)
        committedHL?.eraseColor(Color.TRANSPARENT)

        for (op in strokes) {
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
                    ci.drawPath(path, fillPaint(op.color))
                }
                BrushType.FOUNTAIN -> {
                    val path = op.fountainPath ?: Path(buildFountainPath(op)).also { op.fountainPath = it }
                    ci.drawPath(path, fillPaint(op.color))
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
                BrushType.ERASER_STROKE -> {
                    // no-op; effect already realized by removed strokes
                }
            }
        }
    }

    // ===== Hit testing for stroke eraser =====

    private fun hitStroke(s: StrokeOp, x: Float, y: Float, tol: Float): Boolean {
        // Try path hit if available (fountain/callig)
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
        // Segment distance test
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

    private fun distPointSeg2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax; val aby = by - ay
        val apx = px - ax; val apy = py - ay
        val ab2 = abx * abx + aby * aby
        val t = if (ab2 <= 1e-6f) 0f else ((apx * abx + apy * aby) / ab2).coerceIn(0f, 1f)
        val cx = ax + t * abx; val cy = ay + t * aby
        val dx = px - cx; val dy = py - cy
        return dx * dx + dy * dy
    }

    // ===== Utilities =====

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
            activePointerId = -1
            return true
        }
    }
}

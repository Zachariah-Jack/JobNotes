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

class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ===== Public API =====

    fun setBrush(type: BrushType) { baseBrush = type }

    /** Brush size in dp (converted to px internally). */
    fun setStrokeWidthDp(sizeDp: Float) { baseWidthPx = dpToPx(sizeDp) }

    fun setColor(color: Int) { baseColor = color }

    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.lastIndex))
            rebuildCommitted()
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            strokes.add(redoStack.removeAt(redoStack.lastIndex))
            rebuildCommitted()
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        current = null
        committedBitmap?.eraseColor(Color.TRANSPARENT)
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // ===== Stroke model =====

    data class Point(val x: Float, val y: Float)

    private data class StrokeOp(
        val type: BrushType,
        val color: Int,
        val baseWidth: Float,
        val points: MutableList<Point> = mutableListOf(),
        var stampPhase: Float = 0f,
        var lastDir: Float? = null,
        // Final geometry snapshots (deep-copied Path on pen-up)
        var calligPath: Path? = null,
        var fountainPath: Path? = null,
        var highlightPath: Path? = null       // NEW: for both highlighter modes
    )

    private val strokes = mutableListOf<StrokeOp>()
    private val redoStack = mutableListOf<StrokeOp>()
    private var current: StrokeOp? = null

    // ===== “Next stroke” configuration =====
    private var baseBrush: BrushType = BrushType.PEN
    private var baseColor: Int = Color.BLACK
    private var baseWidthPx: Float = dpToPx(4f)

    // ===== Layers =====
    private var committedBitmap: Bitmap? = null
    private var committedCanvas: Canvas? = null

    private var scratchBitmap: Bitmap? = null
    private var scratchCanvas: Canvas? = null

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

        committedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            committedCanvas = Canvas(it)
        }
        scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            scratchCanvas = Canvas(it)
        }
        rebuildCommitted()
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withSave {
            translate(translationX, translationY)
            scale(scaleFactor, scaleFactor)
            committedBitmap?.let { drawBitmap(it, 0f, 0f, null) }
            scratchBitmap?.let { drawBitmap(it, 0f, 0f, null) }
        }
    }

    // ===== Input =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always run scale detector (pinch zoom via fingers)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isStylus(event, event.actionIndex)) {
                    startStroke(event, event.actionIndex)
                } else {
                    // Finger alone: no drawing
                    drawing = false
                    activePointerId = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch → pan/zoom; never draw
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
                    // Two-finger pan
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
            it.highlightPath = null
        }

        // Reset scratch for the new stroke
        scratchBitmap?.eraseColor(Color.TRANSPARENT)

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

        val c = scratchCanvas ?: return

        when (op.type) {
            BrushType.CALLIGRAPHY -> {
                op.points.add(Point(x, y))
                scratchBitmap?.eraseColor(Color.TRANSPARENT)
                val path = buildCalligraphyPath(op)
                calligPaint.color = op.color
                c.drawPath(path, calligPaint)
            }
            BrushType.FOUNTAIN -> {
                op.points.add(Point(x, y))
                scratchBitmap?.eraseColor(Color.TRANSPARENT)
                val path = buildFountainPath(op)
                calligPaint.color = op.color
                c.drawPath(path, calligPaint)
            }
            BrushType.HIGHLIGHTER_FREEFORM -> {
                op.points.add(Point(x, y))
                scratchBitmap?.eraseColor(Color.TRANSPARENT)
                val path = buildFountainPath(op) // same geometry as fountain, just translucent color
                calligPaint.color = op.color
                c.drawPath(path, calligPaint)
            }
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                // Keep only start + latest end
                if (op.points.size == 1) {
                    op.points.add(Point(x, y))
                } else {
                    op.points[op.points.lastIndex] = Point(x, y)
                }
                scratchBitmap?.eraseColor(Color.TRANSPARENT)
                val p0 = op.points.first()
                val p1 = op.points.last()
                val path = buildStraightBarPath(p0.x, p0.y, p1.x, p1.y, op.baseWidth)
                calligPaint.color = op.color
                c.drawPath(path, calligPaint)
            }
            BrushType.PENCIL -> {
                op.points.add(Point(x, y))
                drawPencilSegment(c, sx, sy, x, y, op, dist)
            }
            BrushType.MARKER -> {
                op.points.add(Point(x, y))
                drawMarkerSegment(c, sx, sy, x, y, op, dist)
            }
            BrushType.PEN -> {
                op.points.add(Point(x, y))
                drawPenSegment(c, sx, sy, x, y, op)
            }
        }

        lastX = x
        lastY = y
    }

    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke ->
            when (stroke.type) {
                BrushType.CALLIGRAPHY -> {
                    val work = buildCalligraphyPath(stroke)
                    stroke.calligPath = Path(work)
                }
                BrushType.FOUNTAIN -> {
                    val work = buildFountainPath(stroke)
                    stroke.fountainPath = Path(work)
                }
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val work = buildFountainPath(stroke)
                    stroke.highlightPath = Path(work)
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    // Final straight bar from first to last point
                    val p0 = stroke.points.first()
                    val p1 = stroke.points.last()
                    val work = buildStraightBarPath(p0.x, p0.y, p1.x, p1.y, stroke.baseWidth)
                    stroke.highlightPath = Path(work)
                }
                else -> { /* nothing to snapshot */ }
            }
            strokes.add(stroke)
        }
        current = null
        drawing = false
        activePointerId = -1

        // Commit everything
        rebuildCommitted()
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // ===== Segment renderers (incremental, except union-based tools) =====

    private fun newStrokePaint(color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            xfermode = null
        }

    private fun newFillPaint(color: Int, alpha: Int = 255): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            this.alpha = alpha.coerceIn(0, 255)
            xfermode = null
        }

    private fun drawPenSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp
    ) {
        val p = newStrokePaint(op.color, op.baseWidth)
        canvas.drawLine(x0, y0, x1, y1, p)
    }

    // Marker as a soft, heavy pen
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

    // ===== PENCIL (fast + crisp, pre-rotated stamps) =====

    // Fast xorshift RNG for hot loop
    private var rngState = 0x9E3779B9.toInt()
    private inline fun frand(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        val v = (x ushr 1) and 0x7FFFFFFF
        return v * (1f / 0x7FFFFFFF)
    }

    // Stamp paint (ALPHA_8), no filtering for crisper look
    private val pencilStampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = false    // crisp
        xfermode = null
    }

    // Base & pre-rotated stamps (0..180°)
    private var pencilBaseStamp: Bitmap? = null
    private val PENCIL_ROT_BINS = 16
    private val pencilRotStamps: Array<Bitmap?> = arrayOfNulls(PENCIL_ROT_BINS)
    private val dstRect = RectF()

    /** Create / cache base ALPHA_8 stamp with sharper falloff + dropout. */
    private fun ensurePencilBaseStamp(): Bitmap {
        var bmp = pencilBaseStamp
        if (bmp != null && !bmp.isRecycled) return bmp

        val size = 64
        val rnd = FloatArray(size * size) { frand() }
        fun blur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            val win = 2 * radius + 1
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            // horizontal
            for (y in 0 until h) {
                var acc = 0f
                for (x in -radius..radius) acc += src[(y * w) + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = acc / win
                    val x0 = (x - radius).coerceIn(0, w - 1)
                    val x2 = (x + radius + 1).coerceIn(0, w - 1)
                    acc += src[y * w + x2] - src[y * w + x0]
                }
            }
            // vertical
            for (x in 0 until w) {
                var acc = 0f
                for (y in -radius..radius) acc += tmp[(y.coerceIn(0, h - 1)) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = acc / win
                    val y0 = (y - radius).coerceIn(0, h - 1)
                    val y2 = (y + radius + 1).coerceIn(0, h - 1)
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
                n = (n * 1.20f - 0.25f).coerceIn(0f, 1f)

                val dx = (x - cx) / maxR
                val dy = (y - cy) / maxR
                val r2 = dx * dx + dy * dy
                val fall = if (r2 >= 1f) 0f else exp(-3.8f * r2).toFloat()

                val a = (fall * n).coerceIn(0f, 1f)
                out[i] = (a * 255f).toInt().toByte()
            }
        }

        bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        pencilBaseStamp = bmp
        return bmp
    }

    /** Build 16 rotated ALPHA_8 stamps (0..180°). */
    private fun ensurePencilRotStamps() {
        if (pencilRotStamps[0] != null && !(pencilRotStamps[0]?.isRecycled ?: true)) return
        val base = ensurePencilBaseStamp()
        val size = base.width
        val center = size / 2f
        val step = 180f / PENCIL_ROT_BINS

        for (i in 0 until PENCIL_ROT_BINS) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val m = Matrix()
            m.postRotate(i * step, center, center)
            c.drawBitmap(base, m, null)
            pencilRotStamps[i] = bmp
        }
    }

    private fun rotIndexFor(angleDeg: Float): Int {
        val step = 180f / PENCIL_ROT_BINS
        var a = angleDeg % 180f
        if (a < 0f) a += 180f
        val idx = ((a / step) + 0.5f).toInt()
        return idx % PENCIL_ROT_BINS
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

        // Tangent & normal
        val inv = 1f / len
        val tx = dx * inv
        val ty = dy * inv
        val nx = -ty
        val ny = tx

        // Base spacing; adaptive cap to limit stamps per move (<= ~64)
        val baseSpacing = max(0.20f * op.baseWidth, 1.2f)
        val spacing = max(baseSpacing, len / 64f)

        // Stamp size (slightly elongated)
        val base = op.baseWidth.coerceAtLeast(0.75f)
        val longAxis = base * 1.15f
        val shortAxis = base * 0.80f

        // Angle of stroke in degrees
        val angleDeg = atan2(ty, tx) * 180f / Math.PI.toFloat()
        ensurePencilRotStamps()
        var rotIdx = rotIndexFor(angleDeg)

        // Per-segment alpha (darker at slower "speed"); slightly stronger overall
        fun alphaForSpeed(s: Float): Int {
            val base = 120f / (1f + 0.06f * s) + 20f
            val a = base * 1.50f
            return a.roundToInt().coerceIn(30, 140)
        }
        pencilStampPaint.color = op.color
        pencilStampPaint.alpha = alphaForSpeed(len)

        var t = op.stampPhase
        while (t < len) {
            val px = x0 + dx * (t / len)
            val py = y0 + dy * (t / len)

            // Small jitter (fast RNG)
            val jN = (frand() - 0.5f) * (base * 0.18f)
            val jT = (frand() - 0.5f) * (base * 0.12f)
            val jx = px + nx * jN + tx * jT
            val jy = py + ny * jN + ty * jT

            // Tiny orientation jitter by hopping ±1 bin occasionally
            val jitterChance = 0.15f
            val idx = if (frand() < jitterChance) {
                val d = if (frand() < 0.5f) -1 else 1
                (rotIdx + d + PENCIL_ROT_BINS) % PENCIL_ROT_BINS
            } else rotIdx

            val stamp = pencilRotStamps[idx]!!
            val scaleLong = longAxis * (0.95f + 0.10f * frand())
            val scaleShort = shortAxis * (0.92f + 0.08f * frand())
            val halfW = scaleLong * 0.5f
            val halfH = scaleShort * 0.5f

            dstRect.set(jx - halfW, jy - halfH, jx + halfW, jy + halfH)
            canvas.drawBitmap(stamp, null, dstRect, pencilStampPaint)

            t += spacing
        }
        op.stampPhase = t - len
    }

    // ===== Calligraphy & Fountain (union of segment trapezoids; no self-cancel) =====

    private val workCalligPath = Path().apply { fillType = Path.FillType.WINDING }
    private val tmpPath = Path()
    private val segPath = Path()
    private val calligPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
        xfermode = null          // SRC_OVER blending (no erasing)
    }

    private fun buildCalligraphyPath(op: StrokeOp): Path {
        workCalligPath.rewind()
        workCalligPath.fillType = Path.FillType.WINDING

        // Speed‑adaptive resampling to reduce scallops.
        val step = max(0.33f * op.baseWidth, 0.9f)
        val pts = resample(op.points, step)
        if (pts.size < 2) return workCalligPath

        // Direction smoothing to avoid flutter.
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

        // Fixed nib normal (chisel).
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
                hasAccum = true
            } else {
                tmpPath.set(workCalligPath)
                workCalligPath.rewind()
                workCalligPath.fillType = Path.FillType.WINDING
                workCalligPath.op(tmpPath, segPath, Path.Op.UNION)
            }
        }

        return workCalligPath
    }

    /** Fountain: same unioned ribbon, but round nib (direction‑independent width). */
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
                hasAccum = true
            } else {
                tmpPath.set(workCalligPath)
                workCalligPath.rewind()
                workCalligPath.fillType = Path.FillType.WINDING
                workCalligPath.op(tmpPath, segPath, Path.Op.UNION)
            }
        }

        return workCalligPath
    }

    /** Straight highlighter: square-capped bar between two points. */
    private fun buildStraightBarPath(x0: Float, y0: Float, x1: Float, y1: Float, width: Float): Path {
        val path = Path()
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len <= 1e-3f) {
            // tiny dot rectangle
            val hw = max(0.5f, width * 0.5f)
            path.addRect(x0 - hw, y0 - hw, x0 + hw, y0 + hw, Path.Direction.CW)
            return path
        }
        val inv = 1f / len
        val nx = -dy * inv
        val ny =  dx * inv
        val hw = max(0.5f, width * 0.5f)

        val l0x = x0 + nx * hw; val l0y = y0 + ny * hw
        val r0x = x0 - nx * hw; val r0y = y0 - ny * hw
        val l1x = x1 + nx * hw; val l1y = y1 + ny * hw
        val r1x = x1 - nx * hw; val r1y = y1 - ny * hw

        path.moveTo(l0x, l0y)
        path.lineTo(l1x, l1y)
        path.lineTo(r1x, r1y)
        path.lineTo(r0x, r0y)
        path.close()
        return path
    }

    /** Width: hairline when aligned with nib; bold when perpendicular. */
    private fun calligWidth(op: StrokeOp, dir: Float): Float {
        val rel = abs(sin(dir - nibAngleRad))               // 0..1
        val minW = max(0.7f, op.baseWidth * 0.06f)          // avoid degenerates
        val maxW = max(minW + 1f, op.baseWidth * 1.05f)     // slender look
        return minW + (maxW - minW) * rel
    }

    private fun shortestAngleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d >  Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    // Resample a polyline to (approximately) fixed arc‑length steps
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

    // ===== Rebuild committed layer from the stroke list =====

    private fun rebuildCommitted() {
        val c = committedCanvas ?: return
        committedBitmap?.eraseColor(Color.TRANSPARENT)

        for (op in strokes) {
            op.stampPhase = 0f
            op.lastDir = null

            when (op.type) {
                BrushType.CALLIGRAPHY -> {
                    val path = op.calligPath ?: Path(buildCalligraphyPath(op)).also { op.calligPath = it }
                    calligPaint.color = op.color
                    c.drawPath(path, calligPaint)
                }
                BrushType.FOUNTAIN -> {
                    val path = op.fountainPath ?: Path(buildFountainPath(op)).also { op.fountainPath = it }
                    calligPaint.color = op.color
                    c.drawPath(path, calligPaint)
                }
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val path = op.highlightPath ?: Path(buildFountainPath(op)).also { op.highlightPath = it }
                    calligPaint.color = op.color
                    c.drawPath(path, calligPaint)
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val p0 = op.points.firstOrNull()
                    val p1 = op.points.lastOrNull()
                    val path = op.highlightPath ?: if (p0 != null && p1 != null) {
                        Path(buildStraightBarPath(p0.x, p0.y, p1.x, p1.y, op.baseWidth)).also { op.highlightPath = it }
                    } else Path()
                    calligPaint.color = op.color
                    c.drawPath(path, calligPaint)
                }
                else -> {
                    val pts = op.points
                    if (pts.size < 2) continue
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]
                        val b = pts[i]
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        when (op.type) {
                            BrushType.PEN    -> drawPenSegment(c, a.x, a.y, b.x, b.y, op)
                            BrushType.MARKER -> drawMarkerSegment(c, a.x, a.y, b.x, b.y, op, dist)
                            BrushType.PENCIL -> drawPencilSegment(c, a.x, a.y, b.x, b.y, op, dist)
                            else -> {}
                        }
                    }
                }
            }
        }
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

            // Zoom around gesture focus
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
            // Entering pinch: stop any drawing
            drawing = false
            activePointerId = -1
            return true
        }
    }
}

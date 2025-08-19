package com.pelicankb.jobnotes.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave
import kotlin.math.*
import kotlin.random.Random

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
        // Final geometry snapshot for calligraphy (deep-copied Path;
        // never mutated after pen-up)
        var calligPath: Path? = null
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

    // Calligraphy nib angle (fixed, tweak to taste)
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

        // Append point for committed history
        op.points.add(Point(x, y))

        val c = scratchCanvas ?: return

        if (op.type == BrushType.CALLIGRAPHY) {
            // Live preview: rebuild as union of segment trapezoids.
            scratchBitmap?.eraseColor(Color.TRANSPARENT)
            val path = buildCalligraphyPath(op) // working path
            calligPaint.color = op.color
            c.drawPath(path, calligPaint)
        } else {
            // Render newest segment for other tools
            when (op.type) {
                BrushType.PEN      -> drawPenSegment(c, sx, sy, x, y, op)
                BrushType.FOUNTAIN -> drawFountainSegment(c, sx, sy, x, y, op, dist)
                BrushType.MARKER   -> drawMarkerSegment(c, sx, sy, x, y, op, dist)
                BrushType.PENCIL   -> drawPencilSegment(c, sx, sy, x, y, op, dist)
                else -> { /* no-op */ }
            }
        }

        lastX = x
        lastY = y
    }

    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke ->
            if (stroke.type == BrushType.CALLIGRAPHY) {
                // Snapshot (deep copy) so later strokes cannot mutate it.
                val work = buildCalligraphyPath(stroke)
                stroke.calligPath = Path(work)
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

    // ===== Segment renderers (incremental, except calligraphy which uses whole-stroke path) =====

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

    private fun drawFountainSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, dist: Float
    ) {
        // Slight width boost with speed; crisp edges
        val speed = dist
        val width = op.baseWidth + min(op.baseWidth * 0.55f, speed * 0.05f)
        val p = newStrokePaint(op.color, width)
        canvas.drawLine(x0, y0, x1, y1, p)
    }

    // Marker as a soft, heavy pen (no stamping → no scallops)
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

    private fun drawPencilSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, dist: Float
    ) {
        // Core graphite line (a bit darker)
        val core = newStrokePaint(op.color, max(1f, op.baseWidth * 0.9f)).apply {
            alpha = 235
            maskFilter = BlurMaskFilter(max(0.5f, op.baseWidth * 0.10f), BlurMaskFilter.Blur.NORMAL)
        }
        val p = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }
        canvas.drawPath(p, core)

        // Tight, near-line speckles
        val spacing = max(0.8f, op.baseWidth * 0.30f)
        val dx = x1 - x0
        val dy = y1 - y0
        val len = dist
        val nx = -dy / (len + 1e-4f)
        val ny =  dx / (len + 1e-4f)

        val speckPaint = newFillPaint(op.color, 90)
        val radiusMax = max(0.6f, op.baseWidth * 0.12f)
        val specksPerStamp = when {
            op.baseWidth < 4f  -> 1
            op.baseWidth < 8f  -> 2
            else               -> 3
        }

        var t = op.stampPhase
        while (t < dist) {
            val px = x0 + dx * (t / dist)
            val py = y0 + dy * (t / dist)
            repeat(specksPerStamp) {
                val r = Random.nextFloat() * radiusMax
                val side = if (Random.nextBoolean()) 1f else -1f
                val jx = px + nx * r * side + (Random.nextFloat() - 0.5f) * radiusMax * 0.3f
                val jy = py + ny * r * side + (Random.nextFloat() - 0.5f) * radiusMax * 0.3f
                canvas.drawCircle(jx, jy, max(0.4f, op.baseWidth * 0.06f), speckPaint)
            }
            t += spacing
        }
        op.stampPhase = t - dist
    }

    // ===== Calligraphy (union of segment trapezoids; no caps; no self-cancel) =====

    // Working path reused for live preview (never stored in strokes).
    private val workCalligPath = Path().apply { fillType = Path.FillType.WINDING }
    private val tmpPath = Path()
    private val segPath = Path()
    private val calligPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
        xfermode = null          // SRC_OVER blending (no erasing)
    }

    /**
     * Build the calligraphy stroke into a *non self-cancelling* path by
     * unioning a sequence of small convex trapezoids between resampled
     * points. This avoids gaps when the stroke crosses itself.
     */
    private fun buildCalligraphyPath(op: StrokeOp): Path {
        workCalligPath.rewind()
        workCalligPath.fillType = Path.FillType.WINDING

        // Speed‑adaptive resampling to reduce scallops.
        val step = max(0.33f * op.baseWidth, 0.9f)
        val pts = resample(op.points, step)
        if (pts.size < 2) return workCalligPath

        // Precompute smoothed directions at each resampled point.
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

        // Constant nib normal (chisel nib).
        val nx = -sin(nibAngleRad)
        val ny =  cos(nibAngleRad)

        var hasAccum = false
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]
            val p1 = pts[i]
            val w0 = calligWidth(op, dirs[i - 1])
            val w1 = calligWidth(op, dirs[i])

            // Clamp to avoid degenerates near hairlines
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

            // Trapezoid for this segment
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
                // dst = union(accum, seg)
                tmpPath.set(workCalligPath)
                workCalligPath.rewind()
                workCalligPath.fillType = Path.FillType.WINDING
                workCalligPath.op(tmpPath, segPath, Path.Op.UNION)
            }
        }

        return workCalligPath
    }

    /** Width: hairline when aligned with nib; bold when perpendicular. */
    private fun calligWidth(op: StrokeOp, dir: Float): Float {
        val rel = abs(sin(dir - nibAngleRad))               // 0..1
        // Slightly higher floor avoids degenerate quads at tight hairlines
        val minW = max(0.7f, op.baseWidth * 0.06f)
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
            if (op.type == BrushType.CALLIGRAPHY) {
                // Use stored snapshot; if missing (old data), rebuild and cache it.
                val path = op.calligPath ?: Path(buildCalligraphyPath(op)).also { op.calligPath = it }
                calligPaint.color = op.color
                c.drawPath(path, calligPaint)   // SRC_OVER + WINDING
                continue
            }

            val pts = op.points
            if (pts.size < 2) continue
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                when (op.type) {
                    BrushType.PEN      -> drawPenSegment(c, a.x, a.y, b.x, b.y, op)
                    BrushType.FOUNTAIN -> drawFountainSegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.MARKER   -> drawMarkerSegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.PENCIL   -> drawPencilSegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.CALLIGRAPHY -> {} // handled above
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

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
        var lastDir: Float? = null          // smoothed direction cache (radians)
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

    // Calligraphy nib angle (fixed 45°; tune if you want)
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
        // Always run scale detector (pinch zoom w/ fingers)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isStylus(event, event.actionIndex)) {
                    startStroke(event, event.actionIndex)
                } else {
                    // Finger: no drawing on single-finger down
                    drawing = false
                    activePointerId = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch → pan/zoom mode; never draw
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

        // Append point for committed rebuild later
        op.points.add(Point(x, y))

        val c = scratchCanvas ?: return

        if (op.type == BrushType.CALLIGRAPHY) {
            // Rebuild current calligraphy stroke as *one ribbon path* for smooth edges.
            scratchBitmap?.eraseColor(Color.TRANSPARENT)
            drawCalligraphyStroke(c, op)
        } else {
            // Render only the newest segment on scratch
            when (op.type) {
                BrushType.PEN        -> drawPenSegment(c, sx, sy, x, y, op)
                BrushType.FOUNTAIN   -> drawFountainSegment(c, sx, sy, x, y, op, dist)
                BrushType.MARKER     -> drawMarkerSegment(c, sx, sy, x, y, op, dist)
                BrushType.PENCIL     -> drawPencilSegment(c, sx, sy, x, y, op, dist)
                else -> { /* handled above */ }
            }
        }

        lastX = x
        lastY = y
    }

    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke -> strokes.add(stroke) }
        current = null
        drawing = false
        activePointerId = -1

        // Merge scratch into committed by rebuilding
        rebuildCommitted()
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // ===== Segment renderers (incremental, except calligraphy which is whole-stroke) =====

    private fun newStrokePaint(color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
        }

    private fun newFillPaint(color: Int, alpha: Int = 255): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            this.alpha = alpha.coerceIn(0, 255)
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
        // Core graphite line (a bit darker than before)
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
        val nx = -dy / (len + 1e-4f) // unit-ish normal
        val ny =  dx / (len + 1e-4f)

        val speckPaint = newFillPaint(op.color, 90)
        val radiusMax = max(0.6f, op.baseWidth * 0.12f)   // keep specks close
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

    // ===== Calligraphy (single ribbon path) =====

    // Reused scratch for building the polygon
    private val calligPath = Path()
    private val calligPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    private val tmpLeft = ArrayList<PointF>(1024)
    private val tmpRight = ArrayList<PointF>(1024)

    /** Build and draw the whole calligraphy stroke (used during drawing). */
    private fun drawCalligraphyStroke(canvas: Canvas, op: StrokeOp) {
        if (op.points.size < 2) return
        calligPath.reset()
        tmpLeft.clear()
        tmpRight.clear()

        val pts = op.points
        val nx = -sin(nibAngleRad)
        val ny =  cos(nibAngleRad)

        // direction smoothing (reduce pulsing)
        fun dirAt(i: Int): Float {
            return when (i) {
                0 -> atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
                pts.lastIndex -> atan2(pts[i].y - pts[i - 1].y, pts[i].x - pts[i - 1].x)
                else -> atan2(pts[i + 1].y - pts[i - 1].y, pts[i + 1].x - pts[i - 1].x)
            }
        }
        var dirLPF = dirAt(0)

        for (i in pts.indices) {
            val p = pts[i]
            val rawDir = dirAt(i)
            // low‑pass filter angle to avoid width flutter
            val d = shortestAngleDiff(rawDir, dirLPF)
            dirLPF += d * 0.35f

            val w = calligWidth(op, dirLPF)
            tmpLeft.add(PointF(p.x + nx * (w * 0.5f), p.y + ny * (w * 0.5f)))
            tmpRight.add(PointF(p.x - nx * (w * 0.5f), p.y - ny * (w * 0.5f)))
        }

        // Build closed polygon: left edge forward, right edge back
        calligPath.moveTo(tmpLeft[0].x, tmpLeft[0].y)
        for (i in 1 until tmpLeft.size) calligPath.lineTo(tmpLeft[i].x, tmpLeft[i].y)
        for (i in tmpRight.size - 1 downTo 0) calligPath.lineTo(tmpRight[i].x, tmpRight[i].y)
        calligPath.close()

        calligPaint.color = op.color
        canvas.drawPath(calligPath, calligPaint)

        // Single caps at ends for nicer tips
        val headDir = dirAt(pts.lastIndex)
        val tailDir = dirAt(0)
        val headW = calligWidth(op, headDir)
        val tailW = calligWidth(op, tailDir)
        drawRotatedOval(canvas, pts.first().x, pts.first().y,
            max(1f, tailW), max(1f, op.baseWidth * 0.45f), nibAngleDeg, op.color, 255)
        drawRotatedOval(canvas, pts.last().x,  pts.last().y,
            max(1f, headW), max(1f, op.baseWidth * 0.45f), nibAngleDeg, op.color, 255)
    }

    /** Width function: pencil‑thin when aligned with nib, bold when perpendicular. */
    private fun calligWidth(op: StrokeOp, dir: Float): Float {
        val rel = abs(sin(dir - nibAngleRad))                 // 0..1
        val minW = max(0.4f, op.baseWidth * 0.04f)            // allow true thin lines
        val maxW = max(minW + 1f, op.baseWidth * 1.05f)       // stay slender (no fat look)
        return minW + (maxW - minW) * rel
    }

    private fun shortestAngleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d >  Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    // Draw a filled oval rotated by angleDeg around (cx, cy)
    private fun drawRotatedOval(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        angleDeg: Float,
        color: Int,
        alpha: Int,
        blur: BlurMaskFilter? = null
    ) {
        val paint = newFillPaint(color, alpha).apply { maskFilter = blur }
        val rect = RectF(
            cx - width / 2f, cy - height / 2f,
            cx + width / 2f, cy + height / 2f
        )
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        canvas.drawOval(rect, paint)
        canvas.restore()
    }

    // ===== Rebuild committed layer from the stroke list =====

    private fun rebuildCommitted() {
        val c = committedCanvas ?: return
        committedBitmap?.eraseColor(Color.TRANSPARENT)

        for (op in strokes) {
            op.stampPhase = 0f
            op.lastDir = null
            val pts = op.points
            if (pts.size < 2) continue

            when (op.type) {
                BrushType.CALLIGRAPHY -> {
                    // Whole-stroke ribbon (same as while drawing)
                    drawCalligraphyStroke(c, op)
                }
                else -> {
                    // Replay segment-by-segment for the others
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]
                        val b = pts[i]
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        when (op.type) {
                            BrushType.PEN       -> drawPenSegment(c, a.x, a.y, b.x, b.y, op)
                            BrushType.FOUNTAIN  -> drawFountainSegment(c, a.x, a.y, b.x, b.y, op, dist)
                            BrushType.MARKER    -> drawMarkerSegment(c, a.x, a.y, b.x, b.y, op, dist)
                            BrushType.PENCIL    -> drawPencilSegment(c, a.x, a.y, b.x, b.y, op, dist)
                            BrushType.CALLIGRAPHY -> {} // handled above
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

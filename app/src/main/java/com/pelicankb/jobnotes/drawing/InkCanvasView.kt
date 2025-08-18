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

    fun setBrush(type: BrushType) {
        baseBrush = type
    }

    /** Brush size in dp (will be converted to px internally) */
    fun setStrokeWidthDp(sizeDp: Float) {
        baseWidthPx = dpToPx(sizeDp)
    }

    fun setColor(color: Int) {
        baseColor = color
    }

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
        // For calligraphy/marker/pencil stamping spacing continuity:
        var stampPhase: Float = 0f
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

    // Calligraphy nib angle (fixed). Adjust if you want.
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
        rebuildCommitted() // redraw all strokes to the new committed bitmap
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
        // always run scale detector (for finger pinch zoom)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isStylus(event, event.actionIndex)) {
                    startStroke(event, event.actionIndex)
                } else {
                    // finger down alone: do not start drawing
                    drawing = false
                    activePointerId = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // multi-touch → pan/zoom mode; never draw
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
                    // two-finger pan
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

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishStroke()
            }
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
        ).also { it.points.add(Point(x, y)) }

        // reset scratch
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

        // append point for committed rebuild later
        op.points.add(Point(x, y))

        // render just the new segment on the scratch layer
        val c = scratchCanvas ?: return
        when (op.type) {
            BrushType.PEN -> drawPenSegment(c, sx, sy, x, y, op)
            BrushType.FOUNTAIN -> drawFountainSegment(c, sx, sy, x, y, op, dist)
            BrushType.CALLIGRAPHY -> drawCalligraphySegment(c, sx, sy, x, y, op, dist)
            BrushType.MARKER -> drawMarkerSegment(c, sx, sy, x, y, op, dist)
            BrushType.PENCIL -> drawPencilSegment(c, sx, sy, x, y, op, dist)
        }

        lastX = x
        lastY = y
    }

    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke ->
            strokes.add(stroke)
        }
        current = null
        drawing = false
        activePointerId = -1

        // Merge scratch into committed by rebuilding (keeps undo/redo simple and consistent)
        rebuildCommitted()
        // Clear scratch
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // ===== Segment renderers (incremental, no re-draw of old segments) =====

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
        // Slight width boost with speed; crisp
        val speed = dist
        val width = op.baseWidth + min(op.baseWidth * 0.55f, speed * 0.05f)
        val p = newStrokePaint(op.color, width)
        canvas.drawLine(x0, y0, x1, y1, p)
    }

    private fun drawCalligraphySegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, dist: Float
    ) {
        // Chisel nib via rotated oval stamps at fixed nib angle.
        // Thickness varies with direction: |sin(direction - nibAngle)|.
        val dx = x1 - x0
        val dy = y1 - y0
        val dir = atan2(dy, dx)
        val rel = abs(sin(dir - nibAngleRad)) // 0..1
        val minW = op.baseWidth * 0.12f   // allow true pencil-thin
        val maxW = op.baseWidth * 2.2f    // bold
        val w = minW + (maxW - minW) * rel
        val h = max(1f, op.baseWidth * 0.42f) // chisel length (along nib axis)

        val spacing = max(0.75f, op.baseWidth * 0.30f)
        var t = op.stampPhase
        while (t < dist) {
            val px = x0 + dx * (t / dist)
            val py = y0 + dy * (t / dist)
            drawRotatedOval(canvas, px, py, w, h, nibAngleDeg, op.color, 255)
            t += spacing
        }
        // carry remainder so stamping is uniform across segments
        op.stampPhase = t - dist
    }

    private fun drawMarkerSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, dist: Float
    ) {
        // Heavier deposit + soft edges.
        val spacing = max(0.6f, op.baseWidth * 0.22f)
        val dx = x1 - x0
        val dy = y1 - y0

        val baseW = op.baseWidth * 1.35f
        val h = max(1f, op.baseWidth * 0.5f)
        val blur = BlurMaskFilter(op.baseWidth * 0.20f, BlurMaskFilter.Blur.NORMAL)

        var t = op.stampPhase
        while (t < dist) {
            val px = x0 + dx * (t / dist)
            val py = y0 + dy * (t / dist)
            // Slight speed boost to width (more ink at speed)
            val w = baseW + min(op.baseWidth * 0.35f, (dist) * 0.03f)
            drawRotatedOval(canvas, px, py, w, h, nibAngleDeg, op.color, 255, blur)
            t += spacing
        }
        op.stampPhase = t - dist
    }

    private fun drawPencilSegment(
        canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, op: StrokeOp, dist: Float
    ) {
        // Core graphite line
        val core = newStrokePaint(op.color, max(1f, op.baseWidth * 0.85f)).apply {
            alpha = 225
            // slight softness
            maskFilter = BlurMaskFilter(max(0.5f, op.baseWidth * 0.10f), BlurMaskFilter.Blur.NORMAL)
        }
        val p = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        canvas.drawPath(p, core)

        // Tight, near-line speckles (not exploding out)
        val spacing = max(0.8f, op.baseWidth * 0.30f)
        val dx = x1 - x0
        val dy = y1 - y0
        val len = dist
        val nx = -dy / (len + 1e-4f) // unit normal (approx)
        val ny = dx / (len + 1e-4f)

        val speckPaint = newFillPaint(op.color, 80) // light specks
        val radiusMax = max(0.6f, op.baseWidth * 0.12f) // keep close to the line
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

        // local helper using same incremental logic as during scratch draw
        fun replayStroke(op: StrokeOp) {
            op.stampPhase = 0f
            val pts = op.points
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                when (op.type) {
                    BrushType.PEN -> drawPenSegment(c, a.x, a.y, b.x, b.y, op)
                    BrushType.FOUNTAIN -> drawFountainSegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.CALLIGRAPHY -> drawCalligraphySegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.MARKER -> drawMarkerSegment(c, a.x, a.y, b.x, b.y, op, dist)
                    BrushType.PENCIL -> drawPencilSegment(c, a.x, a.y, b.x, b.y, op, dist)
                }
            }
        }

        for (op in strokes) replayStroke(op)
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

            // zoom around gesture focus
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
            // entering pinch: stop any drawing
            drawing = false
            activePointerId = -1
            return true
        }
    }
}

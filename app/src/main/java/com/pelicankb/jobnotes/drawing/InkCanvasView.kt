package com.pelicankb.jobnotes.drawing

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context

import android.graphics.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.os.SystemClock
import android.graphics.drawable.Drawable


import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.core.graphics.withSave
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

import kotlin.math.*
import kotlin.random.Random
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max



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
    // ---- Shape Snap (Samsung Notes-style) ----
    private var shapeSnapEnabled: Boolean = true
    private var shapeSnapForPenFamilyOnly: Boolean = true   // only Pen/Pencil/Marker; not Highlighter/Eraser

    // Hold detection at stroke end
    private val SNAP_HOLD_MS = 350L
    private val STILL_MOVE_EPS = 10f.dp()       // allow more hand shake while holding
    private val CLOSE_EPS = 20f.dp()            // easier to recognize “closed” shapes
    private val LINE_MAX_DEVIATION = 14f.dp()   // more wobble allowed for straight lines
    private val ARC_MAX_DEVIATION = 10f.dp()
    private val CIRCLE_RAD_DEV_PCT = 0.12f
    private val RIGHT_ANGLE_EPS_DEG = 32f       // rectangles tolerate bigger angle error



    // Stroke buffer for current draw
    private val currStrokePts = ArrayList<PointF>(256)
    // Hold-at-end state
    private var holdActive = false
    private var holdStartTime = 0L
    private var lastHoldX = 0f
    private var lastHoldY = 0f
    private val shapeSnapRunnable = Runnable { tryShapeSnapIfEligible() }
    // Snap runtime state
    private enum class SnapKind { NONE, LINE, ARC, CIRCLE, RECT }
    private var snapKind: SnapKind = SnapKind.NONE
    private var snapApplied = false          // preview currently showing a snapped shape
    private var snapAborted = false          // user held longer to revert preview
    private var snapPts: MutableList<PointF>? = null  // vector points to commit on lift

    // Extra hold to REVERT the snap preview (return to freehand)
    private val SNAP_REVERT_EXTRA_MS = 500L


    // quick px<->dp helpers
    private fun Float.dp(): Float = this * resources.displayMetrics.density
    private fun Int.dp(): Float = this * resources.displayMetrics.density

    // Debug toggles (safe to ship; disabled by default)
    private val TAG = "InkCanvasView"
    private val DEBUG_INPUT = false

    // Pretty-print MotionEvent actions for logs
    private fun actionToString(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"

        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        else -> "OTHER($action)"
    }




    // ===== Public API =====

    fun setBrush(type: BrushType) { baseBrush = type }
    fun setStrokeWidthDp(sizeDp: Float) { baseWidthPx = dpToPx(sizeDp) }
    fun setColor(color: Int) { baseColor = color }
    /** Eraser option (applies to both eraser modes). Can be toggled mid-stroke. */
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
        for (i in sections.indices) {
            committedInkBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
            committedHLBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
            scratchInkBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
            scratchHLBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
        }
        clearSelection()
        invalidate()
    }


    // ---- Selection API exposed to Activity ----
    enum class SelectionPolicy { STROKE_WISE, REGION_INSIDE }
    // Shapes that can be inserted from the toolbar
    enum class ShapeKind { RECT, TRI_EQ, TRI_RIGHT, CIRCLE, ARC, LINE }


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
        hideSelectionWidthPopup()

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

        // Source bbox of what’s on the clipboard (doc-space)
        val baseBox = clipboardBounds ?: RectF(0f, 0f, 0f, 0f)

        // 1) Make translated copies (do not set sectionIndex here)
        val copies = clipboard.map { copy ->
            val c = deepCopy(copy)
            translateStroke(c, dx, dy)
            c
        }

        // 2) Add + select + compute selection bbox
        strokes.addAll(copies)
        selectedStrokes.addAll(copies)
        selectedBounds = RectF(baseBox).apply { offset(dx, dy) }
        selectionInteractive = true
        overlayActive = true

        // 3) Retag to the page(s) where they actually landed
        rehomeSelectionToCorrectSections()

        // 4) Paint
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

    fun insertShape(
        kind: ShapeKind,
        approxSizePx: Float,
        strokeWidthPx: Float,
        strokeColor: Int,
        fillColor: Int?
    ) {
        // place at view center -> content coords
        val cxView = width * 0.5f
        val cyView = height * 0.5f
        val (cx, cy) = toContent(cxView, cyView)

        val half = approxSizePx * 0.5f / max(1f, scaleFactor)

        // build the polyline that represents the shape
        val pts: List<PointF> = when (kind) {
            ShapeKind.RECT -> listOf(
                PointF(cx - half, cy - half),
                PointF(cx + half, cy - half),
                PointF(cx + half, cy + half),
                PointF(cx - half, cy + half),
                PointF(cx - half, cy - half)
            )
            ShapeKind.TRI_EQ -> {
                val h = (sqrt(3.0) * half).toFloat()
                listOf(
                    PointF(cx,        cy - h),
                    PointF(cx - half, cy + half),
                    PointF(cx + half, cy + half),
                    PointF(cx,        cy - h)
                )
            }
            ShapeKind.TRI_RIGHT -> listOf(
                PointF(cx - half, cy - half),
                PointF(cx + half, cy - half),
                PointF(cx - half, cy + half),
                PointF(cx - half, cy - half)
            )
            ShapeKind.CIRCLE -> {
                val n = 64
                (0..n).map {
                    val ang = (2f * Math.PI.toFloat() * it / n)
                    PointF(cx + half * cos(ang), cy + half * sin(ang))
                }
            }
            ShapeKind.ARC -> {
                // 180° arc, 48 samples (flat on top)
                val n = 48
                (0..n).map {
                    val ang = (Math.PI.toFloat() * it / n) - (Math.PI.toFloat()/2f)
                    PointF(cx + half * cos(ang), cy + half * sin(ang))
                }
            }
            ShapeKind.LINE -> listOf(PointF(cx - half, cy), PointF(cx + half, cy))
        }

        val secIdx = sectionIndexForContentY(cy)
        val op = StrokeOp(
            type = BrushType.PEN,
            color = strokeColor,
            baseWidth = strokeWidthPx
        ).also {
            it.sectionIndex = secIdx
            it.points.clear()
            pts.forEach { p -> it.points.add(Point(p.x, p.y)) }
            it.shapeKind = kind
            it.shapeFillColor = fillColor
        }

        // add & select immediately
        strokes.add(op)
        selectedStrokes.clear()
        selectedStrokes.add(op)
        selectedBounds = strokeBounds(op)
        selectionInteractive = true
        overlayActive = true

        rebuildCommitted()
        invalidate()
    }

    // ADD/REPLACE — unified updater: works for newer selectedStrokes AND legacy selected
    fun updateSelectedStrokeWidthDp(dp: Float) {
        val px = dpToPx(dp)
        var changed = false

        // Newer selection set
        if (try {
                val f = this::class.java.getDeclaredField("selectedStrokes")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        val w = s.javaClass.getDeclaredField("baseWidth")
                        w.isAccessible = true
                        w.setFloat(s, px)
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        // Legacy selection set
        if (try {
                val f = this::class.java.getDeclaredField("selected")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        val w = s.javaClass.getDeclaredField("baseWidth")
                        w.isAccessible = true
                        w.setFloat(s, px)
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        if (changed) { rebuildCommitted(); invalidate() }
    }
    /**
     * Relative width change for the current selection.
     * Adds/subtracts a fixed delta in DP to each selected stroke's width, preserving differences.
     * Returns true if any stroke changed.
     */
    fun updateSelectedStrokeWidthDeltaDp(
        deltaDp: Float,
        minDp: Float = 0.5f,
        maxDp: Float = 120f
    ): Boolean {
        if (selectedStrokes.isEmpty()) return false

        val density = resources.displayMetrics.density
        var changed = false

        for (s in selectedStrokes) {
            // Current width in dp (baseWidth is px)
            val curDp = (s.baseWidth / density)
            val newDp = (curDp + deltaDp).coerceIn(minDp, maxDp)
            val newPx = newDp * density
            if (newPx != s.baseWidth) {
                s.baseWidth = newPx
                changed = true
            }
        }

        if (changed) {
            rebuildCommitted()
            invalidate()
        }
        return changed
    }

    /**
     * Proportional (percent) width change for the current selection.
     * Multiplies each selected stroke's width by [scale], preserving differences
     * (e.g., 1.10f => +10%, 0.90f => -10%).
     * Returns true if any stroke changed.
     */
    fun updateSelectedStrokeWidthScale(
        scale: Float,
        minDp: Float = 0.5f,
        maxDp: Float = 120f
    ): Boolean {
        if (selectedStrokes.isEmpty()) return false
        // Treat near-1.0 as no-op to avoid micro jitter
        if (kotlin.math.abs(scale - 1f) < 0.0005f) return false

        val density = resources.displayMetrics.density
        var changed = false

        for (s in selectedStrokes) {
            val curDp = (s.baseWidth / density)
            val newDp = (curDp * scale).coerceIn(minDp, maxDp)
            val newPx = newDp * density
            if (newPx != s.baseWidth) {
                s.baseWidth = newPx
                changed = true
            }
        }

        if (changed) {
            rebuildCommitted()
            invalidate()
        }
        return changed
    }


    // ADD/REPLACE — unified updater (new + legacy)
    fun updateSelectedStrokeColor(color: Int) {
        var changed = false

        if (try {
                val f = this::class.java.getDeclaredField("selectedStrokes")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        val c = s.javaClass.getDeclaredField("color")
                        c.isAccessible = true
                        c.setInt(s, color)
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        if (try {
                val f = this::class.java.getDeclaredField("selected")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        val c = s.javaClass.getDeclaredField("color")
                        c.isAccessible = true
                        c.setInt(s, color)
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        if (changed) { rebuildCommitted(); invalidate() }
    }

    // ADD/REPLACE — unified updater (new + legacy); freehand will ignore fill
    fun updateSelectedShapeFill(colorOrNull: Int?) {
        var changed = false

        if (try {
                val f = this::class.java.getDeclaredField("selectedStrokes")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        runCatching {
                            val fld = s.javaClass.getDeclaredField("shapeFillColor")
                            fld.isAccessible = true
                            fld.set(s, colorOrNull)
                        }
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        if (try {
                val f = this::class.java.getDeclaredField("selected")
                f.isAccessible = true
                val set = f.get(this) as? MutableSet<Any>
                if (!set.isNullOrEmpty()) {
                    for (s in set) {
                        runCatching {
                            val fld = s.javaClass.getDeclaredField("shapeFillColor")
                            fld.isAccessible = true
                            fld.set(s, colorOrNull)
                        }
                    }
                    true
                } else false
            } catch (_: Throwable) { false }
        ) changed = true

        if (changed) { rebuildCommitted(); invalidate() }
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
        // v2 adds: sections (heights) + per-stroke sectionIndex
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        out.writeInt(2) // version = 2

        // --- sections ---
        out.writeInt(sections.size)
        for (s in sections) {
            out.writeFloat(s.heightPx)
        }

        // --- strokes ---
        out.writeInt(strokes.size)
        for (op in strokes) {
            out.writeInt(op.type.ordinal)
            out.writeInt(op.color)
            out.writeFloat(op.baseWidth)
            out.writeBoolean(op.eraseHLOnly)
            out.writeInt(op.sectionIndex)                // NEW in v2
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

            // --- sections ---
            sections.clear()
            if (ver >= 2) {
                val secCount = `in`.readInt().coerceAtLeast(0)
                repeat(secCount) {
                    val h = `in`.readFloat().coerceAtLeast(1f)
                    sections.add(Section(heightPx = h, yOffsetPx = 0f))
                }
            } else {
                // v1 payloads had no sections; use a single page at current view height (fallback)
                val h = if (height > 0) height.toFloat() else 1f
                sections.add(Section(heightPx = h, yOffsetPx = 0f))
            }

            // repack offsets (top-to-bottom with gap)
            var acc = 0f
            for (i in sections.indices) {
                sections[i].yOffsetPx = acc
                acc += sections[i].heightPx + sectionGapPx
            }

            // --- strokes ---
            strokes.clear()
            val n = `in`.readInt().coerceAtLeast(0)
            repeat(n) {
                val typeOrdinal = `in`.readInt()
                val all = enumValues<BrushType>()
                val type = if (typeOrdinal in all.indices) all[typeOrdinal] else BrushType.PEN

                val color = `in`.readInt()
                val width = `in`.readFloat()
                val hlOnly = `in`.readBoolean()
                val secIndex = if (ver >= 2) `in`.readInt().coerceAtLeast(0) else 0

                val ptsN = `in`.readInt().coerceAtLeast(0)
                val pts = MutableList(ptsN) { Point(`in`.readFloat(), `in`.readFloat()) }

                val op = StrokeOp(type, color, width, pts.toMutableList()).apply {
                    eraseHLOnly = hlOnly
                    sectionIndex = secIndex.coerceIn(0, max(0, sections.lastIndex))
                }
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

            // Allocate bitmaps to match restored sections *if* we already know width,
            // otherwise onSizeChanged() will allocate later.
            if (width > 0) allocateSectionBitmaps(width)

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
        var hidden: Boolean = false,
        var sectionIndex: Int = 0
    ) {
        // shape metadata (null => freehand)
        var shapeKind: ShapeKind? = null
        var shapeFillColor: Int? = null
    }

    private val strokes = mutableListOf<StrokeOp>()
    private val redoStack = mutableListOf<StrokeOp>()
    private var current: StrokeOp? = null

    // ===== “Next stroke” configuration =====
    private var baseBrush: BrushType = BrushType.PEN
    private var baseColor: Int = Color.BLACK
    private var baseWidthPx: Float = dpToPx(4f)
    private var eraserHLOnly: Boolean = false

    // ===== Layers =====
// We now keep separate bitmaps per section (page). Each section has:
// - committed Ink + HL (finalized)
// - scratch Ink + HL (in-progress)
//
// Canvases parallel the bitmap arrays. Index by sectionIndex.
    private val committedInkBySection   = ArrayList<Bitmap?>()
    private val canvasInkBySection      = ArrayList<Canvas?>()
    private val committedHLBySection    = ArrayList<Bitmap?>()
    private val canvasHLBySection       = ArrayList<Canvas?>()
    private val scratchInkBySection     = ArrayList<Bitmap?>()
    private val scratchCanvasInkBySection = ArrayList<Canvas?>()
    private val scratchHLBySection      = ArrayList<Bitmap?>()
    private val scratchCanvasHLBySection  = ArrayList<Canvas?>()
    // ------------------------------------------------------------------------
// Back-compat shims for legacy single-page code paths (SECTION 0 ONLY).
// These let older code continue to compile while we migrate calls to per-page arrays.
// Once all call sites are updated, delete this block.
// ------------------------------------------------------------------------
    private val committedInk: Bitmap?
        get() = committedInkBySection.getOrNull(0)
    private val canvasInk: Canvas?
        get() = canvasInkBySection.getOrNull(0)
    private val committedHL: Bitmap?
        get() = committedHLBySection.getOrNull(0)
    private val canvasHL: Canvas?
        get() = canvasHLBySection.getOrNull(0)
    private val scratchInk: Bitmap?
        get() = scratchInkBySection.getOrNull(0)
    private val scratchCanvasInk: Canvas?
        get() = scratchCanvasInkBySection.getOrNull(0)
    private val scratchHL: Bitmap?
        get() = scratchHLBySection.getOrNull(0)
    private val scratchCanvasHL: Canvas?
        get() = scratchCanvasHLBySection.getOrNull(0)


    // Canvas getters for the active stroke's section
    private fun committedInkCanvas(op: StrokeOp): Canvas? =
        canvasInkBySection.getOrNull(op.sectionIndex)
    private fun committedHLCanvas(op: StrokeOp): Canvas? =
        canvasHLBySection.getOrNull(op.sectionIndex)
    private fun scratchInkCanvas(op: StrokeOp): Canvas? =
        scratchCanvasInkBySection.getOrNull(op.sectionIndex)
    private fun scratchHLScratchCanvas(op: StrokeOp): Canvas? =
        scratchCanvasHLBySection.getOrNull(op.sectionIndex)

    // Utility: (re)allocate all per-section bitmaps to current width and section heights.
    private fun allocateSectionBitmaps(viewW: Int) {
        // Clear previous
        committedInkBySection.clear();   canvasInkBySection.clear()
        committedHLBySection.clear();    canvasHLBySection.clear()
        scratchInkBySection.clear();     scratchCanvasInkBySection.clear()
        scratchHLBySection.clear();      scratchCanvasHLBySection.clear()

        for (s in sections) {
            val h = max(1, s.heightPx.roundToInt())
            fun newBmp() = Bitmap.createBitmap(viewW, h, Bitmap.Config.ARGB_8888)

            val ci = newBmp().also { committedInkBySection.add(it);  canvasInkBySection.add(Canvas(it)) }
            val ch = newBmp().also { committedHLBySection.add(it);   canvasHLBySection.add(Canvas(it)) }
            val si = newBmp().also { scratchInkBySection.add(it);     scratchCanvasInkBySection.add(Canvas(it)) }
            val sh = newBmp().also { scratchHLBySection.add(it);      scratchCanvasHLBySection.add(Canvas(it)) }

            // Start clear
            ci.eraseColor(Color.TRANSPARENT)
            ch.eraseColor(Color.TRANSPARENT)
            si.eraseColor(Color.TRANSPARENT)
            sh.eraseColor(Color.TRANSPARENT)
        }
    }

// ===== Transform (pan/zoom) =====

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var fingerPanning = false

    private var scaleFactor = 1f
    private var translationX = 0f
    private var translationY = 0f
    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f
    // ===== Multi-section "pages" =====
    private data class Section(
        var heightPx: Float,   // page height in CONTENT pixels
        var yOffsetPx: Float   // top offset in CONTENT pixels (from start of document)
    )

    private val sections = ArrayList<Section>()
    private val sectionGapPx get() = dpToPx(16f)  // visual gap between pages

    // Scrollbar overlay (refactored helper)
    private val scrollbar = ScrollbarOverlay()




    // Hand/pan tool: when ON, stylus drag pans instead of drawing
    private var panMode = false
    fun setPanMode(enable: Boolean) { panMode = enable }

    // Policy: if true, only stylus/eraser tools can draw; if false, fingers can draw too
    // Policy: if true, only stylus/eraser tools can draw; if false, fingers can draw too
    private var stylusOnly = true

    fun setStylusOnly(enable: Boolean) { stylusOnly = enable }

    // Track when pinch is in progress to avoid starting strokes
    private var scalingInProgress = false

    // Inertial pan
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val viewConfig = ViewConfiguration.get(context)
    private val minFlingVelocity = viewConfig.scaledMinimumFlingVelocity
    private val maxFlingVelocity = viewConfig.scaledMaximumFlingVelocity

    // Tap-and-hold (temporary hand tool for stylus)
    private var tempPanActive = false
    private var longPressRunnable: Runnable? = null
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    // Input state
    private var drawing = false              // ink/eraser stroke
    private var selectingGesture = false     // currently dragging marquee
    private var transforming = false         // moving/resizing existing selection
    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    // Last dirty area in VIEW coords for partial invalidation
    private var lastDirtyViewRect: Rect? = null


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
    // ---- Selection HUD (width button + popup) ----
// Slightly larger button; we’ll also offset past the handle touch-pad so clicks never collide.
    private var selHudBtnRadiusDp = 16f
    private var selHudBtnRectView: RectF? = null
    private var selWidthPopup: android.widget.PopupWindow? = null
    private var selWidthPopupRectScreen: android.graphics.Rect? = null

    private val rotateIcon: Drawable? by lazy { ContextCompat.getDrawable(context, com.pelicankb.jobnotes.R.drawable.ic_rotate) }




    private enum class Handle { NONE, INSIDE, N, S, E, W, NE, NW, SE, SW, ROTATE }
    private enum class TransformKind { TRANSLATE, SCALE_X, SCALE_Y, SCALE_UNIFORM, ROTATE }


    private var transformKind: TransformKind? = null
    private var transformAnchorX = 0f
    private var transformAnchorY = 0f
    private var downX = 0f
    private var downY = 0f
    private var startBounds = RectF()
    private val savedPoints: MutableMap<StrokeOp, List<Point>> = HashMap()
    private val savedBaseWidth: MutableMap<StrokeOp, Float> = HashMap()
    // Rotation overlay state (rotate selection box during rotation gesture)
    private var overlayRotateAngleRad: Float = 0f
    private var overlayRotateActive: Boolean = false
    private var overlayStartBounds = RectF()
    // Transform overlay snapshot (bitmap) and matrix
    private var overlayBmp: Bitmap? = null
    private var overlayBmpCanvas: Canvas? = null
    private var overlayMatrix = Matrix()
    private var overlayStartTopLeft = PointF() // selection bbox top-left at drag start



    private val handleSizeDp = 14f
    private val handleTouchPadDp = 18f
    // Bottom "pull to add" affordance
    private var pullDragActive = false
    private var pullDragStartY = 0f
    private var pullDragDistance = 0f
    private val pullThresholdPx get() = dpToPx(96f)
    // Viewport-relative thresholds (ratios of screen height)
    private val pullStartRatio = 0.20f   // HUD begins at 20% pulled up
    private val pullCommitRatio = 0.40f  // Page commits at 40% pulled up

    // HUD state (replaces old pullDrag* fields for the new flow)
    private var pullHudVisible = false
    private var pullHudProgress = 0f     // 0..1
    private var pullCommittedThisDrag = false
    // ---- Rotation handle (above top-middle) ----
    private var rotateHandleOffsetDp = 28f
    private var rotateSnapDeg = 90f

    private var rotateStartAngleRad: Float? = null
    private var rotateCenterX = 0f
    private var rotateCenterY = 0f
    private var rotateSnapTolDeg = 8f





    // --- Paste "ghost" preview ---
    private var pasteArmed = false
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

    // Transform-time overlay
    private var overlayActive = false

    // ---- Page edge fades (1×) ----
    private var fadeW = dpToPx(12f)
    private var fadeH = dpToPx(8f)
    private val leftEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Page shadow (>1x) ----
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        alpha = 46 // ~18% opacity
    }
    // --- Pull-to-add UI paints ---
    private val pullCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        color = 0x66000000.toInt()
    }
    private val pullProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2196F3.toInt()
    }
    private val pullArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2196F3.toInt()
    }
    private val pullTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dpToPx(12f)
    }

    private fun drawPullToAddIndicator(canvas: Canvas) {
        if (!pullHudVisible) return

        // Center the HUD horizontally, and place it just UNDER the bottom edge of the last page.
        // We keep it tied to the document bottom so it follows as you pull the page upward.
        val docBottomViewY = translationY + contentHeightPx() * scaleFactor
        val cx = width * 0.5f
        val cy = docBottomViewY + dpToPx(36f)   // 36dp below the page edge for clear separation

        // 3× larger radius vs old (14dp -> 42dp)
        val r  = dpToPx(42f)

        // Heavier strokes so the larger HUD feels balanced
        pullCirclePaint.strokeWidth   = dpToPx(4f)
        pullProgressPaint.strokeWidth = dpToPx(6f)
        pullArrowPaint.strokeWidth    = dpToPx(4f)
        pullTextPaint.textSize        = dpToPx(18f)

        // Base circle
        canvas.drawCircle(cx, cy, r, pullCirclePaint)

        // Progress arc (starts at top, sweeps clockwise)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val sweep = 360f * pullHudProgress.coerceIn(0f, 1f)
        canvas.drawArc(oval, -90f, sweep, false, pullProgressPaint)

        // Up arrow inside the circle (scaled by radius)
        val stemTopY = cy - r * 0.45f
        val stemBotY = cy + r * 0.20f
        canvas.drawLine(cx, stemBotY, cx, stemTopY, pullArrowPaint)
        canvas.drawLine(cx, stemTopY, cx - r * 0.22f, stemTopY + r * 0.22f, pullArrowPaint)
        canvas.drawLine(cx, stemTopY, cx + r * 0.22f, stemTopY + r * 0.22f, pullArrowPaint)

        // Label centered under the circle
        canvas.drawText("Add new page", cx, cy + r + dpToPx(18f), pullTextPaint)
    }



    private val pageWorkRect = RectF()


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

        // If we have restored sections from deserialize(), keep their heights.
// Otherwise, bootstrap a single section sized to the view.
        if (sections.isEmpty()) {
            sections.add(Section(heightPx = h.toFloat(), yOffsetPx = 0f))
        }

// Re-pack offsets (top-to-bottom with gaps) using current section heights
        var acc = 0f
        for (i in sections.indices) {
            sections[i].yOffsetPx = acc
            acc += sections[i].heightPx + sectionGapPx
        }

// Allocate per-section bitmaps at the new width
        allocateSectionBitmaps(w)


        // Edge fades
        fadeW = dpToPx(12f)
        fadeH = dpToPx(8f)
        val dark = 0x22000000.toInt()
        val clear = 0x00000000
        leftEdgePaint.shader  = LinearGradient(0f, 0f, fadeW, 0f, dark, clear, Shader.TileMode.CLAMP)
        rightEdgePaint.shader = LinearGradient(w - fadeW, 0f, w.toFloat(), 0f, clear, dark, Shader.TileMode.CLAMP)
        topEdgePaint.shader   = LinearGradient(0f, 0f, 0f, fadeH, dark, clear, Shader.TileMode.CLAMP)
        bottomEdgePaint.shader= LinearGradient(0f, h - fadeH, 0f, h.toFloat(), clear, dark, Shader.TileMode.CLAMP)

        rebuildCommitted() // repaint committed from stroke list
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) CONTENT space: draw each section background + that section's committed/scratch layers
        canvas.save()
        canvas.translate(translationX, translationY)

        for (i in sections.indices) {
            val s = sections[i]

            canvas.save()
            canvas.translate(0f, s.yOffsetPx * scaleFactor)
            canvas.scale(scaleFactor, scaleFactor)

            // Page shadow (when zoomed in)
            if (scaleFactor > 1.02f) {
                pageWorkRect.set(0f, 0f, width.toFloat(), s.heightPx)
                val inflate = dpToPx(6f) / scaleFactor
                pageWorkRect.inset(-inflate, -inflate)
                canvas.drawRoundRect(
                    pageWorkRect,
                    dpToPx(4f) / scaleFactor,
                    dpToPx(4f) / scaleFactor,
                    pageShadowPaint
                )
            }

            // White page background for this section
            canvas.drawRect(0f, 0f, width.toFloat(), s.heightPx, pagePaint)

            // This section's layers (committed first, then scratch)
            committedHLBySection.getOrNull(i)?.let  { canvas.drawBitmap(it, 0f, 0f, null) }
            scratchHLBySection.getOrNull(i)?.let    { canvas.drawBitmap(it, 0f, 0f, null) }
            committedInkBySection.getOrNull(i)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            scratchInkBySection.getOrNull(i)?.let   { canvas.drawBitmap(it, 0f, 0f, null) }

            canvas.restore()
        }
        canvas.restore()

        // 2) Overlays in section-0 content coords (marquee, selection, paste ghost)
        canvas.save()
        canvas.translate(translationX, translationY)
        canvas.scale(scaleFactor, scaleFactor)

        if (pasteArmed && pastePreviewVisible && clipboard.isNotEmpty()) {
            drawClipboardGhost(canvas, pastePreviewCx, pastePreviewCy)
        }
        if (overlayActive && selectedStrokes.isNotEmpty()) {
            if (overlayBmp != null) {
                canvas.save()
                canvas.concat(overlayMatrix)
                // draw the cached selection bitmap at its original top-left
                canvas.drawBitmap(overlayBmp!!, overlayStartTopLeft.x, overlayStartTopLeft.y, null)
                canvas.restore()
                // draw current selection box & handles using selectedBounds
                selectedBounds?.let { r ->
                    // drawSelectionHighlights(canvas) // (disabled for perf during live transform)
                    canvas.drawRect(r, selectionOutline)
                    if (selectionInteractive) drawHandles(canvas, r)
                }

            } else {
                // fallback (shouldn't happen during an active transform)
                drawSelectionOverlay(canvas)
            }
        }

        marqueePath?.let { path ->
            canvas.drawPath(path, marqueeFill)
            canvas.drawPath(path, marqueeOutline)
        }
        if (selectedStrokes.isNotEmpty()) {
            // During rotation gesture, draw rotated box/handles around the original bounds.
            if (overlayRotateActive && overlayStartBounds.width() > 0f && overlayStartBounds.height() > 0f) {
                drawRotatedSelectionBox(canvas, overlayStartBounds, overlayRotateAngleRad)
                // Rotate-handle should also appear aligned over the rotated box: draw using rotated top-mid.
                // We'll draw it relative to rotated canvas for consistency:
                canvas.save()
                val cx = overlayStartBounds.centerX()
                val cy = overlayStartBounds.centerY()
                canvas.rotate(Math.toDegrees(overlayRotateAngleRad.toDouble()).toFloat(), cx, cy)
                drawRotateHandle(canvas, overlayStartBounds)
                canvas.restore()
            } else {
                // Normal (not rotating): axis-aligned visuals using current bounds
                drawSelectionHighlights(canvas)
                selectedBounds?.let { r ->
                    canvas.drawRect(r, selectionOutline)
                    if (selectionInteractive) {
                        drawHandles(canvas, r)
                    }
                    drawRotateHandle(canvas, r)
                }
            }
        }


        // Selection width HUD button disabled: no drawing, no hit target
        selHudBtnRectView = null

        canvas.restore()

        // 3) Page edge fades at 1× (view space)
        if (kotlin.math.abs(scaleFactor - 1f) < 1e-3f) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, fadeW, h, leftEdgePaint)
            canvas.drawRect(w - fadeW, 0f, w, h, rightEdgePaint)
            canvas.drawRect(0f, 0f, w, fadeH, topEdgePaint)
            canvas.drawRect(0f, h - fadeH, w, h, bottomEdgePaint)
        }

        // 4) Scrollbar overlay (view space)
        run {
            val density = resources.displayMetrics.density
            val contentHViewPx = contentHeightPx() * scaleFactor
            scrollbar.draw(canvas, width, height, density, scaleFactor, translationY, contentHViewPx)
        }

        // 5) Bottom pull-to-add affordance (view space) — up arrow with circular progress + label
        drawPullToAddIndicator(canvas)
    }






    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // Keep flings running
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            translationX = scroller.currX.toFloat()
            translationY = scroller.currY.toFloat()
            clampPan() // also enforces 1× horizontal lock
            postInvalidateOnAnimation()
        }
    }

    // ===== Input =====

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ensure the View background itself is not the page — we draw the page in onDraw.
        setBackgroundColor(Color.TRANSPARENT)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (DEBUG_INPUT) {
            val idx = event.actionIndex.coerceAtLeast(0).coerceAtMost(event.pointerCount - 1)
            val toolType = if (idx in 0 until event.pointerCount) event.getToolType(idx) else MotionEvent.TOOL_TYPE_UNKNOWN
            val toolName = when (toolType) {
                MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
                MotionEvent.TOOL_TYPE_ERASER -> "STYLUS_ERASER"
                MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
                MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
                MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
                else -> "OTHER($toolType)"
            }
            Log.d(
                TAG,
                "onTouch ${actionToString(event.actionMasked)} by $toolName " +
                        "pCount=${event.pointerCount} scale=$scaleFactor tx=$translationX ty=$translationY"
            )
        }

        // Always feed detectors
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // Scrollbar overlay interaction
        val density = resources.displayMetrics.density
        val contentHViewPx = contentHeightPx() * scaleFactor
        val consumedByScrollbar = scrollbar.onTouchEvent(
            event,
            width, height, density,
            scaleFactor, translationY, contentHViewPx,
            setTranslationY = { newTy -> translationY = newTy },
            invalidate = { invalidate() }
        )
        if (consumedByScrollbar) return true




        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // If the selection width popup is open and the tap lands inside it,
// consume the event so the canvas does not draw/deselect beneath it.
                selWidthPopupRectScreen?.let { r ->
                    val rx = event.rawX.toInt()
                    val ry = event.rawY.toInt()
                    if (selWidthPopup?.isShowing == true && r.contains(rx, ry)) {
                        return true
                    }
                }

                // (removed) old bottom-line tap gate; new flow is viewport-relative while panning




                stopFling()
                // Reset HUD state for this drag
                pullHudVisible = false
                pullHudProgress = 0f
                pullCommittedThisDrag = false

                // Pointer index for this DOWN event (shared by this case)
                val idx = event.actionIndex

                // idx already defined earlier in ACTION_DOWN


                // Single-finger (not stylus): pan or move selection; never draw
                if (!isStylus(event, idx) && event.pointerCount == 1 && !scalingInProgress) {
                    val (cx, cy) = toContent(event.getX(idx), event.getY(idx))

                    // HUD button disabled — no priority tap handling


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
                    obtainTracker()
                    velocityTracker?.addMovement(event)
                    // If a selection existed and finger tapped on blank area, clear selection now
                    run {
                        if (selectedStrokes.isNotEmpty()) {
                            // Ignore taps inside the width popup
                            selWidthPopupRectScreen?.let { r ->
                                if (selWidthPopup?.isShowing == true) {
                                    val rx = event.rawX.toInt()
                                    val ry = event.rawY.toInt()
                                    if (r.contains(rx, ry)) return@run
                                }
                            }
                            val (cx, cy) = toContent(lastPanFocusX, lastPanFocusY)
                            val anyHit = hitAnyStrokeAt(cx, cy, dpToPx(10f)) != null
                            val insideSel = selectedBounds?.contains(cx, cy) == true
                            val onHandle = detectHandle(cx, cy) != Handle.NONE
                            if (!anyHit && !insideSel && !onHandle) {
                                clearSelection()
                            }
                        }
                    }


                    return true
                }

                // Stylus or other case
                val canDrawNow = acceptsPointer(event, idx) &&
                        event.pointerCount == 1 &&
                        !scalingInProgress

                // (migrated) Pull-to-add handled earlier for finger before panning begins.


                if (canDrawNow) {
                    // Tap-and-hold (stylus) -> temporary pan
                    if (!panMode && isStylus(event, idx)) {
                        scheduleStylusHoldToPan(event, idx)
                    }

                    // Stylus hand/pan tool (or hold-to-pan when it triggers)
                    if (panMode || tempPanActive) {
                        activePointerId = event.getPointerId(idx)
                        lastPanFocusX = event.getX(idx)
                        lastPanFocusY = event.getY(idx)
                        drawing = false
                        selectingGesture = false
                        transforming = false
                        obtainTracker()
                        velocityTracker?.addMovement(event)
                        return true
                    }

                    val (cx, cy) = toContent(event.getX(idx), event.getY(idx))

                    // Tap to place paste; then translate immediately
                    if (pasteArmed && clipboard.isNotEmpty()) {
                        performPasteAt(cx, cy)
                        pasteArmed = false
                        cancelStylusHoldToPan()
                        beginTransform(Handle.INSIDE, cx, cy)
                        transforming = true
                        activePointerId = event.getPointerId(idx)
                        return true
                    }

                    // HUD button disabled — no priority tap handling


                    // Transform selection (handles first, then inside; otherwise maybe clear)
                    if (selectedStrokes.isNotEmpty() && selectionInteractive) {
                        val h = detectHandle(cx, cy)
                        if (h != Handle.NONE) {
                            cancelStylusHoldToPan()
                            beginTransform(h, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        }
                        val inside = selectedBounds?.contains(cx, cy) == true
                        if (inside) {
                            cancelStylusHoldToPan()
                            beginTransform(Handle.INSIDE, cx, cy)
                            transforming = true
                            activePointerId = event.getPointerId(idx)
                            return true
                        } else if (selectionTool != SelTool.NONE && selectionSticky) {
                            clearSelection()
                        }
                    }

                    // HUD button disabled — no selection HUD handling

                    // If popup is open and user taps inside selection, close it
                    run {
                        if (selWidthPopup?.isShowing == true && selectedStrokes.isNotEmpty()) {
                            val (cx, cy) = toContent(event.getX(idx), event.getY(idx))
                            val insideSel = selectedBounds?.contains(cx, cy) == true
                            if (insideSel) {
                                hideSelectionWidthPopup()
                                return true
                            }
                        }
                    }


                    // If a selection exists and the stylus taps outside it (and not on a handle), clear the selection
                    if (selectedStrokes.isNotEmpty() && selectionInteractive) {
                        val inside = selectedBounds?.contains(cx, cy) == true
                        val onHandle = detectHandle(cx, cy) != Handle.NONE
                        if (!inside && !onHandle) {
                            clearSelection()
                        }
                    }

                    // Either start selection marquee or a stroke
                    if (selectionTool != SelTool.NONE) {
                        cancelStylusHoldToPan()
                        startSelection(event, idx)
                    } else {
                        cancelStylusHoldToPan()
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
                // Any second pointer cancels a pending paste and temporary pan
                if (event.pointerCount >= 2 && pasteArmed) pasteArmed = false
                cancelStylusHoldToPan()
                stopFling()

                // Cancel one-pointer modes
                drawing = false
                selectingGesture = false
                transforming = false
                activePointerId = -1
                fingerPanning = false
                endTracker()

                // Two-finger pan baseline
                lastPanFocusX = averageX(event)
                lastPanFocusY = averageY(event)
            }

            MotionEvent.ACTION_MOVE -> {
                // (removed) old drag-distance path; new HUD/progress derived from doc-bottom offset




                velocityTracker?.addMovement(event)
                when {
                    // 1-finger finger pan
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
                            if (abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
                            // --- HUD/progress for bottom-page pull (finger) ---
                            if (event.pointerCount == 1) {
                                val screenH = height.toFloat()
                                val startPx = pullStartRatio * screenH
                                val commitPx = pullCommitRatio * screenH
                                val docBottomViewY = translationY + contentHeightPx() * scaleFactor
                                val pulledUpPx = (screenH - docBottomViewY) // ≥0 when bottom above screen bottom

                                if (pulledUpPx >= startPx) {
                                    pullHudVisible = true
                                    pullHudProgress = ((pulledUpPx - startPx) / max(1f, commitPx - startPx)).coerceIn(0f, 1f)
                                    if (!pullCommittedThisDrag && pulledUpPx >= commitPx) {
                                        addSection()
                                        pullCommittedThisDrag = true
                                        // After adding a page, doc bottom jumps down; HUD will drop out naturally
                                    }
                                } else {
                                    pullHudVisible = false
                                    pullHudProgress = 0f
                                }
                            } else {
                                pullHudVisible = false
                                pullHudProgress = 0f
                            }

                            postInvalidateOnAnimation()

                        }
                    }

                    // Stylus hand-pan tool, or temporary hold-to-pan
                    (panMode || tempPanActive) && activePointerId != -1 -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val x = event.getX(i)
                            val y = event.getY(i)
                            translationX += (x - lastPanFocusX)
                            translationY += (y - lastPanFocusY)
                            lastPanFocusX = x
                            lastPanFocusY = y
                            // Lock horizontal pan at 1×
                            if (abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
                            // --- HUD/progress for bottom-page pull (stylus pan) ---
                            if (event.pointerCount == 1) {
                                val screenH = height.toFloat()
                                val startPx = pullStartRatio * screenH
                                val commitPx = pullCommitRatio * screenH
                                val docBottomViewY = translationY + contentHeightPx() * scaleFactor
                                val pulledUpPx = (screenH - docBottomViewY)

                                if (pulledUpPx >= startPx) {
                                    pullHudVisible = true
                                    pullHudProgress = ((pulledUpPx - startPx) / max(1f, commitPx - startPx)).coerceIn(0f, 1f)
                                    if (!pullCommittedThisDrag && pulledUpPx >= commitPx) {
                                        addSection()
                                        pullCommittedThisDrag = true
                                    }
                                } else {
                                    pullHudVisible = false
                                    pullHudProgress = 0f
                                }
                            } else {
                                pullHudVisible = false
                                pullHudProgress = 0f
                            }

                            postInvalidateOnAnimation()

                        }
                    }

                    // Drawing
                    drawing -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))

                            run {
                                // keep latest content-space sample
                                currStrokePts.add(PointF(cx, cy))

                                if (!holdActive) {
                                    // if last few moves were tiny, treat as holding
                                    val n = currStrokePts.size
                                    if (n >= 3) {
                                        val a = currStrokePts[n - 1]
                                        val b = currStrokePts[n - 2]
                                        val c = currStrokePts[n - 3]
                                        val tiny = hypot(a.x - b.x, a.y - b.y) + hypot(b.x - c.x, b.y - c.y)
                                        if (tiny <= STILL_MOVE_EPS) {
                                            holdActive = true
                                            holdStartTime = SystemClock.uptimeMillis()
                                            lastHoldX = a.x
                                            lastHoldY = a.y
                                            removeCallbacks(shapeSnapRunnable)
                                            postDelayed(shapeSnapRunnable, SNAP_HOLD_MS)
                                        }
                                    }
                                } else {
                                    // cancel hold if moved too much
                                    val a = currStrokePts.last()
                                    if (hypot(a.x - lastHoldX, a.y - lastHoldY) > STILL_MOVE_EPS) {
                                        holdActive = false
                                        removeCallbacks(shapeSnapRunnable)
                                    } else {
                                        // still holding: if preview is showing and we exceeded revert time, revert it
                                        val heldFor = SystemClock.uptimeMillis() - holdStartTime
                                        if (snapApplied && !snapAborted && heldFor >= (SNAP_HOLD_MS + SNAP_REVERT_EXTRA_MS)) {
                                            current?.let { revertSnapPreview(it) }
                                            snapAborted = true
                                        }
                                    }
                                }
                            }


// normal stroke rendering
                            extendStroke(cx, cy)
// Per framework deprecation notes, the dirty rect is ignored on modern HW rendering;



                        }
                    }


                    // Selection marquee
                    selectingGesture -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))
                            extendSelection(cx, cy)
                            postInvalidateOnAnimation()

                        }
                    }

                    // Transform selection
                    transforming -> {
                        val i = event.findPointerIndex(activePointerId)
                        if (i != -1) {
                            val (cx, cy) = toContent(event.getX(i), event.getY(i))
                            updateTransform(cx, cy)
                            postInvalidateOnAnimation()

                        }
                    }

                    // Two-finger pan (any tool)
                    event.pointerCount >= 2 -> {
                        val fx = averageX(event)
                        val fy = averageY(event)
                        val dx = (fx - lastPanFocusX)
                        val dy = (fy - lastPanFocusY)

                        // Horizontal: no overscroll—just apply & clamp
                        translationX += dx

                        // Vertical: allow overscroll then softly resist; clamp hard on ACTION_UP
                        val newY = translationY + dy
                        val limitTop = 0f + vOverscrollPx()
                        val limitBot = height - (contentHeightPx() * scaleFactor) - vOverscrollPx()


                        translationY = when {
                            newY > limitTop  -> limitTop + (newY - limitTop) * 0.2f   // damp above top
                            newY < limitBot  -> limitBot + (newY - limitBot) * 0.2f   // damp below bottom
                            else -> newY
                        }

                        // keep within absolute hard clamps (just in case)
                        clampPan()

                        lastPanFocusX = fx
                        lastPanFocusY = fy
                        // Lock horizontal pan at 1×
                        if (abs(scaleFactor - 1f) < 1e-3f) translationX = 0f
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
                animateBackIntoBoundsIfNeeded()
            // Reset HUD state (commit, if any, already occurred during MOVE)
            pullHudVisible = false
            pullHudProgress = 0f
            pullCommittedThisDrag = false






                val wasPanning = fingerPanning || ((panMode || tempPanActive) && activePointerId != -1)

                if (wasPanning) {
                    // Fling if velocity is high enough
                    velocityTracker?.let { vt ->
                        vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        val vx = vt.getXVelocity(activePointerId)
                        val vy = vt.getYVelocity(activePointerId)
                        if (abs(vx) >= minFlingVelocity || abs(vy) >= minFlingVelocity) {
                            startFling(vx, vy)
                        }
                    }
                }

                fingerPanning = false
                if (panMode) activePointerId = -1
                when {
                    selectingGesture -> finishSelection()
                    transforming -> finishTransform()
                    else -> finishStroke()
                }
                cancelStylusHoldToPan()
                endTracker()
                performClick()
                // SHAPE-SNAP: stop any pending snap
                removeCallbacks(shapeSnapRunnable)
                holdActive = false
                currStrokePts.clear()

            }
        }
        return true
    }


    private fun hideSelectionWidthPopup() {
        selWidthPopup?.dismiss()
        selWidthPopup = null
        selWidthPopupRectScreen = null
    }

    private fun showSelectionWidthPopupAt(anchorRectView: RectF) {
        hideSelectionWidthPopup()

        val content = android.view.LayoutInflater.from(context)
            .inflate(com.pelicankb.jobnotes.R.layout.popup_selection_width, null, false)

        val preview = content.findViewById<View>(com.pelicankb.jobnotes.R.id.previewLine)
        val seek = content.findViewById<android.widget.SeekBar>(com.pelicankb.jobnotes.R.id.seekSelStroke)
        val value = content.findViewById<android.widget.TextView>(com.pelicankb.jobnotes.R.id.valueDp)

        // Ensure these views can receive touch/focus
        content.isClickable = true
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        seek.isEnabled = true
        seek.isClickable = true
        seek.isFocusable = true
        seek.isFocusableInTouchMode = true

        // Tint the SeekBar so it's clearly visible regardless of theme
        try {
            val pd = DrawableCompat.wrap(seek.progressDrawable)
            DrawableCompat.setTint(pd, Color.BLACK)
            seek.progressDrawable = pd

            val th = DrawableCompat.wrap(seek.thumb)
            DrawableCompat.setTint(th, Color.BLACK)
            seek.thumb = th
        } catch (_: Throwable) {
            // best-effort; ignore if drawables not tintable
        }

        // Initialize from selection: average DP
        val density = resources.displayMetrics.density
        val avgPx = selectedStrokes.map { it.baseWidth }.average().toFloat().coerceAtLeast(0.5f)
        val startDp = (avgPx / density).coerceIn(1f, 120f)
        seek.max = 120
        seek.progress = startDp.toInt()
        value.text = "${startDp.toInt()} dp"

        // Preview line drawable
        preview.background = object : android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) {
            override fun draw(canvas: android.graphics.Canvas) {
                super.draw(canvas)
                val cy = bounds.centerY().toFloat()
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    color = Color.BLACK
                    strokeWidth = seek.progress * density
                }
                val pad = (12 * density)
                canvas.drawLine(bounds.left + pad, cy, bounds.right - pad, cy, p)
            }
        }

        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                val dp = v.coerceAtLeast(1)
                value.text = "$dp dp"
                // Absolute normalize on current selection
                updateSelectedStrokeWidthDp(dp.toFloat())
                // Refresh preview
                preview.invalidate()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        selWidthPopup = android.widget.PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true /* focusable: true so SeekBar gets key/touch focus */
        ).apply {
            isOutsideTouchable = true
            isTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            elevation = 12f
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setOnDismissListener { selWidthPopup = null; selWidthPopupRectScreen = null }
            // Allow over-screen edges if needed
            isClippingEnabled = false
        }

        // Compute screen pos down-right from corner; flip if near edges.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenX = (loc[0] + anchorRectView.right).toInt()
        val screenY = (loc[1] + anchorRectView.bottom).toInt()

        // Measure popup size
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val pw = content.measuredWidth
        val ph = content.measuredHeight

        val displayRect = android.graphics.Rect()
        getWindowVisibleDisplayFrame(displayRect)

        var x = screenX + dpToPx(6f).toInt()
        var y = screenY + dpToPx(6f).toInt()
        if (x + pw > displayRect.right) x = (screenX - pw - dpToPx(6f)).toInt()
        if (y + ph > displayRect.bottom) y = (screenY - ph - dpToPx(6f)).toInt()

        // Save rect to consume touches inside popup (so canvas won't deselect/draw)
        selWidthPopupRectScreen = android.graphics.Rect(x, y, x + pw, y + ph)

        selWidthPopup?.showAtLocation(this, android.view.Gravity.START or android.view.Gravity.TOP, x, y)
    }

    // ===== Zoom helpers =====

    private fun zoomTo(targetScale: Float, focusViewX: Float, focusViewY: Float) {
        val (cx, cy) = toContent(focusViewX, focusViewY)
        val clamped = targetScale.coerceIn(0.5f, 4f)
        scaleFactor = clamped
        // Keep the tapped content point under the same screen coordinate
        translationX = focusViewX - clamped * cx
        translationY = focusViewY - clamped * cy
        clampPan() // includes 1× horizontal lock + centering for <1×
        invalidate()
    }

    // ===== Stroke input =====

    private fun startStroke(ev: MotionEvent, idx: Int) {
        redoStack.clear()

        val (x, y) = toContent(ev.getX(idx), ev.getY(idx))
        val secIdx = sectionIndexForContentY(y)
        current = StrokeOp(
            type = baseBrush,
            color = baseColor,
            baseWidth = baseWidthPx
        ).also {
            it.sectionIndex = secIdx

            it.points.add(Point(x, y))
            it.lastDir = null
            it.calligPath = null
            it.fountainPath = null
            it.eraseHLOnly = eraserHLOnly
            if (baseBrush == BrushType.ERASER_STROKE) it.erased = mutableListOf()
        }

        when (baseBrush) {
            BrushType.HIGHLIGHTER_FREEFORM,
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                scratchHLBySection.getOrNull(secIdx)?.eraseColor(Color.TRANSPARENT)
            }
            BrushType.ERASER_AREA -> { /* no scratch */ }
            else -> {
                scratchInkBySection.getOrNull(secIdx)?.eraseColor(Color.TRANSPARENT)
            }
        }


        lastX = x
        lastY = y
        // SHAPE-SNAP: reset buffers/hold and seed first point
        currStrokePts.clear()
        holdActive = false
        removeCallbacks(shapeSnapRunnable)
        currStrokePts.add(PointF(x, y))
        snapKind = SnapKind.NONE
        snapApplied = false
        snapAborted = false
        snapPts = null


        drawing = true
        selectingGesture = false
        transforming = false
        activePointerId = ev.getPointerId(idx)

        // Use a hardware layer while drawing union-based tools to improve perf; drop it on finish
        if (baseBrush == BrushType.FOUNTAIN || baseBrush == BrushType.CALLIGRAPHY) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

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
        // Constrain selection to the page where the selection started
        // (compute from the marquee start Y).
        val selectionPage = sectionIndexForContentY(marqueeStartY)

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
        val selRegion = Region().apply { setPath(selPath, clip) }
        val selBox = android.graphics.Rect().also { selRegion.getBounds(it) }

        // Determine the page for the selection by using the marquee center Y
        val selCenterY = (selBox.top + selBox.bottom) * 0.5f
        val selectionPage = sectionIndexForContentY(selCenterY)

        selectedStrokes.clear()
        for (s in strokes) {
            if (s.hidden) continue
            if (s.sectionIndex != selectionPage) continue  // PAGE-LOCAL selection rule


            val b = strokeBounds(s) ?: continue
            if (!RectF(selBox).intersect(b)) continue

            when (selectionPolicy) {
                SelectionPolicy.STROKE_WISE   -> if (strokeIntersectsRegion(s, selRegion)) selectedStrokes.add(s)
                SelectionPolicy.REGION_INSIDE -> if (strokeInsideRegion(s, selRegion)) selectedStrokes.add(s)
            }
        }
        selectedBounds = computeSelectionBounds()
        selectionInteractive = true
    }


    // ===== Transform (drag/scale) =====

    private fun beginTransform(handle: Handle, cx: Float, cy: Float) {
        hideSelectionWidthPopup()

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
            Handle.ROTATE -> {
                // rotation around selection center
                rotateCenterX = r.centerX()
                rotateCenterY = r.centerY()
                rotateStartAngleRad = atan2(cy - rotateCenterY, cx - rotateCenterX)
                overlayRotateActive = true
                overlayRotateAngleRad = 0f
                overlayStartBounds.set(startBounds)  // keep the original bounds for drawing a rotated box

                TransformKind.ROTATE
            }
            else -> null
        }

        overlayActive = true
        setLayerType(LAYER_TYPE_HARDWARE, null) // lightweight perf boost while transforming

        // Build a cached bitmap of the selected strokes (content-space)
        val sb = selectedBounds ?: return
        val bw = ceil(max(1f, sb.width())).toInt()
        val bh = ceil(max(1f, sb.height())).toInt()
        if (bw > 0 && bh > 0) {
            overlayBmp?.recycle()
            overlayBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            overlayBmpCanvas = Canvas(overlayBmp!!)
            overlayStartTopLeft.set(sb.left, sb.top)

            // Draw the selected strokes into overlayBmp at (0,0) by offsetting -sb.left/-sb.top
            overlayBmpCanvas?.save()
            overlayBmpCanvas?.translate(-sb.left, -sb.top)

            // Reuse your committed-draw logic per stroke type (HL first, then ink), but ONLY for selectedStrokes:
            for (s in selectedStrokes) {
                when (s.type) {
                    BrushType.HIGHLIGHTER_FREEFORM, BrushType.HIGHLIGHTER_STRAIGHT -> {
                        val pts = s.points
                        if (pts.size >= 2) {
                            val p = newStrokePaint(s.color, s.baseWidth)
                            applyHighlighterBlend(p)
                            for (i in 1 until pts.size) {
                                val a = pts[i - 1]; val b = pts[i]
                                overlayBmpCanvas?.drawLine(a.x, a.y, b.x, b.y, p)
                            }
                        }
                    }
                    BrushType.CALLIGRAPHY -> {
                        val path = s.calligPath ?: Path(buildCalligraphyPath(s)).also { s.calligPath = it }
                        overlayBmpCanvas?.drawPath(path, fillPaint(s.color))
                    }
                    BrushType.FOUNTAIN -> {
                        val path = s.fountainPath ?: Path(buildFountainPath(s)).also { s.fountainPath = it }
                        overlayBmpCanvas?.drawPath(path, fillPaint(s.color))
                    }
                    BrushType.PEN -> {
                        val pts = s.points
                        if (pts.size >= 2) {
                            val p = newStrokePaint(s.color, s.baseWidth)
                            for (i in 1 until pts.size) {
                                val a = pts[i - 1]; val b = pts[i]
                                overlayBmpCanvas?.drawLine(a.x, a.y, b.x, b.y, p)
                            }
                        }
                    }
                    BrushType.MARKER -> {
                        val pts = s.points
                        if (pts.size >= 2) {
                            markerPaint.color = s.color
                            markerPaint.strokeWidth = s.baseWidth * 1.30f
                            markerPaint.maskFilter = BlurMaskFilter(s.baseWidth * 0.22f, BlurMaskFilter.Blur.NORMAL)
                            for (i in 1 until pts.size) {
                                val a = pts[i - 1]; val b = pts[i]
                                overlayBmpCanvas?.drawLine(a.x, a.y, b.x, b.y, markerPaint)
                            }
                        }
                    }
                    BrushType.PENCIL -> {
                        // Light preview (optional): or skip for speed; pencil is heavy.
                        val pts = s.points
                        if (pts.size >= 2) {
                            val p = newStrokePaint(s.color, s.baseWidth).apply { alpha = 160 }
                            for (i in 1 until pts.size) {
                                val a = pts[i - 1]; val b = pts[i]
                                overlayBmpCanvas?.drawLine(a.x, a.y, b.x, b.y, p)
                            }
                        }
                    }
                    BrushType.ERASER_AREA, BrushType.ERASER_STROKE -> { /* omit in preview; erases will be applied at drop */ }
                }
            }
            overlayBmpCanvas?.restore()
        }
        overlayMatrix.reset()

        // Build base ONCE (with all erasers applied) for correct background during drag
        rebuildCommitted()
        invalidate()
    }

    private fun updateTransform(cx: Float, cy: Float) {
        val kind = transformKind ?: return
        val r = startBounds
        val minScale = 0.1f

        when (kind) {
            TransformKind.TRANSLATE -> {
                val dx = cx - downX
                val dy = cy - downY
                overlayMatrix.reset()
                overlayMatrix.postTranslate(dx, dy)
                selectedBounds = RectF(r).apply { offset(dx, dy) }
            }

            TransformKind.SCALE_X -> {
                val startW = max(1e-3f, r.width())
                val sx = max(minScale, abs(cx - transformAnchorX) / startW)
                overlayMatrix.reset()
                overlayMatrix.postTranslate(-transformAnchorX, -transformAnchorY)
                overlayMatrix.postScale(sx, 1f)
                overlayMatrix.postTranslate(transformAnchorX, transformAnchorY)
                selectedBounds = computeTransformedAABB(r, overlayMatrix)
            }

            TransformKind.SCALE_Y -> {
                val startH = max(1e-3f, r.height())
                val sy = max(minScale, abs(cy - transformAnchorY) / startH)
                overlayMatrix.reset()
                overlayMatrix.postTranslate(-transformAnchorX, -transformAnchorY)
                overlayMatrix.postScale(1f, sy)
                overlayMatrix.postTranslate(transformAnchorX, transformAnchorY)
                selectedBounds = computeTransformedAABB(r, overlayMatrix)
            }

            TransformKind.SCALE_UNIFORM -> {
                val startW = max(1e-3f, r.width())
                val startH = max(1e-3f, r.height())
                val nx = abs(cx - transformAnchorX) / startW
                val ny = abs(cy - transformAnchorY) / startH
                val s = max(minScale, max(nx, ny))
                overlayMatrix.reset()
                overlayMatrix.postTranslate(-transformAnchorX, -transformAnchorY)
                overlayMatrix.postScale(s, s)
                overlayMatrix.postTranslate(transformAnchorX, transformAnchorY)
                selectedBounds = computeTransformedAABB(r, overlayMatrix)
            }

            TransformKind.ROTATE -> {
                val start = rotateStartAngleRad ?: return
                val cur = atan2(cy - rotateCenterY, cx - rotateCenterX)
                val deltaDegRaw = Math.toDegrees((cur - start).toDouble()).toFloat()
                val snapped = (deltaDegRaw / rotateSnapDeg).roundToInt() * rotateSnapDeg
                val useDeg = if (abs(deltaDegRaw - snapped) <= rotateSnapTolDeg) snapped else deltaDegRaw
                overlayRotateAngleRad = Math.toRadians(useDeg.toDouble()).toFloat()

                overlayMatrix.reset()
                overlayMatrix.postRotate(useDeg, rotateCenterX, rotateCenterY)
                selectedBounds = computeTransformedAABB(r, overlayMatrix)
            }
        }
        // Frame-sync repaint is smoother than invalidate()
        postInvalidateOnAnimation()
    }

    private fun finishTransform() {
        overlayRotateActive = false
        overlayRotateAngleRad = 0f

        // If we have a live overlay transform, bake it into the points once
        if (overlayBmp != null) {
            val pts = FloatArray(2)
            for (s in selectedStrokes) {
                for (i in s.points.indices) {
                    val p = s.points[i]
                    pts[0] = p.x
                    pts[1] = p.y
                    overlayMatrix.mapPoints(pts)  // apply final transform
                    s.points[i] = Point(pts[0], pts[1])
                }
                s.calligPath = null
                s.fountainPath = null
            }
            overlayBmp?.recycle()
            overlayBmp = null
            overlayBmpCanvas = null
            overlayMatrix.reset()
        }

        transforming = false
        transformKind = null
        savedPoints.clear()
        savedBaseWidth.clear()

        // bring moved selection to the top so past erasers don’t punch through
        moveSelectionToTop()
        rehomeSelectionToCorrectSections()
        normalizeAreaErasersToEnd()

        // unhide strokes and drop overlay
        for (s in selectedStrokes) s.hidden = false
        overlayActive = false
        setLayerType(LAYER_TYPE_NONE, null)

        // Clamp selection within document bounds (CONTENT space)
        selectedBounds?.let { r ->
            val docH = contentHeightPx()
            var dx = 0f; var dy = 0f
            if (r.left < 0f) dx = -r.left
            if (r.right > width) dx = min(dx, width - r.right)
            if (r.top < 0f) dy = -r.top
            if (r.bottom > docH) dy = min(dy, docH - r.bottom)
            if (dx != 0f || dy != 0f) {
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
        setLayerType(LAYER_TYPE_NONE, null)
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
            // Non-cumulative width scaling from snapshot
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

    private val pagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color =
            Color.WHITE}

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            xfermode = null
        }

    // Small helper used by rebuildCommitted() to clear a circular area
    private fun clearDisc(canvas: Canvas?, cx: Float, cy: Float, radius: Float) {
        if (canvas == null) return
        canvas.drawCircle(cx, cy, radius, clearPaint)
    }

    private fun applyHighlighterBlend(p: Paint) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            p.blendMode = android.graphics.BlendMode.LIGHTEN
        } else {
            @Suppress("DEPRECATION")
            p.xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.LIGHTEN
            )
        }
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.SQUARE
        p.strokeJoin = Paint.Join.ROUND
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

    /** Section top offset (CONTENT px) for a stroke; 0 if missing. */
    private fun sectionOffsetY(op: StrokeOp): Float =
        sections.getOrNull(op.sectionIndex)?.yOffsetPx ?: 0f
    // Return the section index that fully contains the given doc-space rect, or -1 if none
    private fun sectionIndexContainingRect(docRect: RectF): Int {
        for (i in sections.indices) {
            val off = sections[i].yOffsetPx
            val h   = sections[i].heightPx
            val top = off
            val bot = off + h
            if (docRect.top >= top && docRect.bottom <= bot) return i
        }
        return -1
    }

    // Return the section index that contains a doc-space Y, or -1 if none
    private fun sectionIndexAtDocY(docY: Float): Int {
        for (i in sections.indices) {
            val off = sections[i].yOffsetPx
            val h   = sections[i].heightPx
            if (docY >= off && docY <= off + h) return i
        }
        return -1
    }

    // Re-home all selected strokes' sectionIndex based on where they ended up.
// Prefer a single target section if the selection as a whole fits in one section;
// otherwise assign per-stroke based on the stroke's own bounds.
    private fun rehomeSelectionToCorrectSections() {
        if (selectedStrokes.isEmpty()) return

        // Try group-level rehome first (fewer writes, keeps group together)
        val sb = selectedBounds ?: run { return }  // doc-space bounds of selection
        val groupTarget = sectionIndexContainingRect(sb)

        if (groupTarget >= 0) {
            for (s in selectedStrokes) s.sectionIndex = groupTarget
            return
        }

        // Fallback: assign per-stroke (handles tall selections grazing a boundary)
        // Fallback: assign per-stroke (handles tall selections grazing a boundary)
        for (s in selectedStrokes) {
            val b = strokeBounds(s) ?: continue   // skip if bounds is null
            val target = sectionIndexContainingRect(b)
            if (target >= 0) {
                s.sectionIndex = target
            } else {
                // As a last resort, pick by centerY; this keeps content visible
                val centerY = (b.top + b.bottom) * 0.5f
                val byCenter = sectionIndexAtDocY(centerY)
                if (byCenter >= 0) s.sectionIndex = byCenter
            }
        }

    }


    /** Translate canvas up by the section offset while drawing this stroke, then restore. */
    private inline fun withSection(canvas: Canvas?, op: StrokeOp, draw: (Canvas) -> Unit) {
        if (canvas == null) return
        val offY = sectionOffsetY(op)
        canvas.save()
        canvas.translate(0f, -offY)
        draw(canvas)
        canvas.restore()
    }

    // ===== Rebuild committed layers =====

    private fun rebuildCommitted() {
        if (sections.isEmpty()) return

        // Clear all committed bitmaps
        for (i in sections.indices) {
            committedInkBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
            committedHLBySection.getOrNull(i)?.eraseColor(Color.TRANSPARENT)
        }

        // Replay strokes into their section's committed canvases
        for (op in strokes) {
            if (op.hidden) continue
            val ci = committedInkCanvas(op)
            val ch = committedHLCanvas(op)
            // ---- Prebuilt shape (if present), draw fill then stroke, then continue ----
            if (op.shapeKind != null) {
                val ci = committedInkCanvas(op)
                if (ci != null) withSection(ci, op) { c ->
                    val path = Path().apply {
                        val pts = op.points
                        if (pts.isNotEmpty()) {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                    }
                    // Fill first (optional)
                    op.shapeFillColor?.let { fill ->
                        c.drawPath(path, fillPaint(fill))
                    }
                    // Stroke on top
                    c.drawPath(path, newStrokePaint(op.color, op.baseWidth))
                }
                continue // important: skip default freehand branches
            }

            when (op.type) {
                BrushType.HIGHLIGHTER_FREEFORM -> {
                    val pts = op.points
                    if (pts.size < 2 || ch == null) continue
                    val p = newStrokePaint(op.color, op.baseWidth)
                    applyHighlighterBlend(p)
                    withSection(ch, op) { c ->
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]; val b = pts[i]
                            c.drawLine(a.x, a.y, b.x, b.y, p)
                        }
                    }
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val pts = op.points
                    if (pts.size < 2 || ch == null) continue
                    val p = newStrokePaint(op.color, op.baseWidth)
                    applyHighlighterBlend(p)
                    withSection(ch, op) { c ->
                        c.drawLine(pts.first().x, pts.first().y, pts.last().x, pts.last().y, p)
                    }
                }
                BrushType.CALLIGRAPHY -> {
                    val path = op.calligPath ?: Path(buildCalligraphyPath(op)).also { op.calligPath = it }
                    ci?.let { withSection(it, op) { c -> c.drawPath(path, fillPaint(op.color)) } }
                }
                BrushType.FOUNTAIN -> {
                    val path = op.fountainPath ?: Path(buildFountainPath(op)).also { op.fountainPath = it }
                    ci?.let { withSection(it, op) { c -> c.drawPath(path, fillPaint(op.color)) } }
                }
                BrushType.PEN -> {
                    val pts = op.points
                    if (pts.size < 2 || ci == null) continue
                    val p = newStrokePaint(op.color, op.baseWidth)
                    withSection(ci, op) { c ->
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]; val b = pts[i]
                            c.drawLine(a.x, a.y, b.x, b.y, p)
                        }
                    }
                }
                BrushType.MARKER -> {
                    val pts = op.points
                    if (pts.size < 2 || ci == null) continue
                    val p = markerPaint.apply {
                        color = op.color
                        strokeWidth = op.baseWidth * 1.30f
                        maskFilter = BlurMaskFilter(op.baseWidth * 0.22f, BlurMaskFilter.Blur.NORMAL)
                    }
                    withSection(ci, op) { c ->
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]; val b = pts[i]
                            c.drawLine(a.x, a.y, b.x, b.y, p)
                        }
                    }
                }
                BrushType.PENCIL -> {
                    val pts = op.points
                    if (pts.size < 2 || ci == null) continue
                    withSection(ci, op) { c ->
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]; val b = pts[i]
                            val dist = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
                            drawPencilSegment(c, a.x, a.y, b.x, b.y, op, dist)
                        }
                    }
                }
                // ADD inside rebuildCommitted()'s when(op.type)
                BrushType.ERASER_AREA -> {
                    // Mirror the incremental logic from extendStroke(): clear circles along the path
                    val pts = op.points
                    if (pts.size < 2) continue

                    val half = op.baseWidth * 0.5f
                    val clearInk = !op.eraseHLOnly  // same semantic as your live path

                    val ci0 = committedInkCanvas(op)      // ink layer for this section
                    val ch0 = committedHLCanvas(op)       // highlighter layer for this section

                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val steps = max(1, (dist / max(1f, half)).toInt())

                        for (k in 0..steps) {
                            val t = k / steps.toFloat()
                            val px = a.x + dx * t
                            val py = a.y + dy * t

                            if (clearInk) withSection(ci0, op) { c -> c.drawCircle(px, py, half, clearPaint) }
                            withSection(ch0, op) { c -> c.drawCircle(px, py, half, clearPaint) }

                        }
                    }
                }

                BrushType.ERASER_STROKE -> { /* no-op on rebuild; already applied by removing strokes */ }
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
                    val p = newStrokePaint(s.color, s.baseWidth)
                    applyHighlighterBlend(p)

                    for (i in 1 until pts.size) canvas.drawLine(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y, p)
                }
                BrushType.HIGHLIGHTER_STRAIGHT -> {
                    val pts = s.points
                    if (pts.size < 2) continue
                    val p = newStrokePaint(s.color, s.baseWidth)
                    applyHighlighterBlend(p)

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

    // Axis-aligned bbox for a rect after applying a matrix (content-space)
    private fun computeTransformedAABB(src: RectF, m: Matrix): RectF {
        val pts = floatArrayOf(
            src.left,  src.top,
            src.right, src.top,
            src.right, src.bottom,
            src.left,  src.bottom
        )
        m.mapPoints(pts)
        var minX = pts[0]; var minY = pts[1]; var maxX = pts[0]; var maxY = pts[1]
        for (i in 2 until pts.size step 2) {
            val x = pts[i]; val y = pts[i+1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return RectF(minX, minY, maxX, maxY)
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
    private fun drawRotatedSelectionBox(canvas: Canvas, r: RectF, angleRad: Float) {
        // Rotate around selection center
        val cx = r.centerX()
        val cy = r.centerY()
        canvas.save()
        canvas.rotate(Math.toDegrees(angleRad.toDouble()).toFloat(), cx, cy)

        // Draw the same selection visuals on the rotated canvas using the "start" bounds
        canvas.drawRect(overlayStartBounds, selectionOutline)
        if (selectionInteractive) {
            // Draw handles at the corners/edges of the start bounds under rotation
            drawHandles(canvas, overlayStartBounds)
        }
        canvas.restore()
    }

    private fun drawRotateHandle(canvas: Canvas, r: RectF) {
        val off = dpToPx(rotateHandleOffsetDp)
        val cx = r.centerX()
        val cy = r.top - off

        // handle disc
        val discR = dpToPx(9f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF9E9E9E.toInt()
            strokeWidth = dpToPx(1.5f)
        }
        canvas.drawCircle(cx, cy, discR, fill)
        canvas.drawCircle(cx, cy, discR, stroke)

        // draw vector icon centered in the disc
        rotateIcon?.let { d ->
            val iconSize = dpToPx(14f)  // keep some padding inside the 18dp icon; looks crisp
            val half = (iconSize * 0.5f).toInt()
            val left = (cx - half).toInt()
            val top  = (cy - half).toInt()
            val right = (cx + half).toInt()
            val bottom = (cy + half).toInt()
            d.setBounds(left, top, right, bottom)
            d.alpha = 255
            d.draw(canvas)
        }
    }


    private fun detectHandle(x: Float, y: Float): Handle {
        val r = selectedBounds ?: return Handle.NONE
        val pad = dpToPx(handleTouchPadDp)

        // ROTATE handle (check first)
        run {
            val off = dpToPx(rotateHandleOffsetDp)
            val rx = r.centerX()
            val ry = r.top - off
            val rotPad = dpToPx(handleTouchPadDp + 6f) // slightly easier to grab than other handles
            if (abs(x - rx) <= rotPad && abs(y - ry) <= rotPad) return Handle.ROTATE
        }


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
    // Hit a single stroke at (x,y) within tolerance, topmost first (view-space tol converted to content)
    private fun hitAnyStrokeAt(cx: Float, cy: Float, tolPx: Float = dpToPx(10f)): StrokeOp? {
        // search topmost first
        for (i in strokes.size - 1 downTo 0) {
            val s = strokes[i]
            if (s.hidden) continue
            if (hitStroke(s, cx, cy, tolPx)) return s
        }
        return null
    }

    /** Select the given stroke and any 'connected' strokes.
     *  Connection heuristic: bounding boxes overlap or touch within a small margin. */
    private fun selectStrokeAndConnected(seed: StrokeOp, expandMarginPx: Float = dpToPx(6f)) {
        selectedStrokes.clear()
        // compute seed bounds
        val seedB = strokeBounds(seed) ?: return
        val margin = expandMarginPx
        val expanded = RectF(seedB.left - margin, seedB.top - margin, seedB.right + margin, seedB.bottom + margin)
        // collect strokes whose bounds intersect the expanded seed bounds
        for (s in strokes) {
            if (s === seed || !s.hidden) {

                val b = strokeBounds(s) ?: continue
                if (RectF.intersects(expanded, b)) selectedStrokes.add(s)
            }
        }
        selectedBounds = computeSelectionBounds()
        selectionInteractive = true
        overlayActive = true
        invalidate()
    }


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
            BrushType.ERASER_AREA -> {
                val pts = s.points
                if (pts.size < 2) return null
                // treat eraser area like a thick polyline so selection region can include it
                buildPolylineArea(pts, s.baseWidth * 0.5f)
            }
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

    // ===== Dirty-rect helpers (content -> view) =====

    /** Convert a content-space rect to a clamped view-space Rect (ints). */
    private fun contentToViewRect(src: RectF): Rect {
        val left   = (translationX + src.left   * scaleFactor).roundToInt()
        val top    = (translationY + src.top    * scaleFactor).roundToInt()
        val right  = (translationX + src.right  * scaleFactor).roundToInt()
        val bottom = (translationY + src.bottom * scaleFactor).roundToInt()
        // Clamp to view bounds so we never request out-of-range redraws
        val clampedLeft   = left.coerceIn(0, width)
        val clampedTop    = top.coerceIn(0, height)
        val clampedRight  = right.coerceIn(clampedLeft, width)
        val clampedBottom = bottom.coerceIn(clampedTop, height)
        return Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }
    private fun contentToViewX(cx: Float): Float = translationX + cx * scaleFactor
    private fun contentToViewY(cy: Float): Float = translationY + cy * scaleFactor


    /**
     * Compute a conservative dirty rect in VIEW space for a stroke segment from (x0,y0) -> (x1,y1).
     * widthPx is the base stroke width in CONTENT space. We inflate by a margin to cover joins/blur.
     */
    private fun strokeDirtyViewRect(x0: Float, y0: Float, x1: Float, y1: Float, widthPx: Float): Rect {
        val minX = min(x0, x1)
        val minY = min(y0, y1)
        val maxX = max(x0, x1)
        val maxY = max(y0, y1)

        // Inflate in CONTENT space, then map to VIEW space.
        // Margin covers round caps, blurs, pencil jitter, etc.
        val half = max(0.5f, 0.5f * widthPx)
        val margin = half * 0.6f + 6f
        val box = RectF(minX - margin, minY - margin, maxX + margin, maxY + margin)

        return contentToViewRect(box)
    }

    // ===== Section helpers =====
    /** Total document height (CONTENT px) across all sections + gaps. */
    private fun contentHeightPx(): Float {
        if (sections.isEmpty()) return height.toFloat()
        val last = sections.last()
        return last.yOffsetPx + last.heightPx
    }

    /** Find section index by a content Y coordinate; returns 0 if none found. */
    private fun sectionIndexForContentY(cy: Float): Int {
        for (i in sections.indices) {
            val s = sections[i]
            if (cy >= s.yOffsetPx && cy < s.yOffsetPx + s.heightPx) return i
        }
        return 0
    }

    /** Add a new section at the end, same height as the first section (for now). */
    private fun addSection() {
        val baseH = if (sections.isEmpty()) height.toFloat() else sections[0].heightPx
        val newTop = if (sections.isEmpty()) 0f else (sections.last().yOffsetPx + sections.last().heightPx + sectionGapPx)
        sections.add(Section(heightPx = baseH, yOffsetPx = newTop))

        // Allocate one set of bitmaps/canvases for the new section only (width = current view width)
        val w = width.coerceAtLeast(1)
        val h = max(1, baseH.roundToInt())
        fun newBmp() = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val ci = newBmp().also { committedInkBySection.add(it); canvasInkBySection.add(Canvas(it)) }
        val ch = newBmp().also { committedHLBySection.add(it);  canvasHLBySection.add(Canvas(it)) }
        val si = newBmp().also { scratchInkBySection.add(it);    scratchCanvasInkBySection.add(Canvas(it)) }
        val sh = newBmp().also { scratchHLBySection.add(it);     scratchCanvasHLBySection.add(Canvas(it)) }

        ci.eraseColor(Color.TRANSPARENT); ch.eraseColor(Color.TRANSPARENT)
        si.eraseColor(Color.TRANSPARENT); sh.eraseColor(Color.TRANSPARENT)

        invalidate()
    }


    // ===== Utilities & gestures =====

    fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density




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
        // Product rule: finger must never draw.
        // - We still allow finger for panning/transform/selection elsewhere in onTouchEvent.
        // - Drawing (ink/highlighter/eraser) may only start from STYLUS/ERASER tool types.
        // - If someone later toggles stylusOnly off, we *still* enforce stylus-only here
        //   to keep the rule absolute in this app.
        return isStylus(ev, pointerIndex)
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
            stopFling()
            val prev = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 4f)

            val fx = detector.focusX
            val fy = detector.focusY
            val dx = fx - translationX
            val dy = fy - translationY
            translationX = fx - dx * (scaleFactor / prev)
            translationY = fy - dy * (scaleFactor / prev)
            clampPan()
            invalidate()
            // Final safety: keep translations finite and within safe bounds after any scale step
            if (!translationX.isFinite()) translationX = 0f
            if (!translationY.isFinite()) translationY = 0f
            clampPan()

            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            drawing = false
            selectingGesture = false
            transforming = false
            activePointerId = -1
            scalingInProgress = true
            stopFling()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scalingInProgress = false
            if (abs(scaleFactor - 1f) < 1e-3f) translationX = 0f

            // Snap to 1× or 2× if near thresholds
            maybeSnapScaleAtEnd(detector.focusX, detector.focusY)
        }

        // --- Snap zoom helper ---
        private fun maybeSnapScaleAtEnd(focusX: Float, focusY: Float) {
            val target = when {
                abs(scaleFactor - 1f) < 0.08f -> 1f
                abs(scaleFactor - 2f) < 0.10f -> 2f
                else -> null
            } ?: return

            val startScale = scaleFactor
            if (abs(target - startScale) < 1e-3f) return

            // Keep the same screen focus over the same content point
            val contentFx = (focusX - translationX) / scaleFactor
            val contentFy = (focusY - translationY) / scaleFactor

            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { animator: ValueAnimator ->
                    val t = animator.animatedFraction
                    scaleFactor = startScale + (target - startScale) * t
                    translationX = focusX - contentFx * scaleFactor
                    translationY = focusY - contentFy * scaleFactor
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Final clamp + gentle settle
                        clampPan()
                        animateBackIntoBoundsIfNeeded()
                    }
                })
            }
            anim.start()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scaleFactor < 1.5f) 2f else 1f
            zoomTo(target, e.x, e.y)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val (cx, cy) = toContent(e.x, e.y)
            // Prefer handles/inside handling elsewhere; here, long-press selects stroke + connected group.
            val hit = hitAnyStrokeAt(cx, cy, dpToPx(10f))
            if (hit != null) {
                selectStrokeAndConnected(hit)
            } else {
                // Long-press on blank area clears any lingering selection
                if (selectedStrokes.isNotEmpty()) {
                    clearSelection()
                }
            }
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

    // ===== In-progress throttle & safety guards =====
    /** Quick min/max bounds from points (CONTENT space). */
    private fun quickBoundsOf(points: List<Point>, out: RectF): Boolean {
        if (points.isEmpty()) return false
        var minX = points[0].x
        var minY = points[0].y
        var maxX = minX
        var maxY = minY
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        out.set(minX, minY, maxX, maxY)
        return true
    }

    /** Minimum movement (CONTENT px) required before processing a new segment for this op. */
    private fun minStepFor(op: StrokeOp): Float {
        // Goal: reduce the “dashed” feel without blowing up union-path costs.
        // Keep union tools a bit conservative; lighten linear tools.
        return when (op.type) {
            // Union-heavy brushes: still throttled, but slightly eased.
            BrushType.CALLIGRAPHY,
            BrushType.FOUNTAIN -> max(0.35f * op.baseWidth, 2.0f)

            // Smooth highlighters/markers: allow denser segments.
            BrushType.MARKER,
            BrushType.HIGHLIGHTER_FREEFORM -> max(0.18f * op.baseWidth, 0.9f)

            // Straight highlighter updates continuously; threshold can be small.
            BrushType.HIGHLIGHTER_STRAIGHT -> max(0.14f * op.baseWidth, 0.8f)

            // Pencil/pen feel better with tighter steps.
            BrushType.PENCIL,
            BrushType.PEN -> 0.7f

            // Eraser area shouldn’t skip too much—keeps erasing smooth.
            BrushType.ERASER_AREA -> max(0.16f * op.baseWidth, 0.9f)

            // Stroke eraser is hit-test based; movement threshold is small.
            BrushType.ERASER_STROKE -> 0.7f
        }
    }



    /** True if value is a finite, non-NaN float. */
    private fun isFinitef(v: Float): Boolean = v.isFinite()

    // ===== Drawing: extend & finish stroke =====

    private fun extendStroke(x: Float, y: Float) {
        val op = current ?: return

        // Clamp both previous and new points to this stroke's section bounds
        fun clampToSectionCoords(xx: Float, yy: Float): Pair<Float, Float> {
            val s = sections.getOrNull(op.sectionIndex) ?: return xx to yy
            val cy = yy.coerceIn(s.yOffsetPx, s.yOffsetPx + s.heightPx)
            return xx to cy
        }
        val (sx, sy) = clampToSectionCoords(lastX, lastY)
        val (nx, ny) = clampToSectionCoords(x, y)

        val dx = nx - sx
        val dy = ny - sy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!isFinitef(dist) || dist < minStepFor(op)) return

        op.points.add(Point(nx, ny))

        when (op.type) {
            BrushType.HIGHLIGHTER_FREEFORM -> {
                val p = newStrokePaint(op.color, op.baseWidth)
                applyHighlighterBlend(p)
                scratchHLScratchCanvas(op)?.let { withSection(it, op) { c -> c.drawLine(sx, sy, nx, ny, p) } }
            }
            BrushType.HIGHLIGHTER_STRAIGHT -> {
                scratchHLBySection.getOrNull(op.sectionIndex)?.eraseColor(Color.TRANSPARENT)
                val p = newStrokePaint(op.color, op.baseWidth)
                applyHighlighterBlend(p)
                val a = op.points.first()
                scratchHLScratchCanvas(op)?.let { withSection(it, op) { c -> c.drawLine(a.x, a.y, nx, ny, p) } }
            }
            BrushType.ERASER_AREA -> {
                val clearInk = !eraserHLOnly
                val half = op.baseWidth * 0.5f
                val steps = max(1, (dist / max(1f, half)).toInt())
                val ci0 = committedInkCanvas(op)
                val ch0 = committedHLCanvas(op)
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val px = sx + dx * t
                    val py = sy + dy * t
                    ci0?.let { withSection(it, op) { c -> if (clearInk) c.drawCircle(px, py, half, clearPaint) } }
                    ch0?.let { withSection(it, op) { c -> c.drawCircle(px, py, half, clearPaint) } }
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
                    if (s.sectionIndex != op.sectionIndex) continue
                    if (hitStroke(s, nx, ny, radius)) {
                        strokes.removeAt(i)
                        op.erased?.add(ErasedEntry(i, s))
                        removedOne = true
                        break
                    }
                }
                if (removedOne) rebuildCommitted()
            }
            BrushType.CALLIGRAPHY -> {
                val len = dist
                if (len > 1e-3f) {
                    val dirNow = atan2(dy, dx)
                    val dirPrev = op.lastDir ?: dirNow
                    val w0 = calligWidth(op, dirPrev)
                    val w1 = calligWidth(op, dirNow)
                    val hw0 = max(0.5f, 0.5f * w0)
                    val hw1 = max(0.5f, 0.5f * w1)

                    val nxA = -sin(nibAngleRad); val nyA = cos(nibAngleRad)
                    val l0x = sx + nxA * hw0; val l0y = sy + nyA * hw0
                    val r0x = sx - nxA * hw0; val r0y = sy - nyA * hw0
                    val l1x = nx + nxA * hw1; val l1y = ny + nyA * hw1
                    val r1x = nx - nxA * hw1; val r1y = ny - nyA * hw1

                    segPath.rewind()
                    segPath.moveTo(l0x, l0y)
                    segPath.lineTo(l1x, l1y)
                    segPath.lineTo(r1x, r1y)
                    segPath.lineTo(r0x, r0y)
                    segPath.close()

                    scratchInkCanvas(op)?.let { withSection(it, op) { c -> c.drawPath(segPath, fillPaint(op.color)) } }
                    lastDirtyViewRect = strokeDirtyViewRect(sx, sy, nx, ny, max(w0, w1))
                    op.lastDir = dirNow
                } else {
                    op.lastDir = atan2(dy, dx)
                }
            }
            BrushType.FOUNTAIN -> {
                val half = max(0.5f, 0.5f * op.baseWidth)
                val seg = buildThickSegmentPath(sx, sy, nx, ny, half, squareCaps = false)
                scratchInkCanvas(op)?.let { withSection(it, op) { c -> c.drawPath(seg, fillPaint(op.color)) } }
                lastDirtyViewRect = strokeDirtyViewRect(sx, sy, nx, ny, op.baseWidth)
            }
            BrushType.PENCIL -> {
                scratchInkCanvas(op)?.let { withSection(it, op) { c -> drawPencilSegment(c, sx, sy, nx, ny, op, dist) } }
            }
            BrushType.MARKER -> {
                scratchInkCanvas(op)?.let { withSection(it, op) { c -> drawMarkerSegment(c, sx, sy, nx, ny, op, dist) } }
            }
            BrushType.PEN -> {
                scratchInkCanvas(op)?.let { withSection(it, op) { c -> drawPenSegment(c, sx, sy, nx, ny, op) } }
            }
        }

        lastDirtyViewRect = strokeDirtyViewRect(sx, sy, nx, ny, op.baseWidth)
        lastX = nx
        lastY = ny
        // Smooth, frame-synced repaint
        postInvalidateOnAnimation()
    }


    private fun finishStroke() {
        if (!drawing) return
        current?.let { stroke ->
            if (stroke.type == BrushType.ERASER_AREA || stroke.type == BrushType.ERASER_STROKE) {
                stroke.eraseHLOnly = eraserHLOnly
            }
            // SHAPE-SNAP: if a preview was applied (and not reverted), replace points with snapped geometry
            if (snapApplied && !snapAborted) {
                replaceStrokePointsWithSnap(stroke)   // commits snapPts into stroke.points
                snapApplied = false
                snapAborted = false
                snapKind = SnapKind.NONE
                snapPts = null
            }

            when (stroke.type) {
                BrushType.CALLIGRAPHY -> {
                    try {
                        val bb = RectF()
                        val have = quickBoundsOf(stroke.points, bb)
                        val area = if (have) (bb.width() * bb.height()) else 0f
                        stroke.calligPath =
                            if (stroke.points.size > 2000 || area > 24_000_000f) null
                            else Path(buildCalligraphyPath(stroke))
                    } catch (_: Throwable) { stroke.calligPath = null }
                }
                BrushType.FOUNTAIN -> {
                    try {
                        val bb = RectF()
                        val have = quickBoundsOf(stroke.points, bb)
                        val area = if (have) (bb.width() * bb.height()) else 0f
                        stroke.fountainPath =
                            if (stroke.points.size > 2000 || area > 24_000_000f) null
                            else Path(buildFountainPath(stroke))
                    } catch (_: Throwable) { stroke.fountainPath = null }
                }
                else -> { /* no snapshot */ }
            }
            strokes.add(stroke)

            // Clear scratch for this page only
            val sec = stroke.sectionIndex
            scratchInkBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
            scratchHLBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
        }
        current = null
        drawing = false
        activePointerId = -1
        setLayerType(LAYER_TYPE_NONE, null)

        rebuildCommitted()
        lastDirtyViewRect = null
        invalidate()
    }


    // Paste centered at (cx, cy) in doc-space, then immediately enter translate
    private fun performPasteAt(cx: Float, cy: Float) {
        val src = clipboardBounds ?: return
        cancelTransform()
        selectedStrokes.clear()

        val dx = cx - src.centerX()
        val dy = cy - src.centerY()

        // 1) Make translated copies (do not set sectionIndex here)
        val copies = clipboard.map { copy ->
            val c = deepCopy(copy)
            translateStroke(c, dx, dy)
            c
        }

        // 2) Add + select + compute selection bbox
        strokes.addAll(copies)
        selectedStrokes.addAll(copies)
        selectedBounds = RectF(src).apply { offset(dx, dy) }
        selectionInteractive = true
        overlayActive = true

        // 3) Retag to the page(s) where they actually landed
        rehomeSelectionToCorrectSections()

        pastePreviewVisible = false
        rebuildCommitted()
        invalidate()
    }


    // ===== Helpers: deep copy, translation & z-order for clipboard/selection =====

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

    // *** NEW: after moving/resizing, keep selection together and ensure erasers render last within the group ***
    private fun moveSelectionToTop() {
        if (selectedStrokes.isEmpty()) return

        // 1) Extract the selected ops in their current relative order
        val group = ArrayList<StrokeOp>(selectedStrokes.size)
        val it = strokes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (selectedStrokes.contains(s)) {
                group.add(s)
                it.remove()
            }
        }

        if (group.isEmpty()) return

        // 2) Stable-partition within the selection: non-erasers first, ERASER_AREA last
        val nonErasers = ArrayList<StrokeOp>(group.size)
        val erasers = ArrayList<StrokeOp>()
        for (s in group) {
            if (s.type == BrushType.ERASER_AREA) erasers.add(s) else nonErasers.add(s)
        }

        // 3) Append back in order so erasers render after sibling strokes
        strokes.addAll(nonErasers)
        strokes.addAll(erasers)
    }
    // Keep all ERASER_AREA ops at the end so rebuildCommitted() will always
// draw strokes first and then apply area clears. This preserves erasures
// even if selections are reordered to the top.
    private fun normalizeAreaErasersToEnd() {
        if (strokes.isEmpty()) return
        val keep = ArrayList<StrokeOp>(strokes.size)
        val area = ArrayList<StrokeOp>()
        for (s in strokes) {
            if (s.type == BrushType.ERASER_AREA) area.add(s) else keep.add(s)
        }
        if (area.isEmpty()) return
        strokes.clear()
        strokes.addAll(keep)   // original order for non-erasers preserved
        strokes.addAll(area)   // original order among erasers preserved
    }


    // ===== Pan/zoom helpers =====

    private fun clampPan() {
        val s = scaleFactor
        val w = width.toFloat()
        val h = height.toFloat()
        val docH = contentHeightPx().coerceAtLeast(1f)

        // -------- X (horizontal) --------
        // When zoomed OUT (<1×), horizontally center the scaled content and disallow horizontal panning.
        // When ≥1×, use a safe clamp range [minX <= 0, 0] to avoid empty coerceIn intervals.
        if (s < 1f - 1e-3f) {
            translationX = (w - s * w) * 0.5f  // center horizontally
        } else {
            val rawMinX = w - s * w
            val minX = kotlin.math.min(0f, rawMinX)  // ensure minX ≤ 0
            val maxX = 0f
            translationX = translationX.coerceIn(minX, maxX)
        }

        // -------- Y (vertical) --------
        // Keep the "top-lock" behavior: only allow scrolling within [minY, 0].
        // Force a valid clamp range by ensuring minY ≤ 0 at all times.
        val rawMinY = h - (s * docH)
        val minY = kotlin.math.min(0f, rawMinY)
        val maxY = 0f
        translationY = translationY.coerceIn(minY, maxY)

        if (DEBUG_INPUT) {
            val rawMinXDbg = w - s * w
            val minXDbg = if (s < 1f - 1e-3f) (w - s * w) * 0.5f else kotlin.math.min(0f, rawMinXDbg)
            Log.d(
                TAG,
                "clampPan: s=$s w=$w h=$h docH=$docH " +
                        "rawMinX=$rawMinXDbg -> tx=$translationX (center if <1×, else clamp to [$minXDbg, 0]) " +
                        "rawMinY=$rawMinY -> clampY[$minY, $maxY] ty=$translationY"
            )
        }
    }









    private fun startFling(vx: Float, vy: Float) {
        val s = scaleFactor
        val w = width.toFloat()
        val h = height.toFloat()
        val docH = contentHeightPx().coerceAtLeast(1f)

        val startX = translationX.toInt()
        val startY = translationY.toInt()

        // Ensure vertical bounds are ALWAYS a valid range, even when zoomed out (<1×)
        fun safeMinY(): Int {
            val raw = (h - s * docH).toInt()
            return kotlin.math.min(0, raw) // ==> minY <= 0, so [minY, 0] is valid
        }

        val (minX, maxX, minY, maxY) = when {
            // Zoomed out: still clamp within a valid range (no recenters)
            s < 1f - 1e-3f -> {
                val minx = (w - s * w).toInt()
                val maxx = 0
                Quad(minx, maxx, safeMinY(), 0)
            }
            // Exactly 1×: lock X, fling within [minY, 0]
            kotlin.math.abs(s - 1f) < 1e-3f -> {
                Quad(0, 0, safeMinY(), 0)
            }
            // Zoomed in: clamp to scaled content
            else -> {
                val minx = (w - s * w).toInt()
                val maxx = 0
                Quad(minx, maxx, safeMinY(), 0)
            }
        }

        val adjVx = if (kotlin.math.abs(s - 1f) < 1e-3f) 0 else vx // horizontal lock at 1×
        scroller.fling(
            startX, startY,
            adjVx.toInt().coerceIn(-maxFlingVelocity, maxFlingVelocity),
            vy.toInt().coerceIn(-maxFlingVelocity, maxFlingVelocity),
            minX, maxX, minY, maxY
        )
        postInvalidateOnAnimation()
    }






    private fun stopFling() {
        if (!scroller.isFinished) scroller.forceFinished(true)
    }

    private fun obtainTracker() {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain() else velocityTracker?.clear()
    }
    private fun endTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // Stylus long-press -> temporary hand pan
    private fun scheduleStylusHoldToPan(ev: MotionEvent, idx: Int) {
        cancelStylusHoldToPan()
        val id = ev.getPointerId(idx)
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        longPressRunnable = Runnable {
            tempPanActive = true
            activePointerId = id
            lastPanFocusX = x
            lastPanFocusY = y
            obtainTracker()
        }
        postDelayed(longPressRunnable, longPressTimeoutMs)
    }
    private fun cancelStylusHoldToPan() {
        tempPanActive = false
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    // tiny tuple helper
    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    // ---- Pan clamp & overscroll helpers ----
    private fun viewContentWidthPx(): Float = width.toFloat() * scaleFactor
    private fun viewContentHeightPx(): Float = height.toFloat() * scaleFactor

    private fun vOverscrollPx(): Float = min(height * 0.12f, dpToPx(96f)) // allow some softness vertically
    private fun hOverscrollPx(): Float = 0f                                // reserved (unused, keeping for parity)

    private fun animateBackIntoBoundsIfNeeded() {
        // Only snap-top when zoomed in or at exactly 1x; never snap anything at <1x.
        if (scaleFactor < 1f - 1e-3f) return

        // Snap back only when overscrolled ABOVE the top (translationY > 0).
        if (translationY <= 0f) return

        val startY = translationY
        val endY = 0f
        if (kotlin.math.abs(startY - endY) < 1e-3f) return

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator: ValueAnimator ->
                val t = animator.animatedFraction
                translationY = startY + (endY - startY) * t
                invalidate()
            }
        }
        anim.start()
    }


    // ======== Shape Snap core ========

    // ======== Shape Snap core (LINE ONLY) ========
// REPLACE the entire body of this function
    private fun tryShapeSnapIfEligible() {
        if (!shapeSnapEnabled) return
        if (!holdActive) return
        val op = current ?: return
        // Need enough points to make a useful judgement
        if (currStrokePts.size < 6) return

        // ---- LINE ONLY (current behavior) ----
        val a = currStrokePts.first()
        val b = currStrokePts.last()

        // Quick gates for a "line-ish" stroke (reuse your existing thresholds)
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 12f.dp()) return

        // Show the snapped preview into the proper scratch layer
        snapPreviewLine(op, a, b)

        // 🔴 CRITICAL: also set the geometry to commit on lift
        // (finishStroke() calls replaceStrokePointsWithSnap(stroke) if snapApplied && !snapAborted)
        snapPts = sampleLinePoints(a, b).toMutableList()

        // Preview is now active; if the user holds longer than the revert window,
        // your move code elsewhere will set snapAborted = true to revert the preview.
        snapApplied = true
        invalidate()
    }


    private fun isPenFamilyBrush(): Boolean {
        return when (baseBrush) {
            BrushType.PEN, BrushType.PENCIL, BrushType.MARKER, BrushType.FOUNTAIN, BrushType.CALLIGRAPHY -> true
            else -> false
        }
    }

// --- Classification ---

    private fun classifyShape(pts: List<PointF>): SnapKind {
        val start = pts.first()
        val end = pts.last()
        val basicClosed = dist(start, end) <= CLOSE_EPS

        // If not strictly closed, apply “closure forgiveness”
        // Case A: endpoint within 3× CLOSE_EPS of start
        // Case B: the stroke crosses near the start point
        // Case C: endpoint lies inside the overall bbox (common when you cross the first edge)
        var closedPts: List<PointF>? = null
        if (!basicClosed) {
            val nearByDist = dist(start, end) <= (CLOSE_EPS * 3f)
            val nearByCross = crossesNearStart(pts, start, CLOSE_EPS * 2f)
            val endInsideBBox = bbox(pts).contains(end.x, end.y)

            if (nearByDist || nearByCross || endInsideBBox) {
                // Force closure for rectangle detection by appending the start point
                closedPts = pts.toMutableList().apply { add(PointF(start.x, start.y)) }
            }
        }

        // Prefer rectangles over circles when we have any form of closure.
        val candidateClosed = closedPts ?: (if (basicClosed) pts else null)
        if (candidateClosed != null) {
            if (looksRect(candidateClosed)) return SnapKind.RECT
            if (looksCircle(candidateClosed)) return SnapKind.CIRCLE
        }

        if (looksLine(pts)) return SnapKind.LINE
        if (looksArc(pts))  return SnapKind.ARC
        return SnapKind.NONE
    }


    private fun looksLine(pts: List<PointF>): Boolean {
        val a = pts.first(); val b = pts.last()
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 12f.dp()) return false

        // quick gate (looser)
        val maxDev = maxPerpDeviation(pts, a, b)
        if (maxDev <= LINE_MAX_DEVIATION) return true

        // robust RMS: trim 10% from both ends to ignore squiggles
        val n = pts.size
        val trim = (n * 0.10f).roundToInt().coerceAtMost(n/4)
        val i0 = 0 + trim
        val i1 = (n - 1) - trim
        if (i1 - i0 + 1 < 4) return false
        val rms = lineRmsPerp(pts, i0, i1)

        // explicit Float to avoid any accidental BigDecimal promotions
        val rel: Float = rms / len.coerceAtLeast(1f)
        return (rms <= (LINE_MAX_DEVIATION * 0.9f)) || (rel <= 0.03f)
    }




    private fun looksArc(pts: List<PointF>): Boolean {
        val a = pts.first()
        val m = pts[pts.size / 2]
        val b = pts.last()
        val circle = circleFrom3(a, m, b) ?: return false
        val (cx, cy, r) = circle
        if (r <= 1f) return false
        val avgDev = pts.asSequence().map { abs(hypot(it.x - cx, it.y - cy) - r) }.average().toFloat()
        if (avgDev > ARC_MAX_DEVIATION) return false
        val ang = arcAngleDegrees(cx, cy, a, b)
        return ang > 20f && ang < 340f
    }

    private fun looksCircle(pts: List<PointF>): Boolean {
        val bb = bbox(pts)
        val aspect = (bb.width() / bb.height()).coerceAtLeast(bb.height() / bb.width())
        if (aspect > 1.2f) return false
        val (cx, cy, r) = circleLeastSquares(pts) ?: return false
        if (r <= 1f) return false
        val avgDev = pts.asSequence().map { abs(hypot(it.x - cx, it.y - cy) - r) }.average().toFloat()
        return (avgDev / r) <= CIRCLE_RAD_DEV_PCT
    }

    private fun looksRect(pts: List<PointF>): Boolean {
        // ----- Closure forgiveness (very lenient) -----
        val start = pts.first()
        val end   = pts.last()
        val bb    = bbox(pts)
        val w     = bb.width()
        val h     = bb.height()

        // Require non-trivial size
        if (min(w, h) < 20f.dp()) return false

        // Consider it "closed-ish" if ANY of these are true:
        //  - endpoint near start within 3.5× tolerance
        //  - the stroke crosses near the start point
        //  - the endpoint lies inside the overall bbox (very common when crossing first edge)
        val closedish =
            (dist(start, end) <= (CLOSE_EPS * 3.5f)) ||
                    crossesNearStart(pts, start, CLOSE_EPS * 2.5f) ||
                    bb.contains(end.x, end.y)

        if (!closedish) return false

        // ----- Tier 1: strong rectangle signal (edge hugging + corner sanity) -----
        // Edge proximity score: how many samples lie close to *any* of the 4 bbox edges?
        // Circles typically fail this, rectangles pass it easily even when drawn rough.
        val edgeTolStrong = max(14f.dp(), 0.03f * (w + h))
        fun distToRectEdges(p: PointF): Float {
            val dx = min(abs(p.x - bb.left),  abs(p.x - bb.right))
            val dy = min(abs(p.y - bb.top),   abs(p.y - bb.bottom))
            return min(dx, dy)
        }
        var nearStrong = 0
        for (p in pts) if (distToRectEdges(p) <= edgeTolStrong) nearStrong++

        val fracNearStrong = nearStrong.toFloat() / pts.size

        // Corner sanity (loose): RDP + merge (5 -> 4) then angles ~90°
        val simplified = rdpPF(pts, epsilon = 14f.dp())
        val cornersRaw = enforceClosedAndCornersPF(simplified)
        val four = when (cornersRaw.size) {
            4 -> cornersRaw
            5 -> {
                var bestI = 1
                var bestD = Float.MAX_VALUE
                for (i in 1 until cornersRaw.size) {
                    val d = hypot(cornersRaw[i].x - cornersRaw[i-1].x, cornersRaw[i].y - cornersRaw[i-1].y)
                    if (d < bestD) { bestD = d; bestI = i }
                }
                val merged = ArrayList<PointF>(4)
                for (i in cornersRaw.indices) if (i != bestI) merged.add(cornersRaw[i])
                merged
            }
            else -> emptyList()
        }

        var cornersOk = false
        if (four.size == 4) {
            cornersOk = true
            for (i in four.indices) {
                val p0 = four[(i + four.size - 1) % four.size]
                val p1 = four[i]
                val p2 = four[(i + 1) % four.size]
                val ang = angleDegPF(p0, p1, p2)
                if (abs(ang - 90f) > RIGHT_ANGLE_EPS_DEG) { cornersOk = false; break }
            }
        }

        // Strong acceptance: lots of edge hugging and corners look ~right
        if (fracNearStrong >= 0.60f && cornersOk) return true

        // ----- Tier 2: very forgiving fallback -----
        // If at least half the points hug edges (looser tol) we accept rectangle,
        // even if corners didn’t simplify nicely (common with fast-drawn boxes).
        val edgeTolLoose = max(18f.dp(), 0.04f * (w + h))
        var nearLoose = 0
        for (p in pts) if (distToRectEdges(p) <= edgeTolLoose) nearLoose++
        val fracNearLoose = nearLoose.toFloat() / pts.size

        if (fracNearLoose >= 0.50f) return true

        // Otherwise, not rectangle.
        return false
    }





// --- Preview drawers (to scratch) ---

    private fun snapPreviewLine(op: StrokeOp, a: PointF, b: PointF) {
        drawSnapped(op) { c, paint -> c.drawLine(a.x, a.y, b.x, b.y, paint) }
    }

    private fun snapPreviewArc(op: StrokeOp, pts: List<PointF>) {
        val a = pts.first(); val m = pts[pts.size / 2]; val b = pts.last()
        val circle = circleFrom3(a, m, b) ?: return
        val (cx, cy, r) = circle
        val startAng = atan2(a.y - cy, a.x - cx)
        val endAng   = atan2(b.y - cy, b.x - cx)
        val midAng   = atan2(m.y - cy, m.x - cx)
        val sweep    = normalizedSweep(startAng, midAng, endAng)
        drawSnapped(op) { c, paint ->
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect,
                Math.toDegrees(startAng.toDouble()).toFloat(),
                Math.toDegrees(sweep.toDouble()).toFloat(),
                false, paint)
        }
    }

    private fun snapPreviewCircle(op: StrokeOp, pts: List<PointF>) {
        val circle = circleLeastSquares(pts) ?: return
        val (cx, cy, r) = circle
        drawSnapped(op) { c, paint -> c.drawCircle(cx, cy, r, paint) }
    }

    private fun snapPreviewRect(op: StrokeOp, pts: List<PointF>) {
        val simplified = enforceClosedAndCornersPF(rdpPF(pts, epsilon = 8f.dp()))
        if (simplified.size != 4) return
        val bb = bbox(simplified)
        drawSnapped(op) { c, paint -> c.drawRect(bb, paint) }
    }

    // Draw snapped preview into the proper scratch layer for the current stroke
    private inline fun drawSnapped(op: StrokeOp, draw: (Canvas, Paint) -> Unit) {
        val sec = op.sectionIndex
        val paint = newStrokePaint(op.color, op.baseWidth).apply {
            if (op.type == BrushType.HIGHLIGHTER_FREEFORM || op.type == BrushType.HIGHLIGHTER_STRAIGHT) {
                applyHighlighterBlend(this)
            }
        }

        if (op.type == BrushType.HIGHLIGHTER_FREEFORM || op.type == BrushType.HIGHLIGHTER_STRAIGHT) {
            scratchHLBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
            scratchHLScratchCanvas(op)?.let { withSection(it, op) { c -> draw(c, paint) } }
        } else {
            scratchInkBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
            scratchInkCanvas(op)?.let { withSection(it, op) { c -> draw(c, paint) } }
        }
    }

// --- Commit & Revert helpers ---

    private fun replaceStrokePointsWithSnap(stroke: StrokeOp) {
        val pts = snapPts ?: return
        stroke.points.clear()
        // Convert PointF -> StrokeOp.Point
        pts.forEach { stroke.points.add(Point(it.x, it.y)) }
        // Clear scratch for this page (we will rebuild committed right after)
        val sec = stroke.sectionIndex
        scratchInkBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
        scratchHLBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
    }

    private fun revertSnapPreview(op: StrokeOp) {
        // Clear preview from scratch and redraw the freehand so user sees original again
        val sec = op.sectionIndex
        scratchInkBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)
        scratchHLBySection.getOrNull(sec)?.eraseColor(Color.TRANSPARENT)

        val pts = op.points
        if (pts.size < 2) return
        if (op.type == BrushType.HIGHLIGHTER_FREEFORM || op.type == BrushType.HIGHLIGHTER_STRAIGHT) {
            val ch = scratchHLScratchCanvas(op) ?: return
            val p = newStrokePaint(op.color, op.baseWidth).apply { applyHighlighterBlend(this) }

            withSection(ch, op) { c ->
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    c.drawLine(a.x, a.y, b.x, b.y, p)
                }
            }
        } else {
            val ci = scratchInkCanvas(op) ?: return
            val p = newStrokePaint(op.color, op.baseWidth)
            withSection(ci, op) { c ->
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    c.drawLine(a.x, a.y, b.x, b.y, p)
                }
            }
        }
        snapApplied = false
        snapKind = SnapKind.NONE
        snapPts = null
    }

// --- Vector point generators for commit ---

    private fun sampleLinePoints(a: PointF, b: PointF): List<PointF> =
        listOf(a, b)

    private fun sampleArcPoints(pts: List<PointF>): List<PointF> {
        val a = pts.first(); val m = pts[pts.size / 2]; val b = pts.last()
        val circle = circleFrom3(a, m, b) ?: return emptyList()
        val (cx, cy, r) = circle
        val sa = atan2(a.y - cy, a.x - cx)
        val ea = atan2(b.y - cy, b.x - cx)
        val ma = atan2(m.y - cy, m.x - cx)
        val sweep = normalizedSweep(sa, ma, ea)
        val steps = max(16, (abs(sweep) * r / 6f).roundToInt().coerceAtMost(256))
        val out = ArrayList<PointF>(steps + 1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val ang = sa + sweep * t
            out.add(PointF(cx + r * cos(ang), cy + r * sin(ang)))
        }
        return out
    }

    private fun sampleCirclePoints(pts: List<PointF>): List<PointF> {
        val c = circleLeastSquares(pts) ?: return emptyList()
        val (cx, cy, r) = c
        val steps = 64
        val out = ArrayList<PointF>(steps + 1)
        for (i in 0..steps) {
            val ang = (2f * Math.PI * i / steps).toFloat()
            out.add(PointF(cx + r * cos(ang), cy + r * sin(ang)))
        }
        return out
    }

    private fun sampleRectPoints(pts: List<PointF>): List<PointF> {
        // Always snap to the bounding box of the entire stroke (more stable & forgiving)
        val bb = bbox(pts)
        return listOf(
            PointF(bb.left,  bb.top),
            PointF(bb.right, bb.top),
            PointF(bb.right, bb.bottom),
            PointF(bb.left,  bb.bottom),
            PointF(bb.left,  bb.top) // close
        )
    }


// --- Utilities for PointF ---

    private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    private fun bbox(pts: List<PointF>): RectF {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun maxPerpDeviation(pts: List<PointF>, a: PointF, b: PointF): Float {
        val vx = b.x - a.x; val vy = b.y - a.y
        val len = hypot(vx, vy).coerceAtLeast(1f)
        var maxDev = 0f
        for (p in pts) {
            val dev = abs((vy * p.x - vx * p.y + b.x * a.y - b.y * a.x) / len)
            if (dev > maxDev) maxDev = dev
        }
        return maxDev
    }
    // Weighted least-squares line fit (unit direction + a point on the line)
    private fun lineFit(pts: List<PointF>, i0: Int, i1: Int): Pair<PointF, PointF> {
        var sx = 0f; var sy = 0f
        var sxx = 0f; var syy = 0f; var sxy = 0f
        val n = (i1 - i0 + 1).coerceAtLeast(2)
        for (i in i0..i1) {
            val p = pts[i]
            sx += p.x; sy += p.y
            sxx += p.x * p.x; syy += p.y * p.y
            sxy += p.x * p.y
        }
        val mx = sx / n; val my = sy / n
        val covXX = (sxx / n) - mx*mx
        val covYY = (syy / n) - my*my
        val covXY = (sxy / n) - mx*my

        // principal direction (eigenvector with larger eigenvalue)
        val t = covXX + covYY
        val d = sqrt(max(0f, t*t - 4f*(covXX*covYY - covXY*covXY)))
        val l1 = 0.5f*(t + d)
        val vx = if (abs(covXY) > 1e-6f) covXY else 1f
        val vy = if (abs(covXY) > 1e-6f) (l1 - covXX) else 0f
        val len = hypot(vx, vy).coerceAtLeast(1e-6f)

        // return unit direction and centroid
        return PointF(vx/len, vy/len) to PointF(mx, my)
    }

    // RMS perpendicular distance to the fitted line over [i0..i1]
    private fun lineRmsPerp(pts: List<PointF>, i0: Int, i1: Int): Float {
        val fit = lineFit(pts, i0, i1)   // avoid destructuring ambiguity
        val dir = fit.first              // unit direction
        val ctr = fit.second             // point on line (centroid)

        val nx = -dir.y; val ny = dir.x
        var sum2 = 0f; var n = 0
        for (i in i0..i1) {
            val p = pts[i]
            val dx = p.x - ctr.x; val dy = p.y - ctr.y
            val dev = abs(nx*dx + ny*dy)
            sum2 += dev*dev; n++
        }
        return sqrt(sum2 / max(1, n))
    }










    private fun circleFrom3(a: PointF, b: PointF, c: PointF): Triple<Float, Float, Float>? {
        val d = 2f * (a.x*(b.y - c.y) + b.x*(c.y - a.y) + c.x*(a.y - b.y))
        if (abs(d) < 1e-3f) return null
        val ux = ((a.x*a.x + a.y*a.y)*(b.y - c.y) + (b.x*b.x + b.y*b.y)*(c.y - a.y) + (c.x*c.x + c.y*c.y)*(a.y - b.y)) / d
        val uy = ((a.x*a.x + a.y*a.y)*(c.x - b.x) + (b.x*b.x + b.y*b.y)*(a.x - c.x) + (c.x*c.x + c.y*c.y)*(b.x - a.x)) / d
        val r = hypot(a.x - ux, a.y - uy)
        return Triple(ux, uy, r)
    }

    private fun circleLeastSquares(pts: List<PointF>): Triple<Float, Float, Float>? {
        var sumX=0f; var sumY=0f; var sumX2=0f; var sumY2=0f; var sumXY=0f; var sumX3=0f; var sumY3=0f; var sumX1Y2=0f; var sumX2Y1=0f
        val n = pts.size.toFloat()
        for (p in pts) {
            val x=p.x; val y=p.y; val x2=x*x; val y2=y*y
            sumX+=x; sumY+=y; sumX2+=x2; sumY2+=y2; sumXY+=x*y
            sumX3+=x2*x; sumY3+=y2*y; sumX1Y2+=x*y2; sumX2Y1+=x2*y
        }
        val C = n*sumX2 - sumX*sumX
        val D = n*sumXY - sumX*sumY
        val E = n*sumX3 + n*sumX1Y2 - (sumX2 + sumY2)*sumX
        val G = n*sumY2 - sumY*sumY
        val H = n*sumX2Y1 + n*sumY3 - (sumX2 + sumY2)*sumY
        val denom = 2f*(C*G - D*D)
        if (abs(denom) < 1e-3f) return null
        val cx = (G*E - D*H) / denom
        val cy = (C*H - D*E) / denom
        val r  = sqrt(((sumX2 + sumY2 - 2f*(cx*sumX + cy*sumY)) / n) + cx*cx + cy*cy)
        return if (r.isFinite()) Triple(cx, cy, r) else null
    }

    private fun rdpPF(pts: List<PointF>, epsilon: Float): List<PointF> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size) { false }
        keep[0] = true; keep[pts.size-1] = true
        fun recurse(s: Int, e: Int) {
            var maxDist = 0f; var idx = -1
            val a = pts[s]; val b = pts[e]
            for (i in s+1 until e) {
                val d = pointLineDistancePF(pts[i], a, b)
                if (d > maxDist) { maxDist = d; idx = i }
            }
            if (maxDist > epsilon && idx != -1) {
                keep[idx] = true
                recurse(s, idx); recurse(idx, e)
            }
        }
        recurse(0, pts.size-1)
        val out = ArrayList<PointF>()
        for (i in pts.indices) if (keep[i]) out.add(pts[i])
        return out
    }

    private fun pointLineDistancePF(p: PointF, a: PointF, b: PointF): Float {
        val vx = b.x - a.x; val vy = b.y - a.y
        val len = hypot(vx, vy).coerceAtLeast(1e-3f)
        return abs((vy * p.x - vx * p.y + b.x * a.y - b.y * a.x) / len)
    }

    // Minimum distance from point P to any segment in the polyline
    private fun minDistToPolylinePF(pts: List<PointF>, p: PointF): Float {
        if (pts.size < 2) return Float.POSITIVE_INFINITY
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val d = pointLineDistancePF(p, a, b)
            if (d < best) best = d
        }
        return best
    }

    // Returns true if any segment passes within tol of the start point (ignoring the very first short segment)
    private fun crossesNearStart(pts: List<PointF>, start: PointF, tol: Float): Boolean {
        if (pts.size < 3) return false
        // skip the first 1–2 segments to avoid trivially hitting the start
        val skip = (pts.size * 0.08f).roundToInt().coerceIn(1, 3)
        for (i in (skip + 1) until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (pointLineDistancePF(start, a, b) <= tol) return true
        }
        // also accept if endpoint is near any interior part of the polyline
        return minDistToPolylinePF(pts.subList(1, pts.size - 1), pts.last()) <= tol
    }


    private fun enforceClosedAndCornersPF(simplified: List<PointF>): List<PointF> {
        if (simplified.isEmpty()) return simplified
        val out = ArrayList(simplified)
        if (dist(out.first(), out.last()) <= CLOSE_EPS) out.removeAt(out.lastIndex)
        return out
    }

    private fun angleDegPF(a: PointF, b: PointF, c: PointF): Float {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val dot = v1x*v2x + v1y*v2y
        val l1 = hypot(v1x, v1y).coerceAtLeast(1e-3f)
        val l2 = hypot(v2x, v2y).coerceAtLeast(1e-3f)
        val cos = (dot / (l1*l2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun arcAngleDegrees(cx: Float, cy: Float, a: PointF, b: PointF): Float {
        val a1 = atan2(a.y - cy, a.x - cx)
        val a2 = atan2(b.y - cy, b.x - cx)
        var d = Math.toDegrees((a2 - a1).toDouble()).toFloat()
        while (d < 0f) d += 360f
        while (d >= 360f) d -= 360f
        return d
    }

    private fun normalizedSweep(sa: Float, mid: Float, ea: Float): Float {
        fun norm(a: Float): Float { var x=a; while (x<0) x+= (2f*Math.PI).toFloat(); while (x>=2f*Math.PI) x-= (2f*Math.PI).toFloat(); return x }
        val s = norm(sa); val m = norm(mid); val e = norm(ea)
        var sweep = e - s; if (sweep < 0) sweep += (2f*Math.PI).toFloat()
        // ensure the mid angle lies on the sweep path; else go the other way
        val onPathCW = if (s <= e) (m in s..e) else (m >= s || m <= e)
        if (!onPathCW) sweep = sweep - (2f*Math.PI).toFloat()
        return sweep
    }

    // ===== Export & Share =====

    /**
     * Render the current page (white background + committed HL/Ink + optional overlays) to a new Bitmap.
     * Size = view size in pixels.
     */
    fun renderCurrentDocumentBitmap(includeSelectionOverlays: Boolean = false): Bitmap {
        // Compose the whole document (all pages) at current view width and full content height
        val totalH = max(1, (contentHeightPx()).roundToInt())
        val bmp = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        // Draw each section at its content Y
        for (i in sections.indices) {
            val s = sections[i]
            c.save()
            c.translate(0f, s.yOffsetPx)
            committedHLBySection.getOrNull(i)?.let  { c.drawBitmap(it, 0f, 0f, null) }
            committedInkBySection.getOrNull(i)?.let { c.drawBitmap(it, 0f, 0f, null) }
            scratchHLBySection.getOrNull(i)?.let    { c.drawBitmap(it, 0f, 0f, null) }
            scratchInkBySection.getOrNull(i)?.let   { c.drawBitmap(it, 0f, 0f, null) }

            if (includeSelectionOverlays) {
                drawSelectionOverlay(c) // already in content coords
                marqueePath?.let { path -> c.drawPath(path, marqueeFill); c.drawPath(path, marqueeOutline) }
            }
            c.restore()
        }
        return bmp
    }


    /**
     * Export the current page to a single-page PDF and write to [output].
     * [dpi] controls the page point size; default 300 DPI.
     * Returns true on success.
     */
    // ---- Compatibility alias for legacy callers (kept during migration) ----
    /**
     * Legacy name kept for MainActivity and older call sites.
     * Internally forwards to the new document-wide renderer.
     */
    fun renderCurrentPageBitmap(includeSelectionOverlays: Boolean = false): Bitmap {
        return renderCurrentDocumentBitmap(includeSelectionOverlays)
    }

    fun exportToPdf(output: OutputStream, dpi: Int = 300): Boolean {
        if (width <= 0 || height <= 0) return false
        // Render a bitmap first (without selection overlays)
        val pageBitmap = renderCurrentDocumentBitmap(includeSelectionOverlays = false)

        val doc = PdfDocument()
        try {
            // Convert pixel size to PDF points (1 point = 1/72 inch)
            val pageWidthPt  = (pageBitmap.width  * 72f / dpi).roundToInt()
            val pageHeightPt = (pageBitmap.height * 72f / dpi).roundToInt()

            val info = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, 1).create()
            val page = doc.startPage(info)
            val canvas = page.canvas

            // Fit the bitmap exactly to the page
            val dst = Rect(0, 0, pageWidthPt, pageHeightPt)
            val src = Rect(0, 0, pageBitmap.width, pageBitmap.height)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(pageBitmap, src, dst, null)

            doc.finishPage(page)
            doc.writeTo(output)
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            try { doc.close() } catch (_: Throwable) {}
        }
        // Back-compat helpers (aliases) — safe to leave in place
        @Suppress("FunctionName")
        fun setStrokeWidthDP(sizeDp: Float) { setStrokeWidthDp(sizeDp) }

    }

}

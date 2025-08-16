package com.pelicankb.jobnotes

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.pelicankb.jobnotes.canvas.NoteCanvasView

class MainActivity : AppCompatActivity() {

    private lateinit var noteCanvas: NoteCanvasView
    private val TAG = "JOBNOTES"

    // Track which panel (if any) is open so tapping the same icon hides it.
    private var currentlyShownPanelId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        noteCanvas = findViewById(R.id.noteCanvas)

        // Keep Undo/Redo enabled state fresh in menu
        noteCanvas.onStackChanged = { invalidateOptionsMenu(); updateSubtitle() }

        // Wire all optional controls we recognize by ID or tag/contentDescription
        wireOptionalControls()

        updateSubtitle()
    }

    // region Toolbar menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // If your project still includes menu_main.xml with actions, inflate it.
        // Safe to call even if you eventually remove those items.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = noteCanvas.canUndo()
        menu.findItem(R.id.action_redo)?.isEnabled = noteCanvas.canRedo()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stylus -> {
                setCurrentTool(NoteCanvasView.Tool.STYLUS)
                noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
                showOnlyPanel(findViewById<View>(R.id.panelStylus)?.id)
                updateSubtitle()
                true
            }
            R.id.action_highlighter -> {
                setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
                noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
                showOnlyPanel(findViewById<View>(R.id.panelHighlighter)?.id)
                updateSubtitle()
                true
            }
            R.id.action_eraser -> {
                setCurrentTool(NoteCanvasView.Tool.ERASER)
                noteCanvas.setTool(NoteCanvasView.Tool.ERASER)
                showOnlyPanel(findViewById<View>(R.id.panelEraser)?.id)
                updateSubtitle()
                true
            }
            R.id.action_select -> {
                setCurrentTool(NoteCanvasView.Tool.SELECT)
                noteCanvas.setTool(NoteCanvasView.Tool.SELECT)
                showOnlyPanel(findViewById<View>(R.id.panelSelect)?.id)
                updateSubtitle()
                true
            }
            R.id.action_undo -> { noteCanvas.undo(); updateSubtitle(); true }
            R.id.action_redo -> { noteCanvas.redo(); updateSubtitle(); true }
            R.id.action_clear -> { noteCanvas.clearAll(); updateSubtitle(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    // endregion

    // region Panel show/hide
    /**
     * Show exactly one panel by its view-id. If the same panel is already visible,
     * hide all panels (toggle behavior). If id is null or panel not found, hide all.
     */
    private fun showOnlyPanel(panelId: Int?) {
        val container = findViewById<View>(R.id.panelContainer) ?: return
        val panels = intArrayOf(
            R.id.panelStylus,
            R.id.panelHighlighter,
            R.id.panelEraser,
            R.id.panelColor,
            R.id.panelSelect,          // optional if present
            R.id.panelText,            // optional if present
            R.id.panelFontSize         // optional if present
        )

        // Determine toggle behavior
        val shouldHideAll = (panelId != null && panelId == currentlyShownPanelId)

        panels.forEach { id ->
            val v = container.findViewById<View>(id)
            if (v != null) {
                v.visibility = if (!shouldHideAll && id == panelId) View.VISIBLE else View.GONE
            }
        }

        currentlyShownPanelId = if (shouldHideAll) null else panelId
    }
    // endregion

    // region Debug subtitle (optional)
    private fun updateSubtitle() {
        val tool = when (getCurrentTool()) {
            NoteCanvasView.Tool.STYLUS -> "Pen"
            NoteCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
            NoteCanvasView.Tool.ERASER -> "Eraser"
            NoteCanvasView.Tool.SELECT -> "Select"
        }
        supportActionBar?.subtitle =
            "Tool: $tool  •  Undo=${noteCanvas.canUndo()}  Redo=${noteCanvas.canRedo()}"
    }

    private fun getCurrentTool(): NoteCanvasView.Tool {
        @Suppress("UNCHECKED_CAST")
        return (noteCanvas.getTag(R.id.tag_current_tool) as? NoteCanvasView.Tool)
            ?: NoteCanvasView.Tool.STYLUS
    }

    private fun setCurrentTool(t: NoteCanvasView.Tool) {
        noteCanvas.setTag(R.id.tag_current_tool, t)
    }
    // endregion

    // region Optional wiring for sliders/switches/HSV to canvas
    private fun <T : View> findByName(name: String): T? {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) return null
        @Suppress("UNCHECKED_CAST")
        return findViewById<View>(id) as? T
    }

    private fun wireOptionalControls() {
        // ---- Top-row tool buttons by common IDs (text or icon buttons)
        // Pen/Stylus
        wireToolButton("btnToolPen", NoteCanvasView.Tool.STYLUS, panelToOpenId = R.id.panelStylus)
        wireToolButton("btnToolStylus", NoteCanvasView.Tool.STYLUS, panelToOpenId = R.id.panelStylus)
        wireToolButton("toolStylus", NoteCanvasView.Tool.STYLUS, panelToOpenId = R.id.panelStylus)

        // Highlighter
        wireToolButton("btnToolHighlighter", NoteCanvasView.Tool.HIGHLIGHTER, panelToOpenId = R.id.panelHighlighter)
        wireToolButton("toolHighlighter", NoteCanvasView.Tool.HIGHLIGHTER, panelToOpenId = R.id.panelHighlighter)

        // Eraser
        wireToolButton("btnToolEraser", NoteCanvasView.Tool.ERASER, panelToOpenId = R.id.panelEraser)
        wireToolButton("toolEraser", NoteCanvasView.Tool.ERASER, panelToOpenId = R.id.panelEraser)

        // Select
        wireToolButton("btnToolSelect", NoteCanvasView.Tool.SELECT, panelToOpenId = R.id.panelSelect)
        wireToolButton("toolSelect", NoteCanvasView.Tool.SELECT, panelToOpenId = R.id.panelSelect)

        // Color panel opener (doesn't change tool)
        findByName<View>("btnToolColor")?.setOnClickListener {
            showOnlyPanel(R.id.panelColor)
            Log.d(TAG, "Wired btnToolColor -> open Color panel (toggle)")
        }
        findByName<View>("toolPalette")?.setOnClickListener {
            showOnlyPanel(R.id.panelColor)
            Log.d(TAG, "Wired toolPalette -> open Color panel (toggle)")
        }

        // ---- Auto-wire via tag/contentDescription: "tool:stylus" / "tool:highlighter" / "tool:eraser" / "tool:select"
        val root = findViewById<View>(R.id.root) ?: window.decorView
        scanAndWireToolViews(root)

        // ---- Stylus brush buttons (fountain / marker etc.)
        findByName<View>("btnBrushFountain")?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
            Log.d(TAG, "Brush -> Fountain")
            updateSubtitle()
        }
        findByName<View>("btnBrushMarker")?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
            Log.d(TAG, "Brush -> Marker")
            updateSubtitle()
        }

        // ---- Sliders
        findByName<Slider>("sliderStylusSize")?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusSize(value)
            Log.d(TAG, "Stylus size -> $value")
        }

        findByName<Slider>("sliderHighlightSize")?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlighterSize(value)
            Log.d(TAG, "Highlighter size -> $value")
        }

        findByName<Slider>("sliderHighlightOpacity")?.addOnChangeListener { _, raw, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
            Log.d(TAG, "Highlighter opacity -> $v")
        }

        findByName<Slider>("sliderEraserSize")?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserSize(value)
            Log.d(TAG, "Eraser size -> $value")
        }

        findByName<CompoundButton>("switchEraseHighlightsOnly")?.setOnCheckedChangeListener { _, checked ->
            noteCanvas.setEraseHighlightsOnly(checked)
            Log.d(TAG, "EraseHighlightsOnly -> $checked")
        }

        // ---- Highlighter mode buttons
        findByName<View>("btnHlFreeform")?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
            Log.d(TAG, "Highlighter mode -> FREEFORM")
        }
        findByName<View>("btnHlStraight")?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
            Log.d(TAG, "Highlighter mode -> STRAIGHT")
        }

        // ---- Color picker (Hue/Sat/Val) + preview
        val hue = findByName<Slider>("sliderHue")
        val sat = findByName<Slider>("sliderSat")
        val vvv = findByName<Slider>("sliderVal")
        val preview = findByName<View>("previewSwatch")
        val previewCard = preview as? MaterialCardView

        fun clamp01(x: Float): Float = when {
            x.isNaN() -> 0f
            x < 0f -> 0f
            x > 1f -> 1f
            else -> x
        }

        fun currentColor(): Int? {
            if (hue == null || sat == null || vvv == null) return null
            val h = hue.value.coerceIn(0f, 360f)
            val s = if (sat.value > 1f) sat.value / 100f else sat.value
            val v = if (vvv.value > 1f) vvv.value / 100f else vvv.value
            val hsv = floatArrayOf(h, clamp01(s), clamp01(v))
            return android.graphics.Color.HSVToColor(hsv)
        }

        fun applyColor(c: Int) {
            // Apply to both pen & highlighter by default
            noteCanvas.setStylusColor(c)
            noteCanvas.setHighlighterColor(c)
            previewCard?.setCardBackgroundColor(c)
            preview?.setBackgroundColor(c)
        }

        val onColorChanged = Slider.OnChangeListener { _, _, _ ->
            try {
                currentColor()?.let { c ->
                    applyColor(c)
                    Log.d(TAG, "Color -> #${Integer.toHexString(c)}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Color picker error suppressed", t)
            }
        }

        hue?.addOnChangeListener(onColorChanged)
        sat?.addOnChangeListener(onColorChanged)
        vvv?.addOnChangeListener(onColorChanged)
    }

    private fun ensureTool(t: NoteCanvasView.Tool) {
        val current = getCurrentTool()
        if (current != t) {
            noteCanvas.setTool(t)
            setCurrentTool(t)
            Log.d(TAG, "Tool -> $t (ensure)")
            updateSubtitle()
        }
    }

    private fun wireToolButton(
        idName: String,
        tool: NoteCanvasView.Tool,
        panelToOpenId: Int? = null
    ) {
        findByName<View>(idName)?.let { v ->
            v.setOnClickListener {
                noteCanvas.setTool(tool)
                setCurrentTool(tool)
                if (panelToOpenId != null) showOnlyPanel(panelToOpenId)
                Log.d(TAG, "Wired $idName -> $tool (panel=$panelToOpenId)")
                updateSubtitle()
            }
        }
    }

    private fun scanAndWireToolViews(root: View) {
        fun matchesToken(s: CharSequence?, token: String): Boolean =
            s?.toString()?.trim()?.equals(token, ignoreCase = true) == true

        fun attach(v: View, tool: NoteCanvasView.Tool, why: String, panelId: Int?) {
            v.setOnClickListener {
                noteCanvas.setTool(tool)
                setCurrentTool(tool)
                if (panelId != null) showOnlyPanel(panelId)
                Log.d(TAG, "Wired via $why -> $tool on viewId=${safeIdName(v)}")
                updateSubtitle()
            }
        }

        fun dfs(v: View) {
            val tag = v.tag
            val cd = v.contentDescription

            when {
                matchesToken(tag as? CharSequence, "tool:stylus") ||
                        matchesToken(cd, "tool:stylus") ->
                    attach(v, NoteCanvasView.Tool.STYLUS, "tag/cd", R.id.panelStylus)

                matchesToken(tag as? CharSequence, "tool:highlighter") ||
                        matchesToken(cd, "tool:highlighter") ->
                    attach(v, NoteCanvasView.Tool.HIGHLIGHTER, "tag/cd", R.id.panelHighlighter)

                matchesToken(tag as? CharSequence, "tool:eraser") ||
                        matchesToken(cd, "tool:eraser") ->
                    attach(v, NoteCanvasView.Tool.ERASER, "tag/cd", R.id.panelEraser)

                matchesToken(tag as? CharSequence, "tool:select") ||
                        matchesToken(cd, "tool:select") ->
                    attach(v, NoteCanvasView.Tool.SELECT, "tag/cd", R.id.panelSelect)

                matchesToken(tag as? CharSequence, "tool:palette") ||
                        matchesToken(cd, "tool:palette") -> {
                    v.setOnClickListener { showOnlyPanel(R.id.panelColor) }
                }
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) dfs(v.getChildAt(i))
            }
        }
        dfs(root)
    }

    private fun safeIdName(v: View): String {
        return try {
            if (v.id == View.NO_ID) "NO_ID"
            else resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            "UNKNOWN_ID"
        }
    }
    // endregion
}

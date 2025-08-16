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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.pelicankb.jobnotes.canvas.NoteCanvasView

class MainActivity : AppCompatActivity() {

    private lateinit var noteCanvas: NoteCanvasView
    private val TAG = "JOBNOTES"

    // Which dropdown is currently shown
    private enum class Panel { NONE, STYLUS, HIGHLIGHTER, ERASER, COLOR }
    private var currentPanel: Panel = Panel.NONE

    // Views we flip between
    private lateinit var panelCard: MaterialCardView
    private lateinit var panelStylus: View
    private lateinit var panelHighlighter: View
    private lateinit var panelEraser: View
    private lateinit var panelColor: View

    // Optional: keep track of tool (without exposing getter on canvas)
    private fun getCurrentTool(): NoteCanvasView.Tool {
        @Suppress("UNCHECKED_CAST")
        return (noteCanvas.getTag(R.id.tag_current_tool) as? NoteCanvasView.Tool)
            ?: NoteCanvasView.Tool.STYLUS
    }
    private fun setCurrentTool(t: NoteCanvasView.Tool) {
        noteCanvas.setTag(R.id.tag_current_tool, t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        noteCanvas = findViewById(R.id.noteCanvas)

        // Panels
        panelCard = findViewById(R.id.toolPanelCard)
        panelStylus = findViewById(R.id.panelStylus)
        panelHighlighter = findViewById(R.id.panelHighlighter)
        panelEraser = findViewById(R.id.panelEraser)
        panelColor = findViewById(R.id.panelColor)

        // Start collapsed
        showPanel(Panel.NONE)

        // Keep menu (undo/redo) enabled state fresh
        noteCanvas.onStackChanged = { invalidateOptionsMenu() }

        wireToolRow()          // top row buttons + toggle behavior
        wireStylusPanel()      // fountain/marker + size
        wireHighlighterPanel() // freeform/straight + size + opacity
        wireEraserPanel()      // mode + size + highlights-only
        wireColorPanel()       // HSV sliders + preview

        // No actionbar subtitle (you asked to remove the “Tool: … Undo/Redo…” text)
        // If you ever want it back, call updateSubtitle()
    }

    // region Toolbar menu (Undo/Redo/Clear only)
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
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
            R.id.action_undo -> { noteCanvas.undo(); true }
            R.id.action_redo -> { noteCanvas.redo(); true }
            R.id.action_clear -> { noteCanvas.clearAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    // endregion

    // region Tool row (Pen / Highlighter / Eraser / Color) with dropdown toggle
    private fun wireToolRow() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.toolSelectorGroup)

        // selectionRequired must be false in XML for toggling off (we set that in activity_main.xml below)
        group.addOnButtonCheckedListener { g, checkedId, isChecked ->
            when (checkedId) {
                R.id.btnToolPen -> {
                    if (isChecked) {
                        ensureTool(NoteCanvasView.Tool.STYLUS)
                        showPanel(Panel.STYLUS)
                    } else if (g.checkedButtonId == View.NO_ID) {
                        showPanel(Panel.NONE)
                    }
                }
                R.id.btnToolHighlighter -> {
                    if (isChecked) {
                        ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
                        showPanel(Panel.HIGHLIGHTER)
                    } else if (g.checkedButtonId == View.NO_ID) {
                        showPanel(Panel.NONE)
                    }
                }
                R.id.btnToolEraser -> {
                    if (isChecked) {
                        ensureTool(NoteCanvasView.Tool.ERASER)
                        showPanel(Panel.ERASER)
                    } else if (g.checkedButtonId == View.NO_ID) {
                        showPanel(Panel.NONE)
                    }
                }
                R.id.btnToolColor -> {
                    if (isChecked) {
                        showPanel(Panel.COLOR)
                    } else if (g.checkedButtonId == View.NO_ID) {
                        showPanel(Panel.NONE)
                    }
                }
            }
        }
    }

    private fun showPanel(which: Panel) {
        currentPanel = which
        panelCard.visibility = if (which == Panel.NONE) View.GONE else View.VISIBLE
        panelStylus.visibility = if (which == Panel.STYLUS) View.VISIBLE else View.GONE
        panelHighlighter.visibility = if (which == Panel.HIGHLIGHTER) View.VISIBLE else View.GONE
        panelEraser.visibility = if (which == Panel.ERASER) View.VISIBLE else View.GONE
        panelColor.visibility = if (which == Panel.COLOR) View.VISIBLE else View.GONE
    }

    private fun ensureTool(t: NoteCanvasView.Tool) {
        val current = getCurrentTool()
        if (current != t) {
            noteCanvas.setTool(t)
            setCurrentTool(t)
            Log.d(TAG, "Tool -> $t")
        }
    }
    // endregion

    // region Panels wiring
    private fun wireStylusPanel() {
        // Buttons
        findViewById<View>(R.id.btnBrushFountain)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
        }
        findViewById<View>(R.id.btnBrushMarker)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
        }
        // Size
        findViewById<Slider>(R.id.sliderStylusSize)?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusSize(value)
        }
    }

    private fun wireHighlighterPanel() {
        // Mode
        findViewById<View>(R.id.btnHlFreeform)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
        }
        findViewById<View>(R.id.btnHlStraight)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
        }
        // Size
        findViewById<Slider>(R.id.sliderHighlightSize)?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlighterSize(value)
        }
        // Opacity (0–100 in XML -> 0–1f)
        findViewById<Slider>(R.id.sliderHighlightOpacity)?.addOnChangeListener { _, raw, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
        }
    }

    private fun wireEraserPanel() {
        // Mode
        findViewById<View>(R.id.btnEraserStroke)?.setOnClickListener {
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.STROKE)
        }
        findViewById<View>(R.id.btnEraserArea)?.setOnClickListener {
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.AREA)
        }
        // Size
        findViewById<Slider>(R.id.sliderEraserSize)?.addOnChangeListener { _, value, _ ->
            noteCanvas.setEraserSize(value)
            // you could also update a preview view here if desired
        }
        // Highlights-only
        findViewById<CompoundButton>(R.id.switchEraseHighlightsOnly)
            ?.setOnCheckedChangeListener { _, checked ->
                noteCanvas.setEraseHighlightsOnly(checked)
            }
    }

    private fun wireColorPanel() {
        // Find color controls within the color panel subtree to avoid collisions
        val hue = panelColor.findViewById<Slider>(R.id.sliderHue)
        val sat = panelColor.findViewById<Slider>(R.id.sliderSat)
        val vvv = panelColor.findViewById<Slider>(R.id.sliderVal)
        val preview = panelColor.findViewById<View>(R.id.previewSwatch)
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
            noteCanvas.setStylusColor(c)
            noteCanvas.setHighlighterColor(c)
            previewCard?.setCardBackgroundColor(c)
            preview?.setBackgroundColor(c)
        }

        val onColorChanged = Slider.OnChangeListener { _, _, _ ->
            try {
                currentColor()?.let { applyColor(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "Color picker error suppressed", t)
            }
        }

        hue?.addOnChangeListener(onColorChanged)
        sat?.addOnChangeListener(onColorChanged)
        vvv?.addOnChangeListener(onColorChanged)
    }
    // endregion

    // (Optional) If you ever want a subtitle again:
    @Suppress("unused")
    private fun updateSubtitle() {
        val toolLabel = when (getCurrentTool()) {
            NoteCanvasView.Tool.STYLUS -> "Pen"
            NoteCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
            NoteCanvasView.Tool.ERASER -> "Eraser"
            else -> "Select" // keeps future-proof if enum grows
        }
        supportActionBar?.subtitle =
            "Tool: $toolLabel • Undo=${noteCanvas.canUndo()} Redo=${noteCanvas.canRedo()}"
    }

    // -------- misc helpers (kept from older wiring if you add more IDs later) -------
    private fun safeIdName(v: View): String {
        return try {
            if (v.id == View.NO_ID) "NO_ID"
            else resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            "UNKNOWN_ID"
        }
    }

    private fun <T : View> findByName(name: String): T? {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) return null
        @Suppress("UNCHECKED_CAST")
        return findViewById<View>(id) as? T
    }
}

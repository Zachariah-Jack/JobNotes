package com.pelicankb.jobnotes

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.pelicankb.jobnotes.canvas.NoteCanvasView

class MainActivity : AppCompatActivity() {

    private lateinit var noteCanvas: NoteCanvasView
    private lateinit var toolGroup: MaterialButtonToggleGroup

    private lateinit var toolPanelCard: MaterialCardView
    private lateinit var panelStylus: View
    private lateinit var panelHighlighter: View
    private lateinit var panelEraser: View
    private lateinit var panelColor: View

    private val TAG = "JOBNOTES"
    private val SHOW_DEBUG_SUBTITLE = false  // set true if you want the subtitle back

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        if (!SHOW_DEBUG_SUBTITLE) supportActionBar?.subtitle = null

        // Canvas
        noteCanvas = findViewById(R.id.noteCanvas)

        // Panels
        toolPanelCard = findViewById(R.id.toolPanelCard)
        panelStylus = findViewById(R.id.panelStylus)
        panelHighlighter = findViewById(R.id.panelHighlighter)
        panelEraser = findViewById(R.id.panelEraser)
        panelColor = findViewById(R.id.panelColor)

        // Tool toggle group
        toolGroup = findViewById(R.id.toolSelectorGroup)
        toolGroup.check(R.id.btnToolPen) // default selection
        setToolAndPanel(NoteCanvasView.Tool.STYLUS)

        toolGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnToolPen -> setToolAndPanel(NoteCanvasView.Tool.STYLUS)
                R.id.btnToolHighlighter -> setToolAndPanel(NoteCanvasView.Tool.HIGHLIGHTER)
                R.id.btnToolEraser -> setToolAndPanel(NoteCanvasView.Tool.ERASER)
                R.id.btnToolColor -> { showOnly(panelColor); updateSubtitle() } // show color panel only
            }
        }

        // Keep Undo/Redo enabled state fresh
        noteCanvas.onStackChanged = {
            invalidateOptionsMenu()
            updateSubtitle()
        }

        // Wire per-panel controls
        wireStylusPanel()
        wireHighlighterPanel()
        wireEraserPanel()
        wireColorPanel()

        updateSubtitle()
    }

    // region Toolbar menu
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

    // region Panel wiring
    private fun wireStylusPanel() {
        // Fountain / Marker
        findViewById<View?>(R.id.btnBrushFountain)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
            Log.d(TAG, "Stylus brush -> FOUNTAIN")
        }
        findViewById<View?>(R.id.btnBrushMarker)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
            Log.d(TAG, "Stylus brush -> MARKER")
        }

        // Size
        findViewById<Slider?>(R.id.sliderStylusSize)?.addOnChangeListener { _, v, _ ->
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusSize(v)
        }
    }

    private fun wireHighlighterPanel() {
        // Mode (FREEFORM / STRAIGHT)
        findViewById<MaterialButtonToggleGroup?>(R.id.highlightModeGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
                when (id) {
                    R.id.btnHlFreeform -> noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
                    R.id.btnHlStraight -> noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
                }
                Log.d(TAG, "Highlighter mode set")
            }

        // Tip size
        findViewById<Slider?>(R.id.sliderHighlightSize)?.addOnChangeListener { _, v, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlighterSize(v)
        }

        // Opacity (0..100 -> 0..1)
        findViewById<Slider?>(R.id.sliderHighlightOpacity)?.addOnChangeListener { _, raw, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
        }
    }

    private fun wireEraserPanel() {
        // Mode (STROKE / AREA)
        findViewById<MaterialButtonToggleGroup?>(R.id.eraserModeGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                ensureTool(NoteCanvasView.Tool.ERASER)
                when (id) {
                    R.id.btnEraserStroke -> noteCanvas.setEraserMode(NoteCanvasView.EraserMode.STROKE)
                    R.id.btnEraserArea -> noteCanvas.setEraserMode(NoteCanvasView.EraserMode.AREA)
                }
                Log.d(TAG, "Eraser mode set")
            }

        // Size
        findViewById<Slider?>(R.id.sliderEraserSize)?.addOnChangeListener { _, v, _ ->
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserSize(v)
        }

        // Highlights-only
        findViewById<com.google.android.material.switchmaterial.SwitchMaterial?>(R.id.switchEraseHighlightsOnly)
            ?.setOnCheckedChangeListener { _, isChecked ->
                noteCanvas.setEraseHighlightsOnly(isChecked)
            }
    }

    private fun wireColorPanel() {
        val hue = findViewById<Slider?>(R.id.sliderHue)
        val sat = findViewById<Slider?>(R.id.sliderSat)
        val vvv = findViewById<Slider?>(R.id.sliderVal)

        val preview = findViewById<View?>(R.id.previewSwatch)
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
            // Color both pen & highlighter
            noteCanvas.setStylusColor(c)
            noteCanvas.setHighlighterColor(c)
            previewCard?.setCardBackgroundColor(c)
            preview?.setBackgroundColor(c)
        }

        val onColorChanged = Slider.OnChangeListener { _, _, _ ->
            try {
                currentColor()?.let { applyColor(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "Color picker suppressed error", t)
            }
        }

        hue?.addOnChangeListener(onColorChanged)
        sat?.addOnChangeListener(onColorChanged)
        vvv?.addOnChangeListener(onColorChanged)
    }
    // endregion

    // region Helpers
    private fun setToolAndPanel(tool: NoteCanvasView.Tool) {
        noteCanvas.setTool(tool)
        setCurrentTool(tool)
        when (tool) {
            NoteCanvasView.Tool.STYLUS -> showOnly(panelStylus)
            NoteCanvasView.Tool.HIGHLIGHTER -> showOnly(panelHighlighter)
            NoteCanvasView.Tool.ERASER -> showOnly(panelEraser)
        }
        updateSubtitle()
    }

    private fun showOnly(visiblePanel: View) {
        panelStylus.visibility = if (visiblePanel === panelStylus) View.VISIBLE else View.GONE
        panelHighlighter.visibility = if (visiblePanel === panelHighlighter) View.VISIBLE else View.GONE
        panelEraser.visibility = if (visiblePanel === panelEraser) View.VISIBLE else View.GONE
        panelColor.visibility = if (visiblePanel === panelColor) View.VISIBLE else View.GONE
        toolPanelCard.visibility = View.VISIBLE
    }

    private fun ensureTool(t: NoteCanvasView.Tool) {
        if (getCurrentTool() != t) {
            noteCanvas.setTool(t)
            setCurrentTool(t)
            when (t) {
                NoteCanvasView.Tool.STYLUS -> toolGroup.check(R.id.btnToolPen)
                NoteCanvasView.Tool.HIGHLIGHTER -> toolGroup.check(R.id.btnToolHighlighter)
                NoteCanvasView.Tool.ERASER -> toolGroup.check(R.id.btnToolEraser)
            }
            updateSubtitle()
        }
    }

    private fun getCurrentTool(): NoteCanvasView.Tool {
        @Suppress("UNCHECKED_CAST")
        return (noteCanvas.getTag(R.id.tag_current_tool) as? NoteCanvasView.Tool)
            ?: NoteCanvasView.Tool.STYLUS
    }

    private fun setCurrentTool(t: NoteCanvasView.Tool) {
        noteCanvas.setTag(R.id.tag_current_tool, t)
    }

    /** Hide debug subtitle by default */
    private fun updateSubtitle() {
        if (SHOW_DEBUG_SUBTITLE) {
            val toolName = when (getCurrentTool()) {
                NoteCanvasView.Tool.STYLUS -> "Pen"
                NoteCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
                NoteCanvasView.Tool.ERASER -> "Eraser"
            }
            supportActionBar?.subtitle =
                "Tool: $toolName  •  Undo=${noteCanvas.canUndo()}  Redo=${noteCanvas.canRedo()}"
        } else {
            supportActionBar?.subtitle = null
        }
    }
    // endregion
}

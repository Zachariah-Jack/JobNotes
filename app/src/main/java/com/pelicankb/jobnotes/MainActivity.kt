package com.pelicankb.jobnotes

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.pelicankb.jobnotes.canvas.NoteCanvasView

class MainActivity : AppCompatActivity() {

    private lateinit var noteCanvas: NoteCanvasView
    private lateinit var toolPanelCard: MaterialCardView

    private val TAG = "JOBNOTES"
    private var currentPanelId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.subtitle = null // no descriptive text bar

        noteCanvas = findViewById(R.id.noteCanvas)
        toolPanelCard = findViewById(R.id.toolPanelCard)

        // Keep Undo/Redo buttons in sync
        noteCanvas.onStackChanged = { updateUndoRedoButtons() }

        wireToolRow()
        wirePanels()
        updateUndoRedoButtons()
    }

    // Only keep Clear in the toolbar overflow
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> { noteCanvas.clearAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Tool row wiring ------------------------------------------------------

    private fun wireToolRow() {
        val group: MaterialButtonToggleGroup = findViewById(R.id.toolGroup)

        val btnKeyboard: MaterialButton = findViewById(R.id.btnToolKeyboard)
        val btnPen: MaterialButton = findViewById(R.id.btnToolPen)
        val btnHighlighter: MaterialButton = findViewById(R.id.btnToolHighlighter)
        val btnEraser: MaterialButton = findViewById(R.id.btnToolEraser)
        val btnSelect: MaterialButton = findViewById(R.id.btnToolSelect)

        val btnUndo: MaterialButton = findViewById(R.id.btnUndo)
        val btnRedo: MaterialButton = findViewById(R.id.btnRedo)
        val btnColor: MaterialButton = findViewById(R.id.btnToolColor)

        // Default to Pen tool
        group.check(btnPen.id)
        noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
        showOnlyPanel(R.id.panelStylus)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                btnPen.id -> {
                    noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
                    togglePanelFor(R.id.panelStylus)
                }
                btnHighlighter.id -> {
                    noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
                    togglePanelFor(R.id.panelHighlighter)
                }
                btnEraser.id -> {
                    noteCanvas.setTool(NoteCanvasView.Tool.ERASER)
                    togglePanelFor(R.id.panelEraser)
                }
                btnSelect.id -> {
                    noteCanvas.setTool(NoteCanvasView.Tool.SELECT)
                    togglePanelFor(R.id.panelSelect)
                }
                btnKeyboard.id -> {
                    // Keyboard/text layer to come later
                    // For now, just hide panels and keep last tool
                    hidePanels()
                }
            }
        }

        btnUndo.setOnClickListener { noteCanvas.undo() }
        btnRedo.setOnClickListener { noteCanvas.redo() }
        btnColor.setOnClickListener { togglePanelFor(R.id.panelColor) }
    }

    private fun updateUndoRedoButtons() {
        try {
            val btnUndo: MaterialButton = findViewById(R.id.btnUndo)
            val btnRedo: MaterialButton = findViewById(R.id.btnRedo)
            btnUndo.isEnabled = noteCanvas.canUndo()
            btnRedo.isEnabled = noteCanvas.canRedo()
        } catch (_: Throwable) {
        }
        invalidateOptionsMenu()
    }

    // --- Panels ---------------------------------------------------------------

    private fun wirePanels() {
        // --- Stylus panel
        findViewById<View>(R.id.btnBrushFountain)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
        }
        findViewById<View>(R.id.btnBrushCalligraphy)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.CALLIGRAPHY)
        }
        findViewById<View>(R.id.btnBrushPen)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PEN)
        }
        findViewById<View>(R.id.btnBrushPencil)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PENCIL)
        }
        findViewById<View>(R.id.btnBrushMarker)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
        }
        findViewById<Slider>(R.id.sliderStylusSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setStylusSize(v)
        }

        // --- Highlighter panel
        findViewById<View>(R.id.btnHlFreeform)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
        }
        findViewById<View>(R.id.btnHlStraight)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
        }
        findViewById<Slider>(R.id.sliderHighlightSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setHighlighterSize(v)
        }
        findViewById<Slider>(R.id.sliderHighlightOpacity)?.addOnChangeListener { _, raw, _ ->
            // Slider is 0..100 in XML
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
        }

        // --- Eraser panel
        findViewById<View>(R.id.btnEraserStroke)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.STROKE)
        }
        findViewById<View>(R.id.btnEraserArea)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.AREA)
        }
        findViewById<Slider>(R.id.sliderEraserSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setEraserSize(v)
            // optional: preview circle size could be updated here if you want
        }
        findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchEraseHighlightsOnly)
            ?.setOnCheckedChangeListener { _, checked ->
                noteCanvas.setEraseHighlightsOnly(checked)
            }

        // --- Select panel
        findViewById<View>(R.id.btnSelectRect)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.SELECT)
            noteCanvas.setSelectionMode(NoteCanvasView.SelectionMode.RECT)
        }
        findViewById<View>(R.id.btnSelectLasso)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.SELECT)
            noteCanvas.setSelectionMode(NoteCanvasView.SelectionMode.LASSO)
        }
        findViewById<View>(R.id.btnSelectClear)?.setOnClickListener {
            noteCanvas.clearSelection()
        }

        // --- Color panel (simple HSV sliders + preview)
        val hue = findViewById<Slider>(R.id.sliderHue)
        val sat = findViewById<Slider>(R.id.sliderSat)
        val valv = findViewById<Slider>(R.id.sliderVal)
        val preview = findViewById<View>(R.id.previewSwatch)

        fun clamp01(x: Float) = when {
            x.isNaN() -> 0f
            x < 0f -> 0f
            x > 1f -> 1f
            else -> x
        }
        fun currentColor(): Int? {
            if (hue == null || sat == null || valv == null) return null
            val h = hue.value.coerceIn(0f, 360f)
            val s = if (sat.value > 1f) sat.value / 100f else sat.value
            val v = if (valv.value > 1f) valv.value / 100f else valv.value
            return android.graphics.Color.HSVToColor(floatArrayOf(h, clamp01(s), clamp01(v)))
        }
        fun applyColor(c: Int) {
            noteCanvas.setStylusColor(c)
            noteCanvas.setHighlighterColor(c)
            (preview as? MaterialCardView)?.setCardBackgroundColor(c)
            preview?.setBackgroundColor(c)
        }
        val onColorChanged = Slider.OnChangeListener { _, _, _ ->
            try { currentColor()?.let(::applyColor) }
            catch (t: Throwable) { Log.w(TAG, "Color picker error suppressed", t) }
        }
        hue?.addOnChangeListener(onColorChanged)
        sat?.addOnChangeListener(onColorChanged)
        valv?.addOnChangeListener(onColorChanged)

        // Default preview color
        runCatching { (preview as? MaterialCardView)?.setCardBackgroundColor(Color.BLACK) }
    }

    private fun ensureTool(t: NoteCanvasView.Tool) {
        noteCanvas.setTool(t)
    }

    // --- Panel show/hide with "tap same icon to close" -----------------------
    private fun togglePanelFor(panelId: Int) {
        if (currentPanelId == panelId) {
            hidePanels()
        } else {
            showOnlyPanel(panelId)
        }
    }

    private fun hidePanels() {
        toolPanelCard.visibility = View.GONE
        findViewById<View>(R.id.panelStylus)?.visibility = View.GONE
        findViewById<View>(R.id.panelHighlighter)?.visibility = View.GONE
        findViewById<View>(R.id.panelEraser)?.visibility = View.GONE
        findViewById<View>(R.id.panelSelect)?.visibility = View.GONE
        findViewById<View>(R.id.panelColor)?.visibility = View.GONE
        currentPanelId = null
    }

    private fun showOnlyPanel(panelId: Int) {
        toolPanelCard.visibility = View.VISIBLE
        val panels = listOf(
            R.id.panelStylus, R.id.panelHighlighter, R.id.panelEraser, R.id.panelSelect, R.id.panelColor
        )
        panels.forEach { id ->
            findViewById<View>(id)?.visibility = if (id == panelId) View.VISIBLE else View.GONE
        }
        currentPanelId = panelId
    }
}

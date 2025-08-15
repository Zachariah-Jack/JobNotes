package com.pelicankb.jobnotes

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

    private lateinit var panelCard: MaterialCardView
    private lateinit var panelStylus: View
    private lateinit var panelHighlighter: View
    private lateinit var panelEraser: View
    private lateinit var panelSelect: View
    private lateinit var panelColor: View

    private enum class Panel { STYLUS, HIGHLIGHTER, ERASER, SELECT, COLOR }
    private var visiblePanel: Panel? = Panel.STYLUS

    private val TAG = "JOBNOTES"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        noteCanvas = findViewById(R.id.noteCanvas)

        panelCard = findViewById(R.id.toolPanelCard)
        panelStylus = findViewById(R.id.panelStylus)
        panelHighlighter = findViewById(R.id.panelHighlighter)
        panelEraser = findViewById(R.id.panelEraser)
        panelSelect = findViewById(R.id.panelSelect)
        panelColor = findViewById(R.id.panelColor)

        // Keep Undo/Redo enabled state fresh
        noteCanvas.onStackChanged = { invalidateOptionsMenu() }

        wireTopRow()
        wirePanels()

        // Start with stylus panel shown
        showPanel(Panel.STYLUS, forceShow = true)
    }

    // region Toolbar menu (3-dots)
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
            R.id.action_stylus -> { selectTool(Panel.STYLUS); true }
            R.id.action_highlighter -> { selectTool(Panel.HIGHLIGHTER); true }
            R.id.action_eraser -> { selectTool(Panel.ERASER); true }
            R.id.action_undo -> { noteCanvas.undo(); true }
            R.id.action_redo -> { noteCanvas.redo(); true }
            R.id.action_clear -> { noteCanvas.clearAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    // endregion

    // region Top row
    private fun wireTopRow() {
        val group: MaterialButtonToggleGroup = findViewById(R.id.toolGroup)
        val btnKeyboard: MaterialButton = findViewById(R.id.btnToolKeyboard)
        val btnPen: MaterialButton = findViewById(R.id.btnToolPen)
        val btnHighlighter: MaterialButton = findViewById(R.id.btnToolHighlighter)
        val btnEraser: MaterialButton = findViewById(R.id.btnToolEraser)
        val btnSelect: MaterialButton = findViewById(R.id.btnToolSelect)

        val btnUndo: MaterialButton = findViewById(R.id.btnUndo)
        val btnRedo: MaterialButton = findViewById(R.id.btnRedo)
        val btnColor: MaterialButton = findViewById(R.id.btnToolColor)

        // Actions
        btnUndo.setOnClickListener { noteCanvas.undo() }
        btnRedo.setOnClickListener { noteCanvas.redo() }
        btnColor.setOnClickListener { togglePanel(Panel.COLOR) }

        // Tool buttons – toggle panel if pressing same tool again
        btnPen.setOnClickListener { toggleToolAndPanel(NoteCanvasView.Tool.STYLUS, Panel.STYLUS, group, btnPen) }
        btnHighlighter.setOnClickListener { toggleToolAndPanel(NoteCanvasView.Tool.HIGHLIGHTER, Panel.HIGHLIGHTER, group, btnHighlighter) }
        btnEraser.setOnClickListener { toggleToolAndPanel(NoteCanvasView.Tool.ERASER, Panel.ERASER, group, btnEraser) }
        btnSelect.setOnClickListener { toggleToolAndPanel(NoteCanvasView.Tool.SELECT, Panel.SELECT, group, btnSelect) }

        // Keyboard button is a placeholder that would switch to the keyboard menu set later
        btnKeyboard.setOnClickListener {
            Log.d(TAG, "Keyboard menu placeholder tapped")
            // For now, leave selection on current tool and show stylus panel toggle behavior
            togglePanel(Panel.STYLUS)
        }

        // Default select Pen
        group.check(btnPen.id)
        noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
    }

    private fun toggleToolAndPanel(
        tool: NoteCanvasView.Tool,
        panel: Panel,
        group: MaterialButtonToggleGroup,
        button: MaterialButton
    ) {
        noteCanvas.setTool(tool)
        if (visiblePanel == panel && panelCard.visibility == View.VISIBLE) {
            // Hide if same button tapped again
            showPanel(null)
            // Keep the tool selected; do not uncheck
        } else {
            // Ensure the toggle group reflects this button
            group.check(button.id)
            showPanel(panel)
        }
    }
    // endregion

    // region Panels
    private fun wirePanels() {
        // Stylus
        findViewById<MaterialButton>(R.id.btnBrushFountain)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
        }
        findViewById<MaterialButton>(R.id.btnBrushCalligraphy)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.CALLIGRAPHY)
        }
        findViewById<MaterialButton>(R.id.btnBrushPen)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PEN)
        }
        findViewById<MaterialButton>(R.id.btnBrushPencil)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PENCIL)
        }
        findViewById<MaterialButton>(R.id.btnBrushMarker)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
        }
        findViewById<Slider>(R.id.sliderStylusSize)?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusSize(value)
        }

        // Highlighter
        findViewById<MaterialButton>(R.id.btnHlFreeform)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
        }
        findViewById<MaterialButton>(R.id.btnHlStraight)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
        }
        findViewById<Slider>(R.id.sliderHighlightSize)?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlighterSize(value)
        }
        findViewById<Slider>(R.id.sliderHighlightOpacity)?.addOnChangeListener { _, raw, _ ->
            ensureTool(NoteCanvasView.Tool.HIGHLIGHTER)
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
        }

        // Eraser
        findViewById<MaterialButton>(R.id.btnEraserStroke)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.STROKE)
        }
        findViewById<MaterialButton>(R.id.btnEraserArea)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserMode(NoteCanvasView.EraserMode.AREA)
        }
        findViewById<Slider>(R.id.sliderEraserSize)?.addOnChangeListener { _, value, _ ->
            ensureTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserSize(value)
        }
        findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchEraseHighlightsOnly)
            ?.setOnCheckedChangeListener { _, checked -> noteCanvas.setEraseHighlightsOnly(checked) }

        // Select
        findViewById<MaterialButton>(R.id.btnSelectRect)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.SELECT)
            noteCanvas.setSelectMode(NoteCanvasView.SelectMode.RECT)
        }
        findViewById<MaterialButton>(R.id.btnSelectLasso)?.setOnClickListener {
            ensureTool(NoteCanvasView.Tool.SELECT)
            noteCanvas.setSelectMode(NoteCanvasView.SelectMode.LASSO)
            // LASSO behavior is coming next; the button switches the mode now.
        }
        findViewById<MaterialButton>(R.id.btnSelectClear)?.setOnClickListener {
            noteCanvas.clearSelection()
        }

        // Color (HSV sliders already wired in your layout; keep as-is if you use them)
        // If you also have the HSV sliders in panel_color, you can reuse your earlier code.
    }

    private fun ensureTool(t: NoteCanvasView.Tool) {
        noteCanvas.setTool(t)
    }

    private fun showPanel(which: Panel?, forceShow: Boolean = false) {
        fun vis(v: View, show: Boolean) { v.visibility = if (show) View.VISIBLE else View.GONE }

        if (which == null) {
            panelCard.visibility = View.GONE
            visiblePanel = null
            return
        }

        if (!forceShow && visiblePanel == which && panelCard.visibility == View.VISIBLE) {
            // toggle off
            panelCard.visibility = View.GONE
            visiblePanel = null
            return
        }

        vis(panelStylus, which == Panel.STYLUS)
        vis(panelHighlighter, which == Panel.HIGHLIGHTER)
        vis(panelEraser, which == Panel.ERASER)
        vis(panelSelect, which == Panel.SELECT)
        vis(panelColor, which == Panel.COLOR)

        panelCard.visibility = View.VISIBLE
        visiblePanel = which
    }

    private fun togglePanel(which: Panel) {
        if (visiblePanel == which && panelCard.visibility == View.VISIBLE) {
            showPanel(null)
        } else {
            showPanel(which)
        }
    }

    private fun selectTool(p: Panel) {
        when (p) {
            Panel.STYLUS -> noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
            Panel.HIGHLIGHTER -> noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            Panel.ERASER -> noteCanvas.setTool(NoteCanvasView.Tool.ERASER)
            Panel.SELECT -> noteCanvas.setTool(NoteCanvasView.Tool.SELECT)
            Panel.COLOR -> {}
        }
        showPanel(p)
    }
    // endregion
}

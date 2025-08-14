package com.pelicankb.jobnotes

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.pelicankb.jobnotes.canvas.NoteCanvasView

class MainActivity : AppCompatActivity() {

    private lateinit var noteCanvas: NoteCanvasView

    // Rows
    private lateinit var penRowScroll: View
    private lateinit var kbdRowScroll: View

    // Pen row buttons
    private lateinit var btnSwitchToKeyboard: ImageButton
    private lateinit var btnPenStylus: ImageButton
    private lateinit var btnPenHighlighter: ImageButton
    private lateinit var btnPenEraser: ImageButton
    private lateinit var btnPenSelect: ImageButton
    private lateinit var btnPenUndo: ImageButton
    private lateinit var btnPenRedo: ImageButton
    private lateinit var btnPenColor: ImageButton

    // Keyboard row buttons
    private lateinit var btnSwitchToPen: ImageButton
    private lateinit var btnKbdCheckbox: ImageButton
    private lateinit var btnKbdText: ImageButton
    private lateinit var btnKbdTextColor: ImageButton
    private lateinit var btnKbdTextBgColor: ImageButton
    private lateinit var btnKbdFontSize: TextView
    private lateinit var btnKbdUndo: ImageButton
    private lateinit var btnKbdRedo: ImageButton

    // Dropdown panel card + panels
    private lateinit var toolPanelCard: MaterialCardView
    private lateinit var panelStylus: View
    private lateinit var panelHighlighter: View
    private lateinit var panelEraser: View
    private lateinit var panelColor: View
    private lateinit var panelSelect: View
    private lateinit var panelText: View
    private lateinit var panelFontSize: View

    private var openPanelViewId: Int? = null
    private var openPanelAnchorId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.subtitle = null

        noteCanvas = findViewById(R.id.noteCanvas)

        // Rows
        penRowScroll = findViewById(R.id.penRowScroll)
        kbdRowScroll = findViewById(R.id.kbdRowScroll)

        // Pen buttons
        btnSwitchToKeyboard = findViewById(R.id.btnSwitchToKeyboard)
        btnPenStylus = findViewById(R.id.btnPenStylus)
        btnPenHighlighter = findViewById(R.id.btnPenHighlighter)
        btnPenEraser = findViewById(R.id.btnPenEraser)
        btnPenSelect = findViewById(R.id.btnPenSelect)
        btnPenUndo = findViewById(R.id.btnPenUndo)
        btnPenRedo = findViewById(R.id.btnPenRedo)
        btnPenColor = findViewById(R.id.btnPenColor)

        // Keyboard buttons
        btnSwitchToPen = findViewById(R.id.btnSwitchToPen)
        btnKbdCheckbox = findViewById(R.id.btnKbdCheckbox)
        btnKbdText = findViewById(R.id.btnKbdText)
        btnKbdTextColor = findViewById(R.id.btnKbdTextColor)
        btnKbdTextBgColor = findViewById(R.id.btnKbdTextBgColor)
        btnKbdFontSize = findViewById(R.id.btnKbdFontSize)
        btnKbdUndo = findViewById(R.id.btnKbdUndo)
        btnKbdRedo = findViewById(R.id.btnKbdRedo)

        // Panels
        toolPanelCard = findViewById(R.id.toolPanelCard)
        panelStylus = findViewById(R.id.panelStylus)
        panelHighlighter = findViewById(R.id.panelHighlighter)
        panelEraser = findViewById(R.id.panelEraser)
        panelColor = findViewById(R.id.panelColor)
        panelSelect = findViewById(R.id.panelSelect)
        panelText = findViewById(R.id.panelText)
        panelFontSize = findViewById(R.id.panelFontSize)

        // Default
        showPenRow()
        setTool(NoteCanvasView.Tool.STYLUS)
        showOnly(panelStylus, anchorId = R.id.btnPenStylus)

        noteCanvas.onStackChanged = {
            invalidateOptionsMenu()
            updateUndoRedoButtons()
        }

        // PEN ROW
        btnSwitchToKeyboard.setOnClickListener {
            hidePanels(); showKeyboardRow()
        }
        btnPenStylus.setOnClickListener {
            togglePanel(it, panelStylus) { setTool(NoteCanvasView.Tool.STYLUS) }
        }
        btnPenHighlighter.setOnClickListener {
            togglePanel(it, panelHighlighter) { setTool(NoteCanvasView.Tool.HIGHLIGHTER) }
        }
        btnPenEraser.setOnClickListener {
            togglePanel(it, panelEraser) { setTool(NoteCanvasView.Tool.ERASER) }
        }
        btnPenSelect.setOnClickListener {
            togglePanel(it, panelSelect) { setTool(NoteCanvasView.Tool.SELECT) }
        }
        btnPenUndo.setOnClickListener { noteCanvas.undo(); updateUndoRedoButtons() }
        btnPenRedo.setOnClickListener { noteCanvas.redo(); updateUndoRedoButtons() }
        btnPenColor.setOnClickListener {
            togglePanel(it, panelColor) { /* applies to pen/hl */ }
        }

        // KEYBOARD ROW (placeholders right now)
        btnSwitchToPen.setOnClickListener { hidePanels(); showPenRow() }
        btnKbdCheckbox.setOnClickListener {
            hidePanels(); Toast.makeText(this, "Insert checkbox (placeholder)", Toast.LENGTH_SHORT).show()
        }
        btnKbdText.setOnClickListener {
            togglePanel(it, panelText) { }
            Toast.makeText(this, "Text formatting (placeholder)", Toast.LENGTH_SHORT).show()
        }
        btnKbdTextColor.setOnClickListener { togglePanel(it, panelColor) { } }
        btnKbdTextBgColor.setOnClickListener { togglePanel(it, panelColor) { } }
        btnKbdFontSize.setOnClickListener { togglePanel(it, panelFontSize) { } }
        btnKbdUndo.setOnClickListener { noteCanvas.undo(); updateUndoRedoButtons() }
        btnKbdRedo.setOnClickListener { noteCanvas.redo(); updateUndoRedoButtons() }

        // Wire panels
        wireStylusPanel()
        wireHighlighterPanel()
        wireEraserPanel()
        wireColorPanel()
        wireSelectPanel()

        updateUndoRedoButtons()
    }

    // Toolbar overflow (undo/redo/clear)
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
            R.id.action_undo -> { noteCanvas.undo(); updateUndoRedoButtons(); true }
            R.id.action_redo -> { noteCanvas.redo(); updateUndoRedoButtons(); true }
            R.id.action_clear -> { noteCanvas.clearAll(); updateUndoRedoButtons(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----- Panel + row helpers -----
    private fun showPenRow() { penRowScroll.visibility = View.VISIBLE; kbdRowScroll.visibility = View.GONE }
    private fun showKeyboardRow() { penRowScroll.visibility = View.GONE; kbdRowScroll.visibility = View.VISIBLE }
    private fun setTool(t: NoteCanvasView.Tool) { noteCanvas.setTool(t) }

    private fun togglePanel(anchor: View, panel: View, beforeShow: (() -> Unit)? = null) {
        val isOpen = toolPanelCard.visibility == View.VISIBLE
        val isSame = isOpen && openPanelViewId == panel.id && openPanelAnchorId == anchor.id
        if (isSame) {
            hidePanels()
        } else {
            beforeShow?.invoke()
            showOnly(panel, anchor.id)
        }
    }

    private fun showOnly(visiblePanel: View, anchorId: Int) {
        toolPanelCard.visibility = View.VISIBLE
        panelStylus.visibility = if (visiblePanel === panelStylus) View.VISIBLE else View.GONE
        panelHighlighter.visibility = if (visiblePanel === panelHighlighter) View.VISIBLE else View.GONE
        panelEraser.visibility = if (visiblePanel === panelEraser) View.VISIBLE else View.GONE
        panelColor.visibility = if (visiblePanel === panelColor) View.VISIBLE else View.GONE
        panelSelect.visibility = if (visiblePanel === panelSelect) View.VISIBLE else View.GONE
        panelText.visibility = if (visiblePanel === panelText) View.VISIBLE else View.GONE
        panelFontSize.visibility = if (visiblePanel === panelFontSize) View.VISIBLE else View.GONE
        openPanelViewId = visiblePanel.id
        openPanelAnchorId = anchorId
    }

    private fun hidePanels() {
        toolPanelCard.visibility = View.GONE
        openPanelViewId = null
        openPanelAnchorId = null
    }

    private fun updateUndoRedoButtons() {
        val canUndo = noteCanvas.canUndo()
        val canRedo = noteCanvas.canRedo()
        btnPenUndo.isEnabled = canUndo; btnPenRedo.isEnabled = canRedo
        btnKbdUndo.isEnabled = canUndo; btnKbdRedo.isEnabled = canRedo
    }

    // ----- Panel wiring -----
    private fun wireStylusPanel() {
        findViewById<MaterialButtonToggleGroup?>(R.id.stylusBrushGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                when (id) {
                    R.id.btnBrushFountain -> noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
                    R.id.btnBrushCalligraphy -> noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.CALLIGRAPHY)
                    R.id.btnBrushPen -> noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PEN)
                    R.id.btnBrushPencil -> noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.PENCIL)
                    R.id.btnBrushMarker -> noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
                }
            }
        findViewById<Slider?>(R.id.sliderStylusSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setStylusSize(v)
        }
    }

    private fun wireHighlighterPanel() {
        findViewById<MaterialButtonToggleGroup?>(R.id.highlightModeGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                when (id) {
                    R.id.btnHlFreeform -> noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
                    R.id.btnHlStraight -> noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
                }
            }
        findViewById<Slider?>(R.id.sliderHighlightSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setHighlighterSize(v)
        }
        findViewById<Slider?>(R.id.sliderHighlightOpacity)?.addOnChangeListener { _, raw, _ ->
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
        }
    }

    private fun wireEraserPanel() {
        findViewById<MaterialButtonToggleGroup?>(R.id.eraserModeGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                when (id) {
                    R.id.btnEraserStroke -> noteCanvas.setEraserMode(NoteCanvasView.EraserMode.STROKE)
                    R.id.btnEraserArea -> noteCanvas.setEraserMode(NoteCanvasView.EraserMode.AREA)
                }
            }
        findViewById<Slider?>(R.id.sliderEraserSize)?.addOnChangeListener { _, v, _ ->
            noteCanvas.setEraserSize(v)
        }
        findViewById<com.google.android.material.switchmaterial.SwitchMaterial?>(R.id.switchEraseHighlightsOnly)
            ?.setOnCheckedChangeListener { _, checked -> noteCanvas.setEraseHighlightsOnly(checked) }
    }

    private fun wireColorPanel() {
        val hue = findViewById<Slider?>(R.id.sliderHue)
        val sat = findViewById<Slider?>(R.id.sliderSat)
        val vvv = findViewById<Slider?>(R.id.sliderVal)
        val preview = findViewById<View?>(R.id.previewSwatch)
        val previewCard = preview as? MaterialCardView

        fun clamp01(x: Float) = when {
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
        val listener = Slider.OnChangeListener { _, _, _ -> currentColor()?.let { applyColor(it) } }
        hue?.addOnChangeListener(listener)
        sat?.addOnChangeListener(listener)
        vvv?.addOnChangeListener(listener)
    }

    private fun wireSelectPanel() {
        findViewById<MaterialButtonToggleGroup?>(R.id.selectModeGroup)
            ?.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val mode = when (id) {
                    R.id.btnSelectLasso -> NoteCanvasView.SelectMode.LASSO
                    else -> NoteCanvasView.SelectMode.RECT
                }
                noteCanvas.setSelectMode(mode)
            }
    }
}

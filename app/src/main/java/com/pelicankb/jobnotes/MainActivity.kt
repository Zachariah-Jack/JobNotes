package com.pelicankb.jobnotes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pelicankb.jobnotes.drawing.BrushType
import com.pelicankb.jobnotes.drawing.InkCanvasView
import com.pelicankb.jobnotes.ui.BrushPreviewView
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

class MainActivity : AppCompatActivity() {

    // ───────── Panels / groups ─────────
    private lateinit var inkCanvas: InkCanvasView

    private lateinit var panelPen: View
    private lateinit var panelKeyboard: View
    private lateinit var groupPenChips: View

    // Title
    private lateinit var noteTitle: EditText

    // Pen color chips (left of pen toolbar)
    private lateinit var chip1: ImageButton
    private lateinit var chip2: ImageButton
    private lateinit var chip3: ImageButton
    private var selectedChipId: Int = R.id.chipColor1

    // Which “family” is active on the canvas
    private enum class ToolFamily { PEN_FAMILY, HIGHLIGHTER }
    private var toolFamily: ToolFamily = ToolFamily.PEN_FAMILY

    // Stylus (pen family) popup + state
    private enum class BrushTypeLocal { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    private var brushType: BrushTypeLocal = BrushTypeLocal.PEN
    private var brushSizeDp: Float = 4f
    private var stylusPopup: PopupWindow? = null

    // Keep pen-family color separate from highlighter color
    private var penFamilyColor: Int = Color.BLACK

    // Highlighter popup + state (separate from pen family)
    private enum class HighlighterMode { FREEFORM, STRAIGHT }
    private var highlighterMode: HighlighterMode = HighlighterMode.FREEFORM
    private var highlighterPopup: PopupWindow? = null
    private var highlighterColor: Int = 0x66FFD54F.toInt() // amber ~60% alpha
    private var highlighterSizeDp: Float = 12f

    // Preset colors (used by both palettes)
    private val PRESET_COLORS = intArrayOf(
        0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(),
        0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(),
        0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(),
        0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFFF5722.toInt(),
        0xFF795548.toInt(), 0xFF9E9E9E.toInt(), 0xFF607D8B.toInt(), 0xFF000000.toInt(),
        0xFFFFFFFF.toInt(), 0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFB2FF59.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inkCanvas = findViewById(R.id.inkCanvas)
        // Initialize canvas with defaults for the pen family
        inkCanvas.setBrush(BrushType.PEN)
        inkCanvas.setStrokeWidthDp(brushSizeDp)

        // Optional undo/redo (IDs may differ in your layout)
        findViewById<View?>(R.id.btnUndoPen)?.setOnClickListener { inkCanvas.undo() }
        findViewById<View?>(R.id.btnRedoPen)?.setOnClickListener { inkCanvas.redo() }

        // ───────── Bind views ─────────
        noteTitle = findViewById(R.id.noteTitle)
        panelPen = findViewById(R.id.panelPen)
        panelKeyboard = findViewById(R.id.panelKeyboard)
        groupPenChips = findViewById(R.id.groupPenChips)

        chip1 = findViewById(R.id.chipColor1)
        chip2 = findViewById(R.id.chipColor2)
        chip3 = findViewById(R.id.chipColor3)

        // Show pen tools by default
        showPenMenu()

        // Expand hit areas on toggles
        findViewById<View>(R.id.btnKeyboardToggle).expandTouchTarget(12)
        findViewById<View>(R.id.btnPenToggle).expandTouchTarget(12)

        // Title behavior
        noteTitle.setOnClickListener {
            noteTitle.requestFocus()
            noteTitle.selectAll()
            showKeyboard(noteTitle)
        }
        noteTitle.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) noteTitle.post { noteTitle.selectAll() } else hideKeyboard(v)
        }
        noteTitle.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v); v.clearFocus(); true
            } else false
        }
        if (savedInstanceState == null && noteTitle.text.isNullOrBlank()) {
            noteTitle.setText("Untitled")
        }

        // Default pen-chip colors
        chip1.imageTintList = ColorStateList.valueOf(Color.BLACK)
        chip2.imageTintList = ColorStateList.valueOf(0xFFFFD54F.toInt())
        chip3.imageTintList = ColorStateList.valueOf(0xFF2196F3.toInt())

        // Chip clicks (pen family only)
        val chipClick = View.OnClickListener { v ->
            val chip = v as ImageButton
            if (chip.id != selectedChipId) {
                selectChip(chip.id)
            } else {
                showColorPaletteDialog(chip) // pen palette
            }
        }
        chip1.setOnClickListener(chipClick)
        chip2.setOnClickListener(chipClick)
        chip3.setOnClickListener(chipClick)

        // Initialize pen color on canvas
        selectChip(selectedChipId)
        penFamilyColor = currentPenColor()

        // Highlighter button → switch family, push HL state, open popup
        findViewById<View>(R.id.btnHighlighter).setOnClickListener { v ->
            toolFamily = ToolFamily.HIGHLIGHTER
            inkCanvas.setBrush(
                if (highlighterMode == HighlighterMode.FREEFORM)
                    BrushType.HIGHLIGHTER_FREEFORM
                else
                    BrushType.HIGHLIGHTER_STRAIGHT
            )
            inkCanvas.setStrokeWidthDp(highlighterSizeDp)
            inkCanvas.setColor(highlighterColor)
            toggleHighlighterPopup(v)
        }

        // Stylus button → switch back to pen family, restore pen color, open popup
        findViewById<View>(R.id.btnStylus).setOnClickListener { v ->
            toolFamily = ToolFamily.PEN_FAMILY
            inkCanvas.setColor(penFamilyColor)
            toggleStylusPopup(v)
        }
    }

    // Tap outside EditText: clear focus & hide keyboard
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    hideKeyboard(v)
                }
            }
        }
        return handled
    }

    // ───────── Toolbar menu toggles ─────────
    fun onKeyboardToggleClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        showKeyboardMenu()
        Toast.makeText(this, "Keyboard menu", Toast.LENGTH_SHORT).show()
    }

    fun onPenToggleClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        showPenMenu()
        Toast.makeText(this, "Pen menu", Toast.LENGTH_SHORT).show()
    }

    private fun showPenMenu() {
        panelPen.visibility = View.VISIBLE
        panelKeyboard.visibility = View.GONE
        groupPenChips.visibility = View.VISIBLE
    }

    private fun showKeyboardMenu() {
        panelPen.visibility = View.GONE
        panelKeyboard.visibility = View.VISIBLE
        groupPenChips.visibility = View.GONE
    }

    // ───────── Pen chip helpers (family‑aware) ─────────
    private fun currentPenColor(): Int {
        return when (selectedChipId) {
            chip1.id -> chip1.imageTintList?.defaultColor
            chip2.id -> chip2.imageTintList?.defaultColor
            chip3.id -> chip3.imageTintList?.defaultColor
            else -> null
        } ?: Color.BLACK
    }

    private fun selectChip(id: Int) {
        selectedChipId = id
        chip1.isSelected = (id == chip1.id)
        chip2.isSelected = (id == chip2.id)
        chip3.isSelected = (id == chip3.id)

        val activeColor = currentPenColor()
        penFamilyColor = activeColor
        if (toolFamily == ToolFamily.PEN_FAMILY) {
            inkCanvas.setColor(activeColor)
        }
    }

    private fun showColorPaletteDialog(targetChip: ImageButton) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_color_palette, null, false)
        val container = dialogView.findViewById<LinearLayout>(R.id.paletteContainer)

        val columns = 5
        val tileSize = 48.dp()
        val tileMargin = 8.dp()

        var tempColor = targetChip.imageTintList?.defaultColor ?: Color.BLACK
        var selectedTile: View? = null

        fun makeRow() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun makeTile() = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
            setImageResource(R.drawable.ic_tool_color)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_color_chip_selector)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            isSelected = false
        }

        var row = makeRow()
        PRESET_COLORS.forEachIndexed { index, color ->
            if (index % columns == 0) {
                if (index != 0) container.addView(row)
                row = makeRow()
            }
            val tile = makeTile().apply {
                imageTintList = ColorStateList.valueOf(color)
                setOnClickListener {
                    selectedTile?.isSelected = false
                    isSelected = true
                    selectedTile = this
                    tempColor = color
                }
            }
            if (color == tempColor) {
                tile.isSelected = true
                selectedTile = tile
            }
            row.addView(tile)
        }
        container.addView(row)

        // custom color
        val customRow = makeRow()
        val customTile = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
            setImageResource(R.drawable.ic_tool_palette)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_color_chip_selector)
            setOnClickListener {
                showAdvancedColorPicker(tempColor) { picked ->
                    tempColor = picked
                    selectedTile?.isSelected = false
                    isSelected = true
                    selectedTile = this
                }
            }
        }
        customRow.addView(customTile)
        container.addView(customRow)

        AlertDialog.Builder(this)
            .setTitle("Pick a color")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { d, _ ->
                targetChip.imageTintList = ColorStateList.valueOf(tempColor)
                if (targetChip.id == selectedChipId) {
                    penFamilyColor = tempColor
                    if (toolFamily == ToolFamily.PEN_FAMILY) {
                        inkCanvas.setColor(tempColor)
                    }
                }
                d.dismiss()
            }
            .show()
    }

    private fun showAdvancedColorPicker(initialColor: Int, onSelected: (Int) -> Unit) {
        ColorPickerDialog.Builder(this)
            .setTitle("Pick any color")
            .setPreferenceName("note_color_picker")
            .setPositiveButton("OK", ColorEnvelopeListener { envelope, _ ->
                onSelected(envelope.color)
            })
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .attachBrightnessSlideBar(true)
            .attachAlphaSlideBar(false)
            .setBottomSpace(12)
            .show()
    }

    // ───────── Stylus (pen family) popup ─────────
    private fun toggleStylusPopup(anchor: View) {
        val existing = stylusPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            stylusPopup = null
            return
        }
        stylusPopup = createStylusMenuPopup().also { popup ->
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            popup.showAsDropDown(anchor, 0, 8.dp())
        }
    }

    private fun createStylusMenuPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_stylus_menu, null, false)

        val btnFountain = content.findViewById<ImageButton>(R.id.iconFountain)
        val btnCallig   = content.findViewById<ImageButton>(R.id.iconCalligraphy)
        val btnPen      = content.findViewById<ImageButton>(R.id.iconPen)
        val btnPencil   = content.findViewById<ImageButton>(R.id.iconPencil)
        val btnMarker   = content.findViewById<ImageButton>(R.id.iconMarker)

        val sizeSlider  = content.findViewById<SeekBar>(R.id.sizeSlider)
        val sizeLabel   = content.findViewById<TextView>(R.id.sizeValue)
        val preview     = content.findViewById<BrushPreviewView>(R.id.brushPreview)

        fun setBrushSelectionUI(type: BrushTypeLocal) {
            btnFountain.isSelected = (type == BrushTypeLocal.FOUNTAIN)
            btnCallig.isSelected   = (type == BrushTypeLocal.CALLIGRAPHY)
            btnPen.isSelected      = (type == BrushTypeLocal.PEN)
            btnPencil.isSelected   = (type == BrushTypeLocal.PENCIL)
            btnMarker.isSelected   = (type == BrushTypeLocal.MARKER)
        }
        setBrushSelectionUI(brushType)

        sizeSlider.max = 60
        sizeSlider.progress = brushSizeDp.toInt().coerceIn(1, 60)
        sizeLabel.text = "${sizeSlider.progress} dp"
        preview.setSample(brushType.name, sizeSlider.progress.dp().toFloat())

        // When stylus popup opens, push pen state and mark family active
        applyPenFamilyBrush()

        val onBrushClick = View.OnClickListener { v ->
            brushType = when (v.id) {
                R.id.iconFountain    -> BrushTypeLocal.FOUNTAIN
                R.id.iconCalligraphy -> BrushTypeLocal.CALLIGRAPHY
                R.id.iconPen         -> BrushTypeLocal.PEN
                R.id.iconPencil      -> BrushTypeLocal.PENCIL
                R.id.iconMarker      -> BrushTypeLocal.MARKER
                else -> brushType
            }
            setBrushSelectionUI(brushType)
            preview.setSample(brushType.name, sizeSlider.progress.dp().toFloat())
            applyPenFamilyBrush()
        }
        btnFountain.setOnClickListener(onBrushClick)
        btnCallig.setOnClickListener(onBrushClick)
        btnPen.setOnClickListener(onBrushClick)
        btnPencil.setOnClickListener(onBrushClick)
        btnMarker.setOnClickListener(onBrushClick)

        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val clamped = value.coerceIn(1, 60)
                sizeLabel.text = "$clamped dp"
                preview.setSample(brushType.name, clamped.dp().toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                brushSizeDp = (seekBar?.progress ?: brushSizeDp.toInt())
                    .toFloat()
                    .coerceIn(1f, 60f)
                if (toolFamily == ToolFamily.PEN_FAMILY) inkCanvas.setStrokeWidthDp(brushSizeDp)
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            elevation = 8f
            setOnDismissListener { stylusPopup = null }
        }
    }

    private fun applyPenFamilyBrush() {
        toolFamily = ToolFamily.PEN_FAMILY
        inkCanvas.setStrokeWidthDp(brushSizeDp)
        inkCanvas.setBrush(
            when (brushType) {
                BrushTypeLocal.FOUNTAIN    -> BrushType.FOUNTAIN
                BrushTypeLocal.CALLIGRAPHY -> BrushType.CALLIGRAPHY
                BrushTypeLocal.PEN         -> BrushType.PEN
                BrushTypeLocal.PENCIL      -> BrushType.PENCIL
                BrushTypeLocal.MARKER      -> BrushType.MARKER
            }
        )
        inkCanvas.setColor(currentPenColor())
    }

    // ───────── Highlighter popup (mode/color + size + preview) ─────────
    private fun toggleHighlighterPopup(anchor: View) {
        val existing = highlighterPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            highlighterPopup = null
            return
        }
        highlighterPopup = createHighlighterPopup().also { popup ->
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            popup.showAsDropDown(anchor, 0, 8.dp())
        }
    }

    private fun createHighlighterPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_highlighter_menu, null, false)

        // Views
        val iconFree = content.findViewById<ImageButton>(R.id.iconHLFreeform)
        val iconLine = content.findViewById<ImageButton>(R.id.iconHLStraight)
        val chip     = content.findViewById<ImageButton>(R.id.chipHLColor)
        val slider   = content.findViewById<SeekBar>(R.id.sizeSliderHL)
        val sizeTxt  = content.findViewById<TextView>(R.id.sizeValueHL)
        val preview  = content.findViewById<BrushPreviewView>(R.id.previewHL)

        // Mode UI
        fun updateModeUI() {
            iconFree.isSelected = (highlighterMode == HighlighterMode.FREEFORM)
            iconLine.isSelected = (highlighterMode == HighlighterMode.STRAIGHT)
        }
        updateModeUI()

        val modeClick = View.OnClickListener { v ->
            highlighterMode = if (v.id == R.id.iconHLStraight)
                HighlighterMode.STRAIGHT else HighlighterMode.FREEFORM
            updateModeUI()
            if (toolFamily == ToolFamily.HIGHLIGHTER) {
                inkCanvas.setBrush(
                    if (highlighterMode == HighlighterMode.FREEFORM)
                        BrushType.HIGHLIGHTER_FREEFORM
                    else
                        BrushType.HIGHLIGHTER_STRAIGHT
                )
            }
        }
        iconFree.setOnClickListener(modeClick)
        iconLine.setOnClickListener(modeClick)

        // Color chip (HL-only)
        chip.imageTintList = ColorStateList.valueOf(highlighterColor)
        chip.setOnClickListener {
            showAdvancedColorPicker(highlighterColor) { picked ->
                // If user picks opaque, enforce ~60% alpha for HL look
                val argb = if ((picked ushr 24) == 0xFF) {
                    (0x66 shl 24) or (picked and 0x00FFFFFF)
                } else picked
                highlighterColor = argb
                chip.imageTintList = ColorStateList.valueOf(highlighterColor)
                if (toolFamily == ToolFamily.HIGHLIGHTER) {
                    inkCanvas.setColor(highlighterColor)
                }
            }
        }

        // Size slider + live preview
        slider.max = 60
        slider.progress = highlighterSizeDp.toInt().coerceIn(1, 60)
        sizeTxt.text = "${slider.progress} dp"
        preview.setSample("HIGHLIGHTER", slider.progress.dp().toFloat())

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val clamped = value.coerceIn(1, 60)
                sizeTxt.text = "$clamped dp"
                preview.setSample("HIGHLIGHTER", clamped.dp().toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                highlighterSizeDp = (seekBar?.progress ?: highlighterSizeDp.toInt())
                    .toFloat()
                    .coerceIn(1f, 60f)
                if (toolFamily == ToolFamily.HIGHLIGHTER) {
                    inkCanvas.setStrokeWidthDp(highlighterSizeDp)
                }
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(
                ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup)
            )
            elevation = 8f
            setOnDismissListener { highlighterPopup = null }
        }
    }

    // Utilities
    private fun showKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        target.post { target.requestFocus(); imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}

/** Expands a view's tap target by [extraDp] on all sides (requires parent is a View). */
private fun View.expandTouchTarget(extraDp: Int) {
    val parentView = this.parent as? View ?: return
    parentView.post {
        val rect = Rect()
        getHitRect(rect)
        val extraPx = (extraDp * resources.displayMetrics.density).toInt()
        rect.left -= extraPx
        rect.top -= extraPx
        rect.right += extraPx
        rect.bottom += extraPx
        parentView.touchDelegate = TouchDelegate(rect, this)
    }
}

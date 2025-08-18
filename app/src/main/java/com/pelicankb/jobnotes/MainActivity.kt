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

// Skydoves ColorPicker
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

// Your brush preview view
import com.pelicankb.jobnotes.ui.BrushPreviewView

class MainActivity : AppCompatActivity() {

    // ───────── Panels / groups ─────────
    private lateinit var panelPen: View
    private lateinit var panelKeyboard: View
    private lateinit var groupPenChips: View

    // Title
    private lateinit var noteTitle: EditText

    // Color chips (left side of pen toolbar)
    private lateinit var chip1: ImageButton
    private lateinit var chip2: ImageButton
    private lateinit var chip3: ImageButton
    private var selectedChipId: Int = R.id.chipColor1

    // Stylus menu state
    private enum class BrushType { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    private var brushType: BrushType = BrushType.PEN
    private var brushSizeDp: Float = 4f
    private var stylusPopup: PopupWindow? = null

    // 24 rounded-out presets (Material-ish)
    private val PRESET_COLORS = intArrayOf(
        0xFFF44336.toInt(), // red
        0xFFE91E63.toInt(), // pink
        0xFF9C27B0.toInt(), // purple
        0xFF673AB7.toInt(), // deep purple
        0xFF3F51B5.toInt(), // indigo
        0xFF2196F3.toInt(), // blue
        0xFF03A9F4.toInt(), // light blue
        0xFF00BCD4.toInt(), // cyan
        0xFF009688.toInt(), // teal
        0xFF4CAF50.toInt(), // green
        0xFF8BC34A.toInt(), // light green
        0xFFCDDC39.toInt(), // lime
        0xFFFFEB3B.toInt(), // yellow
        0xFFFFC107.toInt(), // amber
        0xFFFF9800.toInt(), // orange
        0xFFFF5722.toInt(), // deep orange
        0xFF795548.toInt(), // brown
        0xFF9E9E9E.toInt(), // gray
        0xFF607D8B.toInt(), // blue gray
        0xFF000000.toInt(), // black
        0xFFFFFFFF.toInt(), // white
        0xFF7E57C2.toInt(), // violet alt
        0xFF26A69A.toInt(), // teal alt
        0xFFB2FF59.toInt()  // lime A200
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ───────── Bind views ─────────
        noteTitle = findViewById(R.id.noteTitle)
        panelPen = findViewById(R.id.panelPen)
        panelKeyboard = findViewById(R.id.panelKeyboard)
        groupPenChips = findViewById(R.id.groupPenChips)

        chip1 = findViewById(R.id.chipColor1)
        chip2 = findViewById(R.id.chipColor2)
        chip3 = findViewById(R.id.chipColor3)

        // Default: Pen menu visible
        showPenMenu()

        // Expand hit area around the two toggle buttons
        findViewById<View>(R.id.btnKeyboardToggle).expandTouchTarget(extraDp = 12)
        findViewById<View>(R.id.btnPenToggle).expandTouchTarget(extraDp = 12)

        // Title behavior (tap to edit; tap elsewhere to leave editing)
        noteTitle.setOnClickListener {
            noteTitle.requestFocus()
            noteTitle.selectAll()
            showKeyboard(noteTitle)
        }
        noteTitle.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                noteTitle.post { noteTitle.selectAll() }
            } else {
                hideKeyboard(v)
            }
        }
        noteTitle.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                v.clearFocus()
                true
            } else false
        }
        if (savedInstanceState == null && noteTitle.text.isNullOrBlank()) {
            noteTitle.setText("Untitled")
        }

        // Color chips: tap once to select active chip, tap again to open palette
        val chipClick = View.OnClickListener { v ->
            val chip = v as ImageButton
            if (chip.id != selectedChipId) {
                selectChip(chip.id)
            } else {
                showColorPaletteDialog(chip)
            }
        }
        chip1.setOnClickListener(chipClick)
        chip2.setOnClickListener(chipClick)
        chip3.setOnClickListener(chipClick)
        selectChip(selectedChipId)

        // Stylus menu button (2nd icon in Pen toolbar must have id: btnStylus)
        findViewById<View>(R.id.btnStylus).setOnClickListener { v ->
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

    // ───────── Color chips helpers ─────────
    private fun selectChip(id: Int) {
        selectedChipId = id
        chip1.isSelected = (id == chip1.id)
        chip2.isSelected = (id == chip2.id)
        chip3.isSelected = (id == chip3.id)
        // (Later: propagate active color to drawing tool)
    }

    private fun showColorPaletteDialog(targetChip: ImageButton) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_color_palette, null, false)
        val container = dialogView.findViewById<LinearLayout>(R.id.paletteContainer)

        // Build a 5-column grid programmatically
        val columns = 5
        val tileSize = 48.dp()
        val tileMargin = 8.dp()

        var tempColor = targetChip.imageTintList?.defaultColor ?: Color.BLACK
        var selectedTile: View? = null

        fun makeRow(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        fun makeTile(): ImageButton {
            return ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                    setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                    weight = 0f
                }
                setImageResource(R.drawable.ic_tool_color)
                background = ContextCompat.getDrawable(
                    this@MainActivity, R.drawable.bg_color_chip_selector
                )
                // avoid import confusion; use fully qualified ScaleType:
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                isSelected = false
            }
        }

        // Add preset color tiles
        var row: LinearLayout = makeRow()
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
            // Preselect if it matches current chip color
            if (color == tempColor) {
                tile.isSelected = true
                selectedTile = tile
            }
            row.addView(tile)
        }
        container.addView(row)

        // Row with a “custom color” tile
        val customRow = makeRow()
        val customTile = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
            setImageResource(R.drawable.ic_tool_palette)
            background = ContextCompat.getDrawable(
                this@MainActivity, R.drawable.bg_color_chip_selector
            )
            contentDescription = getString(R.string.content_desc_text_color)
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
                applyChipColor(targetChip, tempColor)
                d.dismiss()
            }
            .show()
    }

    private fun applyChipColor(chip: ImageButton, color: Int) {
        chip.imageTintList = ColorStateList.valueOf(color)
        // (The chip remains selected/active)
    }

    private fun showAdvancedColorPicker(initialColor: Int, onSelected: (Int) -> Unit) {
        ColorPickerDialog.Builder(this)
            .setTitle("Pick any color")
            .setPreferenceName("note_color_picker")
            .setPositiveButton(
                "OK",
                ColorEnvelopeListener { envelope, _ -> onSelected(envelope.color) }
            )
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .attachBrightnessSlideBar(true)
            .attachAlphaSlideBar(false)
            .setBottomSpace(12)
            .show()
    }

    // ───────── Stylus menu (drop‑down card) ─────────
    private fun toggleStylusPopup(anchor: View) {
        val existing = stylusPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            stylusPopup = null
            return
        }
        stylusPopup = createStylusMenuPopup().also { popup ->
            // showAsDropDown keeps it feeling like a “menu”
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            popup.showAsDropDown(anchor, /*xOff*/0, /*yOff*/8.dp())
        }
    }

    private fun createStylusMenuPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_stylus_menu, null, false)

        // Brush buttons
        val btnFountain = content.findViewById<ImageButton>(R.id.iconFountain)
        val btnCallig   = content.findViewById<ImageButton>(R.id.iconCalligraphy)
        val btnPen      = content.findViewById<ImageButton>(R.id.iconPen)
        val btnPencil   = content.findViewById<ImageButton>(R.id.iconPencil)
        val btnMarker   = content.findViewById<ImageButton>(R.id.iconMarker)

        // Size slider + live preview
        val sizeSlider  = content.findViewById<SeekBar>(R.id.sizeSlider)
        val sizeLabel   = content.findViewById<TextView>(R.id.sizeValue)
        val preview     = content.findViewById<BrushPreviewView>(R.id.brushPreview)

        // Init selection UI
        fun setBrushSelectionUI(type: BrushType) {
            btnFountain.isSelected = (type == BrushType.FOUNTAIN)
            btnCallig.isSelected   = (type == BrushType.CALLIGRAPHY)
            btnPen.isSelected      = (type == BrushType.PEN)
            btnPencil.isSelected   = (type == BrushType.PENCIL)
            btnMarker.isSelected   = (type == BrushType.MARKER)
        }
        setBrushSelectionUI(brushType)

        // Init slider from dp → px preview
        sizeSlider.max = 60   // max ~60dp
        sizeSlider.progress = brushSizeDp.toInt().coerceIn(1, 60)
        sizeLabel.text = "${sizeSlider.progress} dp"
        preview.setSample(brushType.name, sizeSlider.progress.dp().toFloat())

        // Brush button handlers
        val onBrushClick = View.OnClickListener { v ->
            brushType = when (v.id) {
                R.id.iconFountain    -> BrushType.FOUNTAIN
                R.id.iconCalligraphy -> BrushType.CALLIGRAPHY
                R.id.iconPen         -> BrushType.PEN
                R.id.iconPencil      -> BrushType.PENCIL
                R.id.iconMarker      -> BrushType.MARKER
                else                 -> brushType
            }
            setBrushSelectionUI(brushType)
            // Update preview with same size
            preview.setSample(brushType.name, sizeSlider.progress.dp().toFloat())
        }
        btnFountain.setOnClickListener(onBrushClick)
        btnCallig.setOnClickListener(onBrushClick)
        btnPen.setOnClickListener(onBrushClick)
        btnPencil.setOnClickListener(onBrushClick)
        btnMarker.setOnClickListener(onBrushClick)

        // Slider change
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val clamped = value.coerceIn(1, 60)
                sizeLabel.text = "$clamped dp"
                preview.setSample(brushType.name, clamped.dp().toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Persist in dp for tools
                brushSizeDp = (seekBar?.progress ?: brushSizeDp.toInt()).toFloat().coerceIn(1f, 60f)
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            // Card-like background + elevation for shadow
            setBackgroundDrawable(
                ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup)
            )
            elevation = 8f
            setOnDismissListener { stylusPopup = null }
        }
    }

    // ───────── Keyboard helpers ─────────
    private fun showKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        target.post {
            target.requestFocus()
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    // dp → px helper (kept **inside** the Activity so `resources` is in scope)
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

package com.pelicankb.jobnotes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pelicankb.jobnotes.canvas.NoteCanvasView
import com.pelicankb.jobnotes.canvas.ToolType
import kotlin.math.abs

private enum class TopMenu { PEN, KEYBOARD }
private enum class Panel { NONE, STYLUS, HIGHLIGHTER, ERASER, COLOR }
private enum class ColorTarget { PEN, HIGHLIGHTER }

class MainActivity : AppCompatActivity() {

    // Core views
    private lateinit var canvasView: NoteCanvasView
    private lateinit var toolPanelCard: MaterialCardView
    private lateinit var panelStylus: View
    private lateinit var panelHighlighter: View
    private lateinit var panelEraser: View
    private lateinit var panelColor: View

    // Top rows
    private lateinit var rowPen: LinearLayout
    private lateinit var rowKeyboard: LinearLayout

    // State
    private var currentTopMenu = TopMenu.PEN
    private var openPanel = Panel.NONE
    private var colorTarget: ColorTarget = ColorTarget.PEN
    private var currentPenColor = Color.BLACK
    private var currentHlColor = Color.YELLOW

    // Font-size state (live number button)
    private var currentFontSize = 12
    private lateinit var btnFontSize: TextView
    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    /** Allowed sizes: 6–20 step 1, 22–32 step 2, 36–64 step 4. */
    private val allowedFontSizes: List<Int> by lazy {
        buildList {
            for (i in 6..20) add(i)
            for (i in 22..32 step 2) add(i)
            for (i in 36..64 step 4) add(i)
        }
    }

    // Favorites persistence
    private val prefs by lazy { getSharedPreferences("color_favorites", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find core
        canvasView = findViewById(R.id.noteCanvas)
        toolPanelCard = findViewById(R.id.toolPanelCard)
        panelStylus = findViewById(R.id.panelStylus)
        panelHighlighter = findViewById(R.id.panelHighlighter)
        panelEraser = findViewById(R.id.panelEraser)
        panelColor = findViewById(R.id.panelColor)
        rowPen = findViewById(R.id.rowPen)
        rowKeyboard = findViewById(R.id.rowKeyboard)

        // restore font size first (needed before building keyboard row)
        currentFontSize = settings.getInt("font_size", 12)

        buildPenRow()
        buildKeyboardRow()
        wireStylusPanel()
        wireHighlighterPanel()
        wireEraserPanel()
        buildColorPanel()

        // Defaults
        canvasView.setTool(ToolType.PEN)
        canvasView.setPenColor(currentPenColor)
        canvasView.setHighlighterColor(currentHlColor)
        canvasView.setPenWidth(6f)
        canvasView.setHighlighterWidth(12f)
        canvasView.setHighlighterOpacity(60)
        canvasView.setEraserWidth(28f)
    }

    // ---------- Rows ----------

    private fun buildPenRow() {
        rowPen.removeAllViews()

        // 1) Keyboard switch
        rowPen.addView(iconBtn(R.drawable.ic_tool_keyboard) {
            showKeyboardRow()
        })

        // 2) Pen
        rowPen.addView(iconBtn(R.drawable.ic_tool_pen, active = true) {
            colorTarget = ColorTarget.PEN
            canvasView.setTool(ToolType.PEN)
            togglePanel(Panel.STYLUS)
            highlightActiveTopIcon(it)
        })

        // 3) Highlighter
        rowPen.addView(iconBtn(R.drawable.ic_tool_highlighter) {
            colorTarget = ColorTarget.HIGHLIGHTER
            canvasView.setTool(ToolType.HIGHLIGHTER)
            togglePanel(Panel.HIGHLIGHTER)
            highlightActiveTopIcon(it)
        })

        // 4) Eraser
        rowPen.addView(iconBtn(R.drawable.ic_tool_eraser) {
            canvasView.setTool(ToolType.ERASER)
            togglePanel(Panel.ERASER)
            highlightActiveTopIcon(it)
        })

        // 5) Rectangle select (lasso later)
        rowPen.addView(iconBtn(R.drawable.ic_tool_select_rect) {
            Toast.makeText(this, "Rectangle select coming soon", Toast.LENGTH_SHORT).show()
            highlightActiveTopIcon(it)
        })

        // 6) Undo
        rowPen.addView(iconBtn(R.drawable.ic_tool_undo) {
            canvasView.undo()
        })

        // 7) Redo
        rowPen.addView(iconBtn(R.drawable.ic_tool_redo) {
            canvasView.redo()
        })

        // 8) Color palette
        rowPen.addView(iconBtn(R.drawable.ic_tool_palette) {
            togglePanel(Panel.COLOR)
            // no active highlight change here
        })
    }

    private fun buildKeyboardRow() {
        rowKeyboard.removeAllViews()

        // 1) Pen toggle (return to Pen menu)
        rowKeyboard.addView(iconBtn(R.drawable.ic_tool_pen_toggle) {
            showPenRow()
        })

        // 2) Checkbox (placeholder)
        rowKeyboard.addView(iconBtn(R.drawable.ic_kbd_checkbox) {
            Toast.makeText(this, "Checkbox insert coming soon", Toast.LENGTH_SHORT).show()
        })

        // 3) Text tools (placeholder)
        rowKeyboard.addView(iconBtn(R.drawable.ic_kbd_text) {
            Toast.makeText(this, "Text tools coming soon", Toast.LENGTH_SHORT).show()
        })

        // 4) Text color (placeholder)
        rowKeyboard.addView(iconBtn(R.drawable.ic_kbd_text_color) {
            Toast.makeText(this, "Text color coming soon", Toast.LENGTH_SHORT).show()
        })

        // 5) Text background color (placeholder)
        rowKeyboard.addView(iconBtn(R.drawable.ic_kbd_text_bg) {
            Toast.makeText(this, "Background color coming soon", Toast.LENGTH_SHORT).show()
        })

        // 6) Font size NUMBER button (live, dynamic)
        btnFontSize = sizeNumberBtn { showFontSizePicker() }
        updateFontSizeBtnText()
        rowKeyboard.addView(btnFontSize)

        // 7) Undo (for now: canvas undo)
        rowKeyboard.addView(iconBtn(R.drawable.ic_kbd_undo) {
            canvasView.undo()
        })
    }

    private fun showKeyboardRow() {
        currentTopMenu = TopMenu.KEYBOARD
        findViewById<View>(R.id.scrollPenRow).isGone = true
        findViewById<View>(R.id.scrollKeyboardRow).isVisible = true
        closePanel()
    }

    private fun showPenRow() {
        currentTopMenu = TopMenu.PEN
        findViewById<View>(R.id.scrollKeyboardRow).isGone = true
        findViewById<View>(R.id.scrollPenRow).isVisible = true
        closePanel()
    }

    // ---------- Panels wiring ----------

    private fun wireStylusPanel() {
        val group = panelStylus.findViewById<MaterialButtonToggleGroup>(R.id.stylusBrushGroup)
        val slider = panelStylus.findViewById<Slider>(R.id.sliderStylusSize)

        group.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            // All stylus variants map to PEN tool (for now)
            canvasView.setTool(ToolType.PEN)
            colorTarget = ColorTarget.PEN
        }

        // Defaults: fountain selected
        panelStylus.findViewById<MaterialButton>(R.id.btnBrushFountain).isChecked = true

        slider.addOnChangeListener { _, v, _ ->
            canvasView.setPenWidth(v)
        }
    }

    private fun wireHighlighterPanel() {
        val group = panelHighlighter.findViewById<MaterialButtonToggleGroup>(R.id.highlightModeGroup)
        val size = panelHighlighter.findViewById<Slider>(R.id.sliderHighlightSize)
        val opacity = panelHighlighter.findViewById<Slider>(R.id.sliderHighlightOpacity)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val straight = checkedId == R.id.btnHlStraight
            canvasView.setHighlighterStraight(straight)
        }
        panelHighlighter.findViewById<MaterialButton>(R.id.btnHlFreeform).isChecked = true

        size.addOnChangeListener { _, v, _ ->
            canvasView.setHighlighterWidth(v)
        }
        opacity.addOnChangeListener { _, v, _ ->
            canvasView.setHighlighterOpacity(v.toInt())
        }
    }

    private fun wireEraserPanel() {
        val group = panelEraser.findViewById<MaterialButtonToggleGroup>(R.id.eraserModeGroup)
        val size = panelEraser.findViewById<Slider>(R.id.sliderEraserSize)
        val onlyHl = panelEraser.findViewById<SwitchMaterial>(R.id.switchEraseHighlightsOnly)
        val preview = panelEraser.findViewById<View>(R.id.eraserPreview)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btnEraserStroke) {
                Toast.makeText(this, "Stroke eraser coming soon", Toast.LENGTH_SHORT).show()
            }
        }
        panelEraser.findViewById<MaterialButton>(R.id.btnEraserArea).isChecked = true

        size.addOnChangeListener { _, v, _ ->
            canvasView.setEraserWidth(v)
            val d = dp(v) // approx preview size
            preview.layoutParams = (preview.layoutParams).apply {
                width = d
                height = d
            }
            preview.requestLayout()
            preview.background = circleDrawable(Color.LTGRAY)
        }

        onlyHl.setOnCheckedChangeListener { _, isChecked ->
            // Future: route CLEAR to highlight layer only.
            canvasView.setEraseByStroke(isChecked) // repurpose for now; no-op
        }
    }

    private fun buildColorPanel() {
        val favRow = panelColor.findViewById<LinearLayout>(R.id.favoritesRow)
        val grid = panelColor.findViewById<GridLayout>(R.id.paletteGrid)

        favRow.removeAllViews()
        grid.removeAllViews()

        // 6 favorites
        repeat(6) { idx ->
            val color = getFavorite(idx)
            val v = if (color == null) emptyFavoriteDot { pickCustomColor(idx) }
            else colorDot(color) { onColorChosen(color, fromFavorite = true) }
            favRow.addView(v)
        }

        // even palette
        val palette = listOf(
            0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(),
            0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(),
            0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(),
            0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFFF5722.toInt(),
            0xFF795548.toInt(), 0xFF9E9E9E.toInt(), 0xFF607D8B.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt()
        )

        grid.columnCount = 8
        palette.forEach { c ->
            grid.addView(colorDot(c) { onColorChosen(c, fromFavorite = false) })
        }
    }

    private fun onColorChosen(color: Int, fromFavorite: Boolean) {
        when (colorTarget) {
            ColorTarget.PEN -> {
                currentPenColor = color
                canvasView.setPenColor(color)
            }
            ColorTarget.HIGHLIGHTER -> {
                currentHlColor = color
                canvasView.setHighlighterColor(color)
            }
        }
        // tint the opacity slider to hint the color
        val opacity = panelHighlighter.findViewById<Slider>(R.id.sliderHighlightOpacity)
        opacity.trackActiveTintList = ColorStateList.valueOf(color)

        if (!fromFavorite) {
            // No-op for now.
        }
    }

    // ---------- Panel visibility / animation ----------

    private fun togglePanel(which: Panel) {
        if (openPanel == which && toolPanelCard.isVisible) {
            closePanel()
            return
        }
        openPanel = which
        showOnlyPanel(which)
        if (toolPanelCard.isGone) {
            toolPanelCard.translationY = -toolPanelCard.height.toFloat()
            toolPanelCard.isVisible = true
            toolPanelCard.alpha = 0f
            toolPanelCard.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun closePanel() {
        openPanel = Panel.NONE
        toolPanelCard.isVisible = false
        showOnlyPanel(Panel.NONE)
    }

    private fun showOnlyPanel(which: Panel) {
        panelStylus.isVisible = which == Panel.STYLUS
        panelHighlighter.isVisible = which == Panel.HIGHLIGHTER
        panelEraser.isVisible = which == Panel.ERASER
        panelColor.isVisible = which == Panel.COLOR
    }

    // ---------- Helpers: icon buttons, color dots, prefs ----------

    private fun iconBtn(
        drawableRes: Int,
        active: Boolean = false,
        onClick: (View) -> Unit
    ): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
                gravity = Gravity.CENTER
            }
            setImageResource(drawableRes)
            background = getDrawable(android.R.drawable.list_selector_background_borderless)
            imageTintList = ColorStateList.valueOf(
                if (active) getColorCompat(R.color.tool_icon_active) else getColorCompat(R.color.tool_icon_inactive)
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = resources.getResourceEntryName(drawableRes)
            setOnClickListener(onClick)
        }
    }

    private fun highlightActiveTopIcon(clicked: View) {
        val row = if (currentTopMenu == TopMenu.PEN) rowPen else rowKeyboard
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            if (v is ImageButton) {
                v.imageTintList = ColorStateList.valueOf(
                    if (v == clicked) getColorCompat(R.color.tool_icon_active)
                    else getColorCompat(R.color.tool_icon_inactive)
                )
            }
        }
    }

    private fun colorDot(color: Int, onClick: () -> Unit): View {
        val v = View(this)
        v.layoutParams = GridLayout.LayoutParams(
            ViewGroup.MarginLayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
        )
        v.background = circleDrawable(color)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun emptyFavoriteDot(onPick: () -> Unit): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
        v.background = ringDrawable(Color.LTGRAY)
        v.setOnClickListener { onPick() }
        return v
    }

    private fun pickCustomColor(slot: Int) {
        // simple HSV picker
        val view = layoutInflater.inflate(R.layout.dialog_hsv_picker, null)
        val preview = view.findViewById<View>(R.id.previewSwatch)
        val sHue = view.findViewById<Slider>(R.id.sliderHue)
        val sSat = view.findViewById<Slider>(R.id.sliderSat)
        val sVal = view.findViewById<Slider>(R.id.sliderVal)

        fun updatePreview() {
            val c = Color.HSVToColor(
                floatArrayOf(sHue.value, sSat.value / 100f, sVal.value / 100f)
            )
            preview.background = circleDrawable(c)
        }

        sHue.addOnChangeListener { _, _, _ -> updatePreview() }
        sSat.addOnChangeListener { _, _, _ -> updatePreview() }
        sVal.addOnChangeListener { _, _, _ -> updatePreview() }
        updatePreview()

        MaterialAlertDialogBuilder(this)
            .setTitle("Pick a color")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val color = Color.HSVToColor(floatArrayOf(sHue.value, sSat.value / 100f, sVal.value / 100f))
                saveFavorite(slot, color)
                buildColorPanel()
                onColorChosen(color, fromFavorite = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getFavorite(i: Int): Int? {
        val k = "fav_$i"
        return if (prefs.contains(k)) prefs.getInt(k, Color.BLACK) else null
    }

    private fun saveFavorite(i: Int, color: Int) {
        prefs.edit().putInt("fav_$i", color).apply()
    }

    // ---------- Font size: number button + dialog ----------

    private fun sizeNumberBtn(onClick: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) }
            setPadding(dp(12), 0, dp(8), 0)
            textSize = 16f
            setTextColor(getColorCompat(R.color.tool_icon_inactive))
            background = getDrawable(android.R.drawable.list_selector_background_borderless)
            gravity = Gravity.CENTER_VERTICAL
            isAllCaps = false
            contentDescription = "Font size"
            setOnClickListener { onClick() }
        }
    }

    private fun updateFontSizeBtnText() {
        btnFontSize.text = "$currentFontSize ▾"
        btnFontSize.setTextColor(getColorCompat(R.color.tool_icon_inactive))
    }

    private fun showFontSizePicker() {
        val view = layoutInflater.inflate(R.layout.dialog_font_size_picker, null)
        val seek = view.findViewById<SeekBar>(R.id.seekFontSize)
        val txtVal = view.findViewById<TextView>(R.id.txtFontSizeValue)
        val txtPreview = view.findViewById<TextView>(R.id.txtPreview)

        // SeekBar indexes into allowedFontSizes
        seek.max = allowedFontSizes.size - 1
        seek.progress = nearestFontIndex(currentFontSize)

        fun applyIndex(idx: Int) {
            val size = allowedFontSizes[idx.coerceIn(0, allowedFontSizes.lastIndex)]
            txtVal.text = "$size pt"
            txtPreview.textSize = size.toFloat()
        }

        applyIndex(seek.progress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                applyIndex(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("Font size")
            .setView(view)
            .setPositiveButton("Apply") { _, _ ->
                currentFontSize = allowedFontSizes[seek.progress]
                settings.edit().putInt("font_size", currentFontSize).apply()
                updateFontSizeBtnText()
                // When text blocks land, we’ll apply currentFontSize to them.
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nearestFontIndex(size: Int): Int {
        var bestIdx = 0
        var bestDiff = Int.MAX_VALUE
        for (i in allowedFontSizes.indices) {
            val d = abs(allowedFontSizes[i] - size)
            if (d < bestDiff) { bestDiff = d; bestIdx = i }
        }
        return bestIdx
    }

    // ---------- Misc helpers ----------

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun getColorCompat(resId: Int) = resources.getColor(resId, theme)

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.parseColor("#20000000"))
        }
    }

    private fun ringDrawable(strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), strokeColor)
        }
    }
}

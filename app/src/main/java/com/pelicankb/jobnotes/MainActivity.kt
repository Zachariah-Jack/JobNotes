package com.pelicankb.jobnotes

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: NoteCanvasView

    // Panels (keep these IDs to avoid breaking other references)
    private lateinit var panelSelect: LinearLayout
    private lateinit var panelText: LinearLayout
    private lateinit var panelFontSize: LinearLayout

    // Toolbar icon buttons
    private lateinit var ibPen: ImageButton
    private lateinit var ibHighlighter: ImageButton
    private lateinit var ibEraser: ImageButton
    private lateinit var ibSelect: ImageButton
    private lateinit var ibUndo: ImageButton
    private lateinit var ibRedo: ImageButton
    private lateinit var ibClear: ImageButton

    // Selection icon buttons
    private lateinit var ibRect: ImageButton
    private lateinit var ibLasso: ImageButton
    private lateinit var ibSelClear: ImageButton
    private lateinit var ibSelDelete: ImageButton

    // Sliders
    private lateinit var seekPenSize: SeekBar
    private lateinit var seekHighlighterSize: SeekBar
    private lateinit var seekEraserSize: SeekBar

    // Color swatches
    private lateinit var btnColorBlack: View
    private lateinit var btnColorBlue: View
    private lateinit var btnColorRed: View
    private lateinit var btnColorYellow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.noteCanvas)

        // --- panels ---
        panelSelect = findViewById(R.id.panelSelect)
        panelText = findViewById(R.id.panelText)
        panelFontSize = findViewById(R.id.panelFontSize)

        // --- toolbar icons ---
        ibPen = findViewById(R.id.ibPen)
        ibHighlighter = findViewById(R.id.ibHighlighter)
        ibEraser = findViewById(R.id.ibEraser)
        ibSelect = findViewById(R.id.ibSelect)
        ibUndo = findViewById(R.id.ibUndo)
        ibRedo = findViewById(R.id.ibRedo)
        ibClear = findViewById(R.id.ibClear)

        // Try your drawables first; fall back to a system icon if not found.
        setIcon(ibPen, arrayOf("ic_pen", "ic_stylus", "ic_brush", "ic_edit"), android.R.drawable.ic_menu_edit)
        setIcon(ibHighlighter, arrayOf("ic_highlighter", "ic_marker", "ic_highlight"), android.R.drawable.ic_menu_edit)
        setIcon(ibEraser, arrayOf("ic_eraser", "ic_erase"), android.R.drawable.ic_menu_delete)
        setIcon(ibSelect, arrayOf("ic_select", "ic_selection", "ic_lasso", "ic_select_rect"), android.R.drawable.ic_menu_crop)
        setIcon(ibUndo, arrayOf("ic_undo", "ic_arrow_undo"), android.R.drawable.ic_menu_revert)
        setIcon(ibRedo, arrayOf("ic_redo", "ic_arrow_redo"), android.R.drawable.ic_menu_rotate)
        setIcon(ibClear, arrayOf("ic_clear", "ic_trash", "ic_delete", "ic_delete_sweep"), android.R.drawable.ic_menu_delete)

        ibPen.setOnClickListener { setTool(NoteCanvasView.Tool.STYLUS) }
        ibHighlighter.setOnClickListener { setTool(NoteCanvasView.Tool.HIGHLIGHTER) }
        ibEraser.setOnClickListener { setTool(NoteCanvasView.Tool.ERASER) }
        ibSelect.setOnClickListener { setTool(NoteCanvasView.Tool.SELECT) }
        ibUndo.setOnClickListener { canvasView.undo() }
        ibRedo.setOnClickListener { canvasView.redo() }
        ibClear.setOnClickListener { recreateCanvas() }

        // --- selection controls (icons) ---
        ibRect = findViewById(R.id.ibRect)
        ibLasso = findViewById(R.id.ibLasso)
        ibSelClear = findViewById(R.id.ibSelClear)
        ibSelDelete = findViewById(R.id.ibSelDelete)

        setIcon(ibRect, arrayOf("ic_select_rect", "ic_rect", "ic_crop"), android.R.drawable.ic_menu_crop)
        setIcon(ibLasso, arrayOf("ic_lasso", "ic_select_lasso"), android.R.drawable.ic_menu_crop)
        setIcon(ibSelClear, arrayOf("ic_selection_clear", "ic_clear"), android.R.drawable.ic_menu_close_clear_cancel)
        setIcon(ibSelDelete, arrayOf("ic_selection_delete", "ic_delete"), android.R.drawable.ic_menu_delete)

        ibRect.setOnClickListener {
            canvasView.setSelectionMode(NoteCanvasView.SelectionMode.RECT)
            updateSelectionIconState()
        }
        ibLasso.setOnClickListener {
            canvasView.setSelectionMode(NoteCanvasView.SelectionMode.LASSO)
            updateSelectionIconState()
        }
        ibSelClear.setOnClickListener { canvasView.clearSelection() }
        ibSelDelete.setOnClickListener { canvasView.deleteSelection() }

        // --- sliders ---
        seekPenSize = findViewById(R.id.seekPenSize)
        seekHighlighterSize = findViewById(R.id.seekHighlighterSize)
        seekEraserSize = findViewById(R.id.seekEraserSize)

        seekPenSize.max = 40
        seekPenSize.progress = 6
        seekPenSize.setOnSeekBarChangeListener(simpleSeek { v ->
            canvasView.setPenWidthPx(dp(v.coerceAtLeast(1)))
        })

        seekHighlighterSize.max = 80
        seekHighlighterSize.progress = 18
        seekHighlighterSize.setOnSeekBarChangeListener(simpleSeek { v ->
            canvasView.setHighlighterWidthPx(dp(v.coerceAtLeast(4)))
        })

        seekEraserSize.max = 120
        seekEraserSize.progress = 28
        seekEraserSize.setOnSeekBarChangeListener(simpleSeek { v ->
            canvasView.setEraserWidthPx(dp(v.coerceAtLeast(6)))
        })

        // --- color swatches for pen/highlighter ---
        btnColorBlack = findViewById(R.id.btnColorBlack)
        btnColorBlue = findViewById(R.id.btnColorBlue)
        btnColorRed = findViewById(R.id.btnColorRed)
        btnColorYellow = findViewById(R.id.btnColorYellow)

        btnColorBlack.setOnClickListener { canvasView.setPenColor(Color.BLACK) }
        btnColorBlue.setOnClickListener { canvasView.setPenColor(0xFF1976D2.toInt()) }
        btnColorRed.setOnClickListener { canvasView.setPenColor(0xFFD32F2F.toInt()) }
        btnColorYellow.setOnClickListener { canvasView.setHighlighterColor(0xFFFFFF00.toInt()) }

        // Default tool
        setTool(NoteCanvasView.Tool.STYLUS)
    }

    // Keep the icon menu too (optional). It mirrors the toolbar buttons.
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stylus -> { setTool(NoteCanvasView.Tool.STYLUS); true }
            R.id.action_highlighter -> { setTool(NoteCanvasView.Tool.HIGHLIGHTER); true }
            R.id.action_eraser -> { setTool(NoteCanvasView.Tool.ERASER); true }
            R.id.action_select -> { setTool(NoteCanvasView.Tool.SELECT); true }
            R.id.action_undo -> { canvasView.undo(); true }
            R.id.action_redo -> { canvasView.redo(); true }
            R.id.action_clear -> { recreateCanvas(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- helpers ---

    private fun setTool(tool: NoteCanvasView.Tool) {
        canvasView.setTool(tool)
        updatePanels(tool)
        updateToolIconState(tool)
    }

    private fun updatePanels(tool: NoteCanvasView.Tool) {
        panelSelect.isVisible = tool == NoteCanvasView.Tool.SELECT
        panelText.isVisible = tool == NoteCanvasView.Tool.STYLUS ||
                tool == NoteCanvasView.Tool.HIGHLIGHTER ||
                tool == NoteCanvasView.Tool.ERASER
        panelFontSize.isVisible = panelText.isVisible
    }

    private fun updateToolIconState(tool: NoteCanvasView.Tool) {
        fun ImageButton.sel(selected: Boolean) {
            alpha = if (selected) 1.0f else 0.55f
            isSelected = selected
        }
        ibPen.sel(tool == NoteCanvasView.Tool.STYLUS)
        ibHighlighter.sel(tool == NoteCanvasView.Tool.HIGHLIGHTER)
        ibEraser.sel(tool == NoteCanvasView.Tool.ERASER)
        ibSelect.sel(tool == NoteCanvasView.Tool.SELECT)
        updateSelectionIconState()
    }

    private fun updateSelectionIconState() {
        // simple visual hint
        if (panelSelect.isVisible) {
            ibRect.alpha = 1f
            ibLasso.alpha = 0.55f
        } else {
            ibRect.alpha = 0.55f
            ibLasso.alpha = 0.55f
        }
    }

    private fun setIcon(view: ImageButton, preferredNames: Array<String>, fallbackAndroidRes: Int) {
        val pkg = packageName
        var found = 0
        for (name in preferredNames) {
            val id = resources.getIdentifier(name, "drawable", pkg)
            if (id != 0) { found = id; break }
        }
        if (found != 0) view.setImageResource(found) else view.setImageResource(fallbackAndroidRes)
        view.contentDescription = preferredNames.firstOrNull() ?: "icon"
    }

    private fun recreateCanvas() {
        // Replace the view as a quick "clear page"
        val parent = canvasView.parent as FrameLayout
        val index = parent.indexOfChild(canvasView)
        parent.removeViewAt(index)
        val newView = NoteCanvasView(this)
        newView.id = R.id.noteCanvas
        parent.addView(
            newView, index,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        canvasView = newView
        setTool(NoteCanvasView.Tool.STYLUS)
    }

    private fun simpleSeek(onChange: (value: Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
}

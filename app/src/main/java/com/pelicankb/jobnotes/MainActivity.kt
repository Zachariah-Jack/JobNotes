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

    // Turn this on temporarily if you want the debug subtitle again
    private val SHOW_DEBUG_SUBTITLE = false

    // Tracks which panel content is showing inside toolPanelCard
    private var currentOpenPanelId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        noteCanvas = findViewById(R.id.noteCanvas)

        // Keep Undo/Redo enabled state fresh
        noteCanvas.onStackChanged = {
            invalidateOptionsMenu()
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
        }

        // Wire the top-row buttons explicitly (Pen/Highlighter/Eraser/Color)
        wireTopRowButtons()

        // Wire sliders, switches, and HSV picker if present
        wireOptionalControls()

        if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
    }

    // region Toolbar menu (only Undo / Redo / Clear live here)
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
            R.id.action_undo -> { noteCanvas.undo(); if (SHOW_DEBUG_SUBTITLE) updateSubtitle(); true }
            R.id.action_redo -> { noteCanvas.redo(); if (SHOW_DEBUG_SUBTITLE) updateSubtitle(); true }
            R.id.action_clear -> { noteCanvas.clearAll(); if (SHOW_DEBUG_SUBTITLE) updateSubtitle(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    // endregion

    // region Top-row buttons (Pen/Highlighter/Eraser/Color) with tap-to-toggle panels
    private fun wireTopRowButtons() {
        // Pen -> set tool + toggle Stylus panel
        findViewById<View>(R.id.btnToolPen)?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
            setCurrentTool(NoteCanvasView.Tool.STYLUS)
            togglePanel(R.id.panelStylus)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "TopRow: Pen -> Stylus panel toggled")
        }

        // Highlighter -> set tool + toggle Highlighter panel
        findViewById<View>(R.id.btnToolHighlighter)?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
            togglePanel(R.id.panelHighlighter)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "TopRow: Highlighter panel toggled")
        }

        // Eraser -> set tool + toggle Eraser panel
        findViewById<View>(R.id.btnToolEraser)?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.ERASER)
            setCurrentTool(NoteCanvasView.Tool.ERASER)
            togglePanel(R.id.panelEraser)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "TopRow: Eraser panel toggled")
        }

        // Color -> do NOT change tool, just toggle Color panel
        findViewById<View>(R.id.btnToolColor)?.setOnClickListener {
            togglePanel(R.id.panelColor)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "TopRow: Color panel toggled")
        }
    }

    private fun togglePanel(panelId: Int) {
        val panelCard = findViewById<MaterialCardView>(R.id.toolPanelCard) ?: return

        // If tapping the same icon while its panel is open -> close it
        if (currentOpenPanelId == panelId && panelCard.visibility == View.VISIBLE) {
            panelCard.visibility = View.GONE
            currentOpenPanelId = null
            return
        }

        // Otherwise, show this panel and hide the others
        showOnlyPanel(panelId)
        panelCard.visibility = View.VISIBLE
        currentOpenPanelId = panelId
    }

    private fun showOnlyPanel(panelId: Int) {
        val panels = listOf(
            R.id.panelStylus,
            R.id.panelHighlighter,
            R.id.panelEraser,
            R.id.panelColor
            // We'll add R.id.panelSelect when we introduce Select panel
        )
        for (id in panels) {
            findViewById<View>(id)?.visibility = if (id == panelId) View.VISIBLE else View.GONE
        }
    }
    // endregion

    // region Debug subtitle (optional)
    private fun updateSubtitle() {
        val tool = when (getCurrentTool()) {
            NoteCanvasView.Tool.STYLUS -> "Pen"
            NoteCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
            NoteCanvasView.Tool.ERASER -> "Eraser"
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
        // Stylus brush buttons
        findByName<View>("btnBrushFountain")?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
            setCurrentTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.FOUNTAIN)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "Wired btnBrushFountain -> Stylus:Fountain")
        }
        findByName<View>("btnBrushMarker")?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
            setCurrentTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusBrush(NoteCanvasView.StylusBrush.MARKER)
            if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            Log.d(TAG, "Wired btnBrushMarker -> Stylus:Marker")
        }

        // Sliders
        findByName<Slider>("sliderStylusSize")?.addOnChangeListener { _, value, _ ->
            noteCanvas.setTool(NoteCanvasView.Tool.STYLUS)
            setCurrentTool(NoteCanvasView.Tool.STYLUS)
            noteCanvas.setStylusSize(value)
            Log.d(TAG, "Stylus size -> $value")
        }

        findByName<Slider>("sliderHighlightSize")?.addOnChangeListener { _, value, _ ->
            noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlighterSize(value)
            Log.d(TAG, "Highlighter size -> $value")
        }

        findByName<Slider>("sliderHighlightOpacity")?.addOnChangeListener { _, raw, _ ->
            noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
            val v = if (raw > 1f) raw / 100f else raw
            noteCanvas.setHighlighterOpacity(v)
            Log.d(TAG, "Highlighter opacity -> $v")
        }

        findByName<Slider>("sliderEraserSize")?.addOnChangeListener { _, value, _ ->
            noteCanvas.setTool(NoteCanvasView.Tool.ERASER)
            setCurrentTool(NoteCanvasView.Tool.ERASER)
            noteCanvas.setEraserSize(value)
            Log.d(TAG, "Eraser size -> $value")
        }

        findByName<CompoundButton>("switchEraseHighlightsOnly")?.setOnCheckedChangeListener { _, checked ->
            noteCanvas.setEraseHighlightsOnly(checked)
            Log.d(TAG, "EraseHighlightsOnly -> $checked")
        }

        // Highlighter mode
        findByName<View>("btnHlFreeform")?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.FREEFORM)
            Log.d(TAG, "Highlighter mode -> FREEFORM")
        }
        findByName<View>("btnHlStraight")?.setOnClickListener {
            noteCanvas.setTool(NoteCanvasView.Tool.HIGHLIGHTER)
            setCurrentTool(NoteCanvasView.Tool.HIGHLIGHTER)
            noteCanvas.setHighlightMode(NoteCanvasView.HighlightMode.STRAIGHT)
            Log.d(TAG, "Highlighter mode -> STRAIGHT")
        }

        // HSV color picker + preview (safe-guarded)
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
            // Default: apply to both pen & highlighter
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
    // endregion

    // region (Utility) auto-wiring by tags if you add any tagged views later
    private fun scanAndWireToolViews(root: View) {
        fun matchesToken(s: CharSequence?, token: String): Boolean =
            s?.toString()?.trim()?.equals(token, ignoreCase = true) == true

        fun attach(v: View, tool: NoteCanvasView.Tool, why: String) {
            v.setOnClickListener {
                noteCanvas.setTool(tool)
                setCurrentTool(tool)
                Log.d(TAG, "Wired via $why -> $tool on viewId=${safeIdName(v)}")
                if (SHOW_DEBUG_SUBTITLE) updateSubtitle()
            }
        }

        fun dfs(v: View) {
            val tag = v.tag
            val cd = v.contentDescription

            when {
                matchesToken(tag as? CharSequence, "tool:stylus") ||
                        matchesToken(cd, "tool:stylus") -> attach(v, NoteCanvasView.Tool.STYLUS, "tag/cd")

                matchesToken(tag as? CharSequence, "tool:highlighter") ||
                        matchesToken(cd, "tool:highlighter") -> attach(v, NoteCanvasView.Tool.HIGHLIGHTER, "tag/cd")

                matchesToken(tag as? CharSequence, "tool:eraser") ||
                        matchesToken(cd, "tool:eraser") -> attach(v, NoteCanvasView.Tool.ERASER, "tag/cd")
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) dfs(v.getChildAt(i))
            }
        }

        dfs(findViewById(R.id.root) ?: window.decorView)
    }

    private fun safeIdName(v: View): String = try {
        if (v.id == View.NO_ID) "NO_ID" else resources.getResourceEntryName(v.id)
    } catch (_: Throwable) {
        "UNKNOWN_ID"
    }
    // endregion
}

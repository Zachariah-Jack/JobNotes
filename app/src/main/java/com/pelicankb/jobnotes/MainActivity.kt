package com.pelicankb.jobnotes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType

import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider

import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat



import com.pelicankb.jobnotes.drawing.BrushType
import com.pelicankb.jobnotes.drawing.InkCanvasView
import com.pelicankb.jobnotes.drawing.InkCanvasView.SelectionPolicy
import com.pelicankb.jobnotes.ui.BrushPreviewView
import com.pelicankb.jobnotes.ui.EraserPreviewView
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

import java.io.File


class MainActivity : AppCompatActivity() {
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // --- Persist InkCanvasView document state (pages + strokes, v2) ---
        val inkView = findViewById<InkCanvasView>(R.id.inkCanvas)
        try {
            outState.putByteArray("ink_state", inkView.serialize())
        } catch (_: Throwable) { /* ignore */ }
    }

    private lateinit var inkView: com.pelicankb.jobnotes.drawing.InkCanvasView

    // ===== Export launchers =====
    private val exportPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { out ->
                // Default 300 dpi; tweak if desired
                val ok = inkCanvas.exportToPdf(out, dpi = 300)
                // (optional) toast or snackbar on success/failure
            }
        }
    }

    private val exportPngLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        if (uri != null) {
            val bmp = inkCanvas.renderCurrentPageBitmap(includeSelectionOverlays = false)
            contentResolver.openOutputStream(uri)?.use { out ->
                // 100 = lossless for PNG (quality ignored by PNG encoder)
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }


    // Save a .pelnote (binary from InkCanvasView.serialize)
    private val saveNoteLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = inkCanvas.serialize()
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Open a .pelnote and deserialize
    private val openNoteLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    inkCanvas.deserialize(bytes)
                    Toast.makeText(this, "Note opened", Toast.LENGTH_SHORT).show()
                    inkCanvas.requestFocus()
                } else {
                    Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───────── Canvas ─────────
    private lateinit var inkCanvas: InkCanvasView

    // ───────── Panels ─────────
    private lateinit var panelPen: View
    private lateinit var panelKeyboard: View
    private lateinit var groupPenChips: View

    // ───────── Title UI ─────────
    private lateinit var titleDisplay: TextView
    private lateinit var titleEdit: EditText
    private lateinit var btnTitleEdit: ImageButton

    // ───────── Toolbar buttons for active-state visuals ─────────
    private lateinit var btnStylus: ImageButton
    private lateinit var btnHighlighter: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnSelectRect: ImageButton

    // Right-side actions new buttons
    private lateinit var btnHandPen: ImageButton
    private lateinit var btnCameraPen: ImageButton
    private lateinit var btnGalleryPen: ImageButton
    private lateinit var btnOverflowPen: ImageButton
    private lateinit var btnHandKbd: ImageButton
    private lateinit var btnCameraKbd: ImageButton
    private lateinit var btnGalleryKbd: ImageButton
    private lateinit var btnOverflowKbd: ImageButton

    // ───────── Top-level tool family ─────────
    private enum class ToolFamily { PEN_FAMILY, HIGHLIGHTER, ERASER }
    private var toolFamily: ToolFamily = ToolFamily.PEN_FAMILY

    // Separate selection "armed" flag to color the select button
    private var selectionArmed: Boolean = false

    // ───────── Pen family (Stylus) state ─────────
    private enum class BrushTypeLocal { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    private var brushType: BrushTypeLocal = BrushTypeLocal.PEN
    private var brushSizeDp: Float = 4f
    private var stylusPopup: PopupWindow? = null

    // Pen color chips
    private lateinit var chip1: ImageButton
    private lateinit var chip2: ImageButton
    private lateinit var chip3: ImageButton
    private var selectedChipId: Int = R.id.chipColor1
    private var penFamilyColor: Int = Color.BLACK

    // ───────── Highlighter state ─────────
    private enum class HighlighterMode { FREEFORM, STRAIGHT }
    private var highlighterMode: HighlighterMode = HighlighterMode.FREEFORM
    private var highlighterPopup: PopupWindow? = null
    private var highlighterColor: Int = 0x66FFD54F.toInt()
    private var highlighterSizeDp: Float = 12f

    // ───────── Eraser state ─────────
    private enum class EraserMode { STROKE, AREA }
    private var eraserMode: EraserMode = EraserMode.AREA
    private var eraserSizeDp: Float = 22f
    private var eraseHighlighterOnly: Boolean = false
    private var eraserPopup: PopupWindow? = null

    // ───────── Selection popup ─────────
    private var selectPopup: PopupWindow? = null

    // ───────── Palette presets ─────────
    private val PRESET_COLORS = intArrayOf(
        0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(),
        0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(),
        0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(),
        0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFFF5722.toInt(),
        0xFF795548.toInt(), 0xFF9E9E9E.toInt(), 0xFF607D8B.toInt(), 0xFF000000.toInt(),
        0xFFFFFFFF.toInt(), 0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFB2FF59.toInt()
    )

    // ───────── Lifecycle ─────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prefer resize when keyboard shows

        setContentView(R.layout.activity_main)


// ...inside onCreate() just after setContentView(...)
                run {
                    val titleRow = findViewById<View>(R.id.titleRow)
                    ViewCompat.setOnApplyWindowInsetsListener(titleRow) { v, insets ->
                        val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top

                        // Preserve existing paddings; just add top inset so the row is pushed below status bar.
                        v.setPadding(v.paddingLeft, topInset + v.paddingTop, v.paddingRight, v.paddingBottom)
                        insets
                    }
                }

        // --- Restore InkCanvasView document state (pages + strokes, v2) ---
        run {
            val inkView = findViewById<InkCanvasView>(R.id.inkCanvas)
            val bytes = savedInstanceState?.getByteArray("ink_state")
            if (bytes != null) {
                try { inkView.deserialize(bytes) } catch (_: Throwable) { /* ignore bad payloads */ }
            }
        }


        inkCanvas = findViewById(R.id.inkCanvas)
        // For quick verification, allow finger drawing too.
        // Change to true later if you want stylus-only drawing.
        inkCanvas.setStylusOnly(true)
        inkCanvas.setPanMode(false)

        btnStylus = findViewById(R.id.btnStylus)
        btnHighlighter = findViewById(R.id.btnHighlighter)
        btnEraser = findViewById(R.id.btnEraser)
        btnSelectRect = findViewById(R.id.btnSelectRect)

        panelPen = findViewById(R.id.panelPen)
        panelKeyboard = findViewById(R.id.panelKeyboard)
        groupPenChips = findViewById(R.id.groupPenChips)

        titleDisplay = findViewById(R.id.noteTitleView)
        titleEdit = findViewById(R.id.noteTitleEdit)
        btnTitleEdit = findViewById(R.id.btnTitleEdit)
        // Make sure the pencil (edit title) button is a hard, live target.
        btnTitleEdit.isEnabled = true
        btnTitleEdit.isClickable = true
        btnTitleEdit.isFocusable = true
        btnTitleEdit.isFocusableInTouchMode = false
        btnTitleEdit.setOnClickListener { enterTitleEditMode() }

// Keep it above the canvas in z-order at runtime (in case XML order/elevation lags)
        btnTitleEdit.bringToFront()
        btnTitleEdit.parent?.let { (it as? View)?.bringToFront() }

// Enlarge its hit target (you already call this; keep it after bringToFront)
        btnTitleEdit.expandTouchTarget(12)

        // Title field should behave like a normal text editor (full keyboard)
        titleEdit.visibility = View.GONE
        titleEdit.isFocusableInTouchMode = true
        titleEdit.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        titleEdit.imeOptions = EditorInfo.IME_ACTION_DONE


        chip1 = findViewById(R.id.chipColor1)
        chip2 = findViewById(R.id.chipColor2)
        chip3 = findViewById(R.id.chipColor3)

        btnHandPen = findViewById(R.id.btnHandPen)
        btnCameraPen = findViewById(R.id.btnCameraPen)
        btnGalleryPen = findViewById(R.id.btnGalleryPen)
        btnOverflowPen = findViewById(R.id.btnOverflowPen)
        btnHandKbd = findViewById(R.id.btnHandKbd)
        btnCameraKbd = findViewById(R.id.btnCameraKbd)
        btnGalleryKbd = findViewById(R.id.btnGalleryKbd)
        btnOverflowKbd = findViewById(R.id.btnOverflowKbd)
        // Ensure the overflow buttons use the proper "more" icon (not the up-arrow)
        btnOverflowPen.setImageResource(R.drawable.ic_more_vert)
        btnOverflowKbd.setImageResource(R.drawable.ic_more_vert)


        // Canvas defaults
        inkCanvas.isFocusableInTouchMode = true
        inkCanvas.requestFocus()
        inkCanvas.setBrush(BrushType.PEN)
        inkCanvas.setStrokeWidthDp(brushSizeDp)

        // Undo/Redo
        findViewById<View?>(R.id.btnUndoPen)?.setOnClickListener { inkCanvas.undo() }
        findViewById<View?>(R.id.btnRedoPen)?.setOnClickListener { inkCanvas.redo() }
        findViewById<View?>(R.id.btnUndoKbd)?.setOnClickListener { inkCanvas.undo() }
        findViewById<View?>(R.id.btnRedoKbd)?.setOnClickListener { inkCanvas.redo() }

        // Right-side actions
        wireEditActions()

        findViewById<ImageButton?>(R.id.btnHandPen)?.setOnClickListener { v ->
            val btn = v as ImageButton
            btn.isSelected = !btn.isSelected
            inkCanvas.setPanMode(btn.isSelected)
            Toast.makeText(this, if (btn.isSelected) "Hand (pan) tool ON" else "Hand tool OFF", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton?>(R.id.btnHandKbd)?.setOnClickListener { v ->
            val btn = v as ImageButton
            btn.isSelected = !btn.isSelected
            inkCanvas.setPanMode(btn.isSelected)
            Toast.makeText(this, if (btn.isSelected) "Hand (pan) tool ON" else "Hand tool OFF", Toast.LENGTH_SHORT).show()
        }


        // Show pen toolbar
        showPenMenu()

        // Enlarge small touch targets
        findViewById<View>(R.id.btnKeyboardToggle).expandTouchTarget(12)
        findViewById<View>(R.id.btnPenToggle).expandTouchTarget(12)
        btnTitleEdit.expandTouchTarget(12)

        // Title edit behavior
        btnTitleEdit.setOnClickListener { enterTitleEditMode() }
        titleEdit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                exitTitleEditMode(save = true); true
            } else false
        }
        if (savedInstanceState == null && titleDisplay.text.isNullOrBlank()) {
            titleDisplay.text = "Untitled"
        }

        // Default chip colors
        chip1.imageTintList = ColorStateList.valueOf(Color.BLACK)
        chip2.imageTintList = ColorStateList.valueOf(0xFFFFD54F.toInt())
        chip3.imageTintList = ColorStateList.valueOf(0xFF2196F3.toInt())

        // Chip clicks (pen family)
        val chipClick = View.OnClickListener { v ->
            val chip = v as ImageButton
            if (chip.id != selectedChipId) selectChip(chip.id) else showColorPaletteDialog(chip)
        }
        chip1.setOnClickListener(chipClick)
        chip2.setOnClickListener(chipClick)
        chip3.setOnClickListener(chipClick)
        selectChip(selectedChipId)
        penFamilyColor = currentPenColor()

        // Toolbar: Stylus
        btnStylus.setOnClickListener { v ->
            findViewById<ImageButton?>(R.id.btnHandPen)?.isSelected = false
            findViewById<ImageButton?>(R.id.btnHandKbd)?.isSelected = false
            inkCanvas.setPanMode(false)

            inkCanvas.setSelectionToolNone(keepSelection = true)
            selectionArmed = false
            selectPopup?.dismiss()

            toolFamily = ToolFamily.PEN_FAMILY
            applyPenFamilyBrush()
            updateToolbarActiveStates()

            toggleStylusPopup(v)
        }

        // Toolbar: Highlighter
        btnHighlighter.setOnClickListener { v ->
            findViewById<ImageButton?>(R.id.btnHandPen)?.isSelected = false
            findViewById<ImageButton?>(R.id.btnHandKbd)?.isSelected = false
            inkCanvas.setPanMode(false)

            inkCanvas.setSelectionToolNone(keepSelection = true)
            selectionArmed = false
            selectPopup?.dismiss()

            toolFamily = ToolFamily.HIGHLIGHTER
            applyHighlighterBrush()
            updateToolbarActiveStates()

            toggleHighlighterPopup(v)
        }

        // Toolbar: Eraser
        btnEraser.setOnClickListener { v ->
            findViewById<ImageButton?>(R.id.btnHandPen)?.isSelected = false
            findViewById<ImageButton?>(R.id.btnHandKbd)?.isSelected = false
            inkCanvas.setPanMode(false)

            inkCanvas.setSelectionToolNone(keepSelection = true)
            selectionArmed = false
            selectPopup?.dismiss()

            toolFamily = ToolFamily.ERASER
            applyEraserBrush()
            updateToolbarActiveStates()

            toggleEraserPopup(v)
        }

        // Selection popup (lasso/rect + mode)
        btnSelectRect.setOnClickListener { v -> toggleSelectPopup(v) }

        // Hand/pan tool
        val enablePan = View.OnClickListener {
            inkCanvas.setPanMode(true)
            Toast.makeText(this, "Pan mode: drag with stylus to scroll", Toast.LENGTH_SHORT).show()
        }
        btnHandPen.setOnClickListener(enablePan)
        btnHandKbd.setOnClickListener(enablePan)

        // Camera/Gallery placeholders (wire real flows in Phase 2)
        val camToast = View.OnClickListener { Toast.makeText(this, "Camera (coming soon)", Toast.LENGTH_SHORT).show() }
        val galToast = View.OnClickListener { Toast.makeText(this, "Gallery (coming soon)", Toast.LENGTH_SHORT).show() }
        btnCameraPen.setOnClickListener(camToast)
        btnCameraKbd.setOnClickListener(camToast)
        btnGalleryPen.setOnClickListener(galToast)
        btnGalleryKbd.setOnClickListener(galToast)

        // Overflow menus
        btnOverflowPen.setOnClickListener { showOverflowMenu(it) }
        btnOverflowKbd.setOnClickListener { showOverflowMenu(it) }

        updateToolbarActiveStates()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onTitleEditClicked(v: View) {
        // Same action you use elsewhere
        enterTitleEditMode()
    }


    // ===== Share helpers (FileProvider) =====

    private fun sharePdfCurrentPage() {
        try {
            val file = File.createTempFile("JobNotes-Page-", ".pdf", cacheDir)
            file.outputStream().use { out ->
                inkCanvas.exportToPdf(out, dpi = 300)
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share PDF"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Unable to share PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePngCurrentPage() {
        try {
            val file = File.createTempFile("JobNotes-Page-", ".png", cacheDir)
            val bmp = inkCanvas.renderCurrentPageBitmap(includeSelectionOverlays = false)
            file.outputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share Image"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Unable to share Image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
// Top-level entries
        popup.menu.add(0, 1, 0, "Export to PDF")
        popup.menu.add(0, 2, 1, "Export to Image")

// Share/export actions
        popup.menu.add(0, 3, 2, "Share as PDF")
        popup.menu.add(0, 4, 3, "Share as Image")

// New: native save/open for JobNotes file
        popup.menu.add(0, 6, 4, "Save Note (.pelnote)")
        popup.menu.add(0, 7, 5, "Open Note (.pelnote)")

// New: implement share JobNotes file (keeps your label)
        popup.menu.add(0, 5, 6, "Share as JobNotes file")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { // Export to PDF
                    // Suggest a filename
                    exportPdfLauncher.launch("JobNotes-Page.pdf")
                    true
                }
                2 -> { // Export to Image (PNG)
                    exportPngLauncher.launch("JobNotes-Page.png")
                    true
                }
                3 -> { // Share as PDF
                    sharePdfCurrentPage(); true
                }
                4 -> { // Share as Image
                    sharePngCurrentPage(); true
                }
                6 -> { // Save .pelnote
                    // SAF can't filter by extension reliably; we provide the name with extension.
                    saveNoteLauncher.launch(suggestedFileName("pelnote"))

                    true
                }
                7 -> { // Open .pelnote
                    // Try to hint likely types; SAF primarily filters by MIME, so allow broad.
                    openNoteLauncher.launch(arrayOf("*/*"))
                    true
                }
                5 -> { // Share as JobNotes file (.pelnote)
                    try {
                        val tmp = File.createTempFile("JobNotes-", ".pelnote", cacheDir)
                        val bytes = inkCanvas.serialize()
                        tmp.outputStream().use { it.write(bytes) }

                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            // Generic binary; receivers will honor extension in the stream name
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share, "Share JobNotes file"))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "Unable to share .pelnote", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()

    }


    private fun wireEditActions() {
        findViewById<View?>(R.id.btnClear)?.setOnClickListener {
            if (inkCanvas.hasSelection()) {
                inkCanvas.deleteSelection()
            } else {
                Toast.makeText(this, "Nothing selected to clear", Toast.LENGTH_SHORT).show()
            }
            inkCanvas.requestFocus()
        }
        findViewById<View?>(R.id.btnCopy)?.setOnClickListener {
            if (inkCanvas.hasSelection()) {
                val ok = inkCanvas.copySelection()
                Toast.makeText(this, if (ok) "Copied selection" else "Copy failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nothing selected to copy", Toast.LENGTH_SHORT).show()
            }
            inkCanvas.requestFocus()
        }
        findViewById<View?>(R.id.btnCut)?.setOnClickListener {
            if (inkCanvas.hasSelection()) {
                val ok = inkCanvas.cutSelection()
                if (!ok) Toast.makeText(this, "Cut failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nothing selected to cut", Toast.LENGTH_SHORT).show()
            }
            inkCanvas.requestFocus()
        }
        findViewById<View?>(R.id.btnPaste)?.setOnClickListener {
            if (inkCanvas.hasClipboard()) {
                val armed = inkCanvas.armPastePlacement()
                if (armed) Toast.makeText(this, "Tap on canvas to place paste", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            }
            inkCanvas.requestFocus()
        }
    }


    // ───────── Menu show/hide (existing) ─────────
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

    // ───────── Pen color chips ─────────
    private fun currentPenColor(): Int = when (selectedChipId) {
        chip1.id -> chip1.imageTintList?.defaultColor
        chip2.id -> chip2.imageTintList?.defaultColor
        chip3.id -> chip3.imageTintList?.defaultColor
        else -> null
    } ?: Color.BLACK

    private fun selectChip(id: Int) {
        selectedChipId = id
        chip1.isSelected = (id == chip1.id)
        chip2.isSelected = (id == chip2.id)
        chip3.isSelected = (id == chip3.id)

        val c = currentPenColor()
        penFamilyColor = c
        if (toolFamily == ToolFamily.PEN_FAMILY) inkCanvas.setColor(c)
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
            scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            if (color == tempColor) { tile.isSelected = true; selectedTile = tile }
            row.addView(tile)
        }
        container.addView(row)

        // Custom color
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
                    if (toolFamily == ToolFamily.PEN_FAMILY) inkCanvas.setColor(tempColor)
                }
                d.dismiss()
                inkCanvas.requestFocus()
            }
            .show()
    }

    private fun showAdvancedColorPicker(@Suppress("UNUSED_PARAMETER") initialColor: Int, onSelected: (Int) -> Unit) {
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

    // ───────── Stylus (Pen family) popup ─────────
    private fun toggleStylusPopup(anchor: View) {
        stylusPopup?.let { if (it.isShowing) { it.dismiss(); stylusPopup = null; return } }
        stylusPopup = createStylusMenuPopup().also { popup ->
            popup.isOutsideTouchable = false
            popup.isFocusable = false
            showPopupAnchoredWithinScreen(popup, anchor)
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
            // keep popup open
            inkCanvas.requestFocus()
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
                brushSizeDp = (seekBar?.progress ?: brushSizeDp.toInt()).toFloat().coerceIn(1f, 60f)
                if (toolFamily == ToolFamily.PEN_FAMILY) inkCanvas.setStrokeWidthDp(brushSizeDp)
                // keep popup open
                inkCanvas.requestFocus()
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // not focusable
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            elevation = 8f
            setOnDismissListener { stylusPopup = null; inkCanvas.requestFocus() }
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
        updateToolbarActiveStates()
    }

    // ───────── Highlighter popup ─────────
    private fun toggleHighlighterPopup(anchor: View) {
        highlighterPopup?.let { if (it.isShowing) { it.dismiss(); highlighterPopup = null; return } }
        highlighterPopup = createHighlighterPopup().also { popup ->
            popup.isOutsideTouchable = false
            popup.isFocusable = false
            showPopupAnchoredWithinScreen(popup, anchor)
        }
    }

    private fun createHighlighterPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_highlighter_menu, null, false)

        val iconFree = content.findViewById<ImageButton>(R.id.iconHLFreeform)
        val iconLine = content.findViewById<ImageButton>(R.id.iconHLStraight)
        val chip     = content.findViewById<ImageButton>(R.id.chipHLColor)
        val slider   = content.findViewById<SeekBar>(R.id.sizeSliderHL)
        val sizeTxt  = content.findViewById<TextView>(R.id.sizeValueHL)
        val preview  = content.findViewById<BrushPreviewView>(R.id.previewHL)

        fun updateModeUI() {
            iconFree.isSelected = (highlighterMode == HighlighterMode.FREEFORM)
            iconLine.isSelected = (highlighterMode == HighlighterMode.STRAIGHT)
        }
        updateModeUI()

        val modeClick = View.OnClickListener { v ->
            highlighterMode =
                if (v.id == R.id.iconHLStraight) HighlighterMode.STRAIGHT else HighlighterMode.FREEFORM
            updateModeUI()
            if (toolFamily == ToolFamily.HIGHLIGHTER) {
                inkCanvas.setBrush(
                    if (highlighterMode == HighlighterMode.FREEFORM)
                        BrushType.HIGHLIGHTER_FREEFORM
                    else
                        BrushType.HIGHLIGHTER_STRAIGHT
                )
            }
            // keep popup open
            inkCanvas.requestFocus()
        }
        iconFree.setOnClickListener(modeClick)
        iconLine.setOnClickListener(modeClick)

        chip.imageTintList = ColorStateList.valueOf(highlighterColor)
        chip.setOnClickListener {
            showAdvancedColorPicker(highlighterColor) { picked ->
                highlighterColor = if ((picked ushr 24) == 0xFF) {
                    (0x66 shl 24) or (picked and 0x00FFFFFF)
                } else picked
                chip.imageTintList = ColorStateList.valueOf(highlighterColor)
                if (toolFamily == ToolFamily.HIGHLIGHTER) inkCanvas.setColor(highlighterColor)
            }
        }

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
                highlighterSizeDp = (seekBar?.progress ?: highlighterSizeDp.toInt()).toFloat().coerceIn(1f, 60f)
                if (toolFamily == ToolFamily.HIGHLIGHTER) inkCanvas.setStrokeWidthDp(highlighterSizeDp)
                // keep popup open
                inkCanvas.requestFocus()
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            elevation = 8f
            setOnDismissListener { highlighterPopup = null; inkCanvas.requestFocus() }
        }
    }

    private fun applyHighlighterBrush() {
        inkCanvas.setBrush(
            if (highlighterMode == HighlighterMode.FREEFORM)
                BrushType.HIGHLIGHTER_FREEFORM
            else
                BrushType.HIGHLIGHTER_STRAIGHT
        )
        inkCanvas.setStrokeWidthDp(highlighterSizeDp)
        inkCanvas.setColor(highlighterColor)
        updateToolbarActiveStates()
    }

    // ───────── Eraser popup ─────────
    private fun toggleEraserPopup(anchor: View) {
        eraserPopup?.let { if (it.isShowing) { it.dismiss(); eraserPopup = null; return } }
        eraserPopup = createEraserPopup().also { p ->
            p.isOutsideTouchable = false
            p.isFocusable = false
            showPopupAnchoredWithinScreen(p, anchor)
        }
    }

    private fun createEraserPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_eraser_menu, null, false)

        val btnStroke = content.findViewById<ImageButton>(R.id.iconEraseStroke)
        val btnArea   = content.findViewById<ImageButton>(R.id.iconEraseArea)
        val sizeBar   = content.findViewById<SeekBar>(R.id.sizeSliderEraser)
        val sizeTxt   = content.findViewById<TextView>(R.id.sizeValueEraser)
        val preview   = content.findViewById<EraserPreviewView>(R.id.previewEraser)
        val hlOnlySw  = content.findViewById<Switch>(R.id.switchHLOnly)

        fun updateModeUI() {
            btnStroke.isSelected = (eraserMode == EraserMode.STROKE)
            btnArea.isSelected   = (eraserMode == EraserMode.AREA)
        }
        updateModeUI()

        val onModeClick = View.OnClickListener { v ->
            eraserMode = if (v.id == R.id.iconEraseStroke) EraserMode.STROKE else EraserMode.AREA
            updateModeUI()
            if (toolFamily == ToolFamily.ERASER) {
                inkCanvas.setBrush(
                    if (eraserMode == EraserMode.AREA) BrushType.ERASER_AREA else BrushType.ERASER_STROKE
                )
            }
            // keep popup open
            inkCanvas.requestFocus()
        }
        btnStroke.setOnClickListener(onModeClick)
        btnArea.setOnClickListener(onModeClick)

        sizeBar.max = 100
        sizeBar.progress = eraserSizeDp.toInt().coerceIn(1, 100)
        sizeTxt.text = "${sizeBar.progress} dp"
        preview.setDiameterPx(sizeBar.progress.dp().toFloat())

        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceIn(1, 100)
                sizeTxt.text = "$v dp"
                preview.setDiameterPx(v.dp().toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                eraserSizeDp = (seekBar?.progress ?: eraserSizeDp.toInt()).toFloat().coerceIn(1f, 100f)
                if (toolFamily == ToolFamily.ERASER) inkCanvas.setStrokeWidthDp(eraserSizeDp)
                // keep popup open
                inkCanvas.requestFocus()
            }
        })

        hlOnlySw.isChecked = eraseHighlighterOnly
        hlOnlySw.setOnCheckedChangeListener { _, checked ->
            eraseHighlighterOnly = checked
            inkCanvas.setEraserHighlighterOnly(eraseHighlighterOnly)
        }

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            elevation = 8f
            setOnDismissListener { eraserPopup = null; inkCanvas.requestFocus() }
        }
    }




    // ───────── Selection popup (lasso/rect + mode toggle) ─────────
    private fun toggleSelectPopup(anchor: View) {
        selectPopup?.let { if (it.isShowing) { it.dismiss(); selectPopup = null; return } }
        selectPopup = createSelectPopup().also { p ->
            p.isOutsideTouchable = false
            p.isFocusable = false
            showPopupAnchoredWithinScreen(p, anchor)
        }
    }

    private fun createSelectPopup(): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_select_menu, null, false)

        val iconLasso = content.findViewById<ImageButton>(R.id.iconSelectLasso)
        val iconRect  = content.findViewById<ImageButton>(R.id.iconSelectRect)
        val modeSw    = content.findViewById<Switch>(R.id.switchStrokeSelect)

        fun setToolUI(which: String) {
            iconLasso.isSelected = which == "lasso"
            iconRect.isSelected  = which == "rect"
        }
        setToolUI("lasso")

        iconLasso.setOnClickListener {
            setToolUI("lasso")
            selectionArmed = true
            inkCanvas.enterSelectionLasso()
            updateToolbarActiveStates()
            // keep popup open
            inkCanvas.requestFocus()
            Toast.makeText(this, "Lasso: draw to select. Tap elsewhere to start a new selection.", Toast.LENGTH_SHORT).show()
        }
        iconRect.setOnClickListener {
            setToolUI("rect")
            selectionArmed = true
            inkCanvas.enterSelectionRect()
            updateToolbarActiveStates()
            // keep popup open
            inkCanvas.requestFocus()
            Toast.makeText(this, "Rect: drag to select. Tap elsewhere to start a new selection.", Toast.LENGTH_SHORT).show()
        }

        modeSw.isChecked = inkCanvas.getSelectionPolicy() == SelectionPolicy.STROKE_WISE
        modeSw.setOnCheckedChangeListener { _, checked ->
            inkCanvas.setSelectionPolicy(
                if (checked) SelectionPolicy.STROKE_WISE else SelectionPolicy.REGION_INSIDE
            )
        }

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            elevation = 8f
            setOnDismissListener { selectPopup = null; inkCanvas.requestFocus() }
        }
    }

    // ───────── Helpers ─────────
    private fun suggestedFileName(ext: String): String {
        val raw = titleDisplay.text?.toString()?.trim().orEmpty()
        val base = if (raw.isBlank()) "Untitled" else raw
        val safe = base.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return "$safe.$ext"
    }


    private fun applyEraserBrush() {
        inkCanvas.setBrush(
            if (eraserMode == EraserMode.AREA) BrushType.ERASER_AREA else BrushType.ERASER_STROKE
        )
        inkCanvas.setStrokeWidthDp(eraserSizeDp)
        inkCanvas.setEraserHighlighterOnly(eraseHighlighterOnly)
    }

    private fun showKeyboardForced(target: View) {
        target.post {
            target.requestFocus()
            WindowInsetsControllerCompat(window, target)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideKeyboard(target: View) {
        WindowInsetsControllerCompat(window, target)
            .hide(WindowInsetsCompat.Type.ime())
    }


    private fun enterTitleEditMode() {
        titleEdit.setText(titleDisplay.text)
        titleDisplay.visibility = View.GONE
        titleEdit.visibility = View.VISIBLE
        titleEdit.requestFocus()
        titleEdit.setSelection(titleEdit.text.length)
        // Ensure IME shows as a full soft keyboard
        showKeyboardForced(titleEdit)
    }


    private fun exitTitleEditMode(save: Boolean) {
        if (save) titleDisplay.text = titleEdit.text
        titleEdit.visibility = View.GONE
        titleDisplay.visibility = View.VISIBLE
        hideKeyboard(titleEdit)
        inkCanvas.requestFocus()
    }

    private fun updateToolbarActiveStates() {
        btnStylus.isSelected = (toolFamily == ToolFamily.PEN_FAMILY) && !selectionArmed
        btnHighlighter.isSelected = (toolFamily == ToolFamily.HIGHLIGHTER) && !selectionArmed
        btnEraser.isSelected = (toolFamily == ToolFamily.ERASER) && !selectionArmed
        btnSelectRect.isSelected = selectionArmed
    }

    private fun showPopupAnchoredWithinScreen(popup: PopupWindow, anchor: View, yOffDp: Int = 8) {
        // Measure content
        val content = popup.contentView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = content.measuredWidth

        // Screen frame
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)

        // Anchor x
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorX = loc[0]

        var xOff = 0
        val margin = 8.dp()
        if (anchorX + popupW + margin > frame.right) {
            xOff = frame.right - (anchorX + popupW) - margin
        }
        popup.showAsDropDown(anchor, xOff, yOffDp.dp())
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_DOWN) {
            // Close title editor if user taps away
            if (titleEdit.visibility == View.VISIBLE) {
                val outRect = Rect()
                titleEdit.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    exitTitleEditMode(save = true)
                }
            }
        }
        return handled
    }
}

/** Expands a view's tap target by [extraDp] on all sides. */
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

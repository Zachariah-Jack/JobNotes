package com.pelicankb.jobnotes



import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable


import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.RadioButton

import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.ColorInt

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pelicankb.jobnotes.drawing.BrushType
import com.pelicankb.jobnotes.drawing.InkCanvasView
import com.pelicankb.jobnotes.drawing.InkCanvasView.SelectionPolicy
import com.pelicankb.jobnotes.ui.BrushPreviewView
import com.pelicankb.jobnotes.ui.EraserPreviewView
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import java.io.File
import java.io.FileOutputStream
















class MainActivity : AppCompatActivity() {
    // ───────── Per-tool color memory (persistent) ─────────
    private val prefs by lazy { getSharedPreferences("jobnotes_prefs", MODE_PRIVATE) }

    private companion object {
        private const val PREF_PEN_COLOR   = "pen_color"
        private const val PREF_HL_COLOR    = "hl_color_argb"
        private const val PREF_HL_SIZE_DP  = "hl_size_dp"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // --- Persist InkCanvasView document state (pages + strokes, v2) ---
        val iv = findViewById<InkCanvasView>(R.id.inkCanvas)
        try {
            outState.putByteArray("ink_state", iv.serialize())
        } catch (_: Throwable) { /* ignore */ }

    }



    private fun inflateForPopup(@LayoutRes layoutId: Int, anchor: View): View {
        val parent = (anchor.rootView as? ViewGroup) ?: window.decorView as ViewGroup
        return layoutInflater.inflate(layoutId, parent, /* attachToRoot = */ false)
    }

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
    private val autosaveFile by lazy { File(filesDir, "autosave.pelnote") }


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
    private lateinit var takePictureLauncher: androidx.activity.result.ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private var pendingCameraUri: Uri? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>



    // ───────── Top-level tool family ─────────
    private enum class ToolFamily { PEN_FAMILY, HIGHLIGHTER, ERASER }
    private var toolFamily: ToolFamily = ToolFamily.PEN_FAMILY

    // Separate selection "armed" flag to color the select button
    private var selectionArmed: Boolean = false

    // ───────── Pen family (Stylus) state ─────────
    private enum class BrushTypeLocal { FOUNTAIN, CALLIGRAPHY, PEN, PENCIL, MARKER }
    private var brushType: BrushTypeLocal = BrushTypeLocal.PEN
    private var brushSizeDp: Float = 4f
    // Text tool state
    private var textSizeDp: Float = 28f
    private var textColor: Int = Color.BLACK
    private var textBold: Boolean = false
    private var textItalic: Boolean = false
    private var textPopup: PopupWindow? = null
    // Floating editor overlay for immediate typing into a text box



    // ADD (Pen style state)
    private var penStrokeStyle: InkCanvasView.StrokeStyle = InkCanvasView.StrokeStyle.SOLID
    private var penArrowEnds: Boolean = false
    private var penLockStyle: Boolean = false


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
    private fun dismissAllPopups(except: PopupWindow? = null) {
        if (stylusPopup != null && stylusPopup != except) { stylusPopup?.dismiss(); stylusPopup = null }
        if (highlighterPopup != null && highlighterPopup != except) { highlighterPopup?.dismiss(); highlighterPopup = null }
        if (eraserPopup != null && eraserPopup != except) { eraserPopup?.dismiss(); eraserPopup = null }
        if (selectPopup != null && selectPopup != except) { selectPopup?.dismiss(); selectPopup = null }
        if (shapesPopup != null && shapesPopup != except) { shapesPopup?.dismiss(); shapesPopup = null } // <— add this line
        if (textPopup != null && textPopup != except) { textPopup?.dismiss(); textPopup = null }

    }




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
        setContentView(R.layout.activity_main)
        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                contentResolver.openInputStream(uri)?.use { inStream ->
                    val bmp = decodeBitmapSafelyFromUri(uri)

                    if (bmp != null) {
                        val v = findViewById<com.pelicankb.jobnotes.drawing.InkCanvasView>(R.id.inkCanvas)



                        v.insertBitmapAtCenter(bmp, select = true)

                    }
                }
            }
        }

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { inStream ->
                    val bmp = decodeBitmapSafelyFromUri(uri)

                    if (bmp != null) {
                        val v = findViewById<com.pelicankb.jobnotes.drawing.InkCanvasView>(R.id.inkCanvas)


                        v.insertBitmapAtCenter(bmp, select = true)
                    }
                }
            }
        }
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            // If camera was requested and granted, launch capture:
            val needCamera = grants.keys.any { it == android.Manifest.permission.CAMERA }
            val cameraOk = grants[android.Manifest.permission.CAMERA] == true
            if (needCamera && cameraOk) {
                val uri = pendingCameraUri
                if (uri != null) {
                    takePictureLauncher.launch(uri)
                }
                return@registerForActivityResult
            }
            // If gallery media was requested and granted, launch picker:
            val mediaPerm =
                if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            val needMedia = grants.keys.any { it == mediaPerm }
            val mediaOk = grants[mediaPerm] == true
            if (needMedia && mediaOk) {
                pickImageLauncher.launch("image/*")
            }
        }






        // This is the critical line
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // ---- Title header: order + gravity + stable insets (no debug visuals) ----
        /*
        val titleRow = findViewById<View>(R.id.titleRow)
        val rootLL   = findViewById<LinearLayout>(R.id.root)

// Make sure children stack from the top
        rootLL.gravity = Gravity.TOP

// If titleRow was appended later, put it back at index 0 (top) once.
        if (rootLL.indexOfChild(titleRow) != 0) {
            rootLL.removeView(titleRow)
            rootLL.addView(titleRow, 0)
        }

// Status-bar insets without accumulating padding
        val basePadLeft   = titleRow.paddingLeft
        val basePadTop    = titleRow.paddingTop
        val basePadRight  = titleRow.paddingRight
        val basePadBottom = titleRow.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(titleRow) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(basePadLeft, basePadTop + topInset, basePadRight, basePadBottom)
            insets
        }

// Keep visible & above other content
        titleRow.visibility = View.VISIBLE
        titleRow.bringToFront()
        titleRow.elevation = titleRow.elevation.coerceAtLeast(8f)

         */














        // Initialize first
        inkCanvas = findViewById<InkCanvasView>(R.id.inkCanvas)
        // Keep edited text above the IME via pure canvas lift (no window shrink, no root padding)
        val root = findViewById<View>(android.R.id.content)






        // Keep the Text popup persistent while in Edit mode
        val btnText = findViewById<ImageButton>(R.id.btnText)
        inkCanvas.textUiCallbacks = object : InkCanvasView.TextUiCallbacks {
            override fun onTextEditStateChanged(editing: Boolean) {
                // If entering Edit -> ensure popup is visible; if leaving -> hide it.
                val isShowing = (textPopup?.isShowing == true)
                // Always clear any IME lift when we exit edit mode
                if (!editing) inkCanvas.setImeLiftPx(0f)

                if (editing && !isShowing) {
                    toggleTextPopup(btnText)
                } else if (!editing && isShowing) {
                    toggleTextPopup(btnText)
                }

            }
        }

        inkCanvas.setTextEditCallbacks(object : InkCanvasView.TextEditCallbacks {
            override fun onRequestStartEdit() {
                inkCanvas.setEditingSelectedText(true)   // <— hide canvas glyphs while typing

                val btnText = findViewById<ImageButton>(R.id.btnText)
                if (textPopup?.isShowing != true) toggleTextPopup(btnText)
            }
            override fun onRequestFinishEdit() {

                inkCanvas.setEditingSelectedText(false)  // <— show canvas glyphs again
                textPopup?.dismiss(); textPopup = null
            }
        })



        inkCanvas.setOnPenStyleChangeListener(object : InkCanvasView.OnPenStyleChangeListener {
            override fun onPenStyleChanged(style: InkCanvasView.StrokeStyle) {
                // Keep the activity's state in sync
                penStrokeStyle = style

                // If the stylus popup is open, update the radios so UI matches the actual style
                if (stylusPopup?.isShowing == true) {
                    syncPenStyleRadios()
                }
            }
        })

        // Wire autosave sink (writes internal autosave file whenever canvas requests)
        inkCanvas.setAutosaveListener(object : InkCanvasView.OnAutosaveListener {
            override fun onAutosaveRequested(payload: ByteArray) {
                try {
                    autosaveFile.outputStream().use { it.write(payload) }
                } catch (_: Throwable) { /* ignore */ }
            }
        })


        // Restore per-tool color/size memory (must be after inkCanvas is bound)
        run {
            // Pen family
            penFamilyColor = prefs.getInt(PREF_PEN_COLOR, Color.BLACK)
            // Highlighter
            highlighterColor = prefs.getInt(PREF_HL_COLOR, 0x4DFFD54F.toInt()) // amber @ ~40% alpha
            highlighterSizeDp = prefs.getFloat(PREF_HL_SIZE_DP, 12f)

            // Apply restored defaults to canvas
            inkCanvas.setBrush(BrushType.PEN)
            inkCanvas.setStrokeWidthDp(brushSizeDp)
            inkCanvas.setColor(penFamilyColor)
        }


// --- Restore InkCanvasView document state (pages + strokes, v2) ---
        savedInstanceState?.getByteArray("ink_state")?.let { bytes ->
            try { inkCanvas.deserialize(bytes) } catch (_: Throwable) { /* ignore bad payloads */ }
        }
        // If not restoring from rotation state, try loading autosave
        if (savedInstanceState == null) {
            try {
                if (autosaveFile.exists()) {
                    val bytes = autosaveFile.readBytes()
                    inkCanvas.deserialize(bytes)
                }
            } catch (_: Throwable) { /* ignore bad autosave */ }
        }


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
        // ---- Safety: ensure header content is visible ----
        if (titleDisplay.visibility != View.VISIBLE && titleEdit.visibility != View.VISIBLE) {
            titleEdit.visibility = View.GONE
            titleDisplay.visibility = View.VISIBLE
        }
        if (titleDisplay.text.isNullOrBlank()) {
            titleDisplay.text = getString(R.string.untitled)
        }

        // Make sure the pencil (edit title) button is a hard, live target.
        btnTitleEdit.isEnabled = true
        btnTitleEdit.isClickable = true
        btnTitleEdit.isClickable = true
        btnTitleEdit.isFocusable = true
        btnTitleEdit.isFocusableInTouchMode = false
        btnTitleEdit.setOnClickListener { enterTitleEditMode() }

// Keep it above the canvas in z-order at runtime (in case XML order/elevation lags)
        btnTitleEdit.bringToFront()


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
        btnHandKbd = findViewById(R.id.btnHandKbd)
        btnCameraPen = findViewById(R.id.btnCameraPen)
        btnGalleryPen = findViewById(R.id.btnGalleryPen)
        btnOverflowPen = findViewById(R.id.btnOverflowPen)


        btnCameraKbd = findViewById(R.id.btnCameraKbd)
        btnGalleryKbd = findViewById(R.id.btnGalleryKbd)
        btnOverflowKbd = findViewById(R.id.btnOverflowKbd)
        // Ensure the overflow buttons use the proper "more" icon (not the up-arrow)
        btnOverflowPen.setImageResource(R.drawable.ic_more_vert)
        btnOverflowKbd.setImageResource(R.drawable.ic_more_vert)
        // One toggle listener for both "hand" buttons; uses the cached fields (no extra findViewById)
        val handToggle = View.OnClickListener { v ->
            val btn = v as ImageButton
            btn.isSelected = !btn.isSelected
            inkCanvas.setPanMode(btn.isSelected)
            Toast.makeText(
                this,
                if (btn.isSelected) "Hand (pan) tool ON" else "Hand tool OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnHandPen.setOnClickListener(handToggle)
        btnHandKbd.setOnClickListener(handToggle)



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



        findViewById<ImageButton>(R.id.btnShapes).setOnClickListener { v ->
            dismissAllPopups()
            toggleShapesPopup(v)
        }





        // Show pen toolbar
        showPenMenu()

        // Enlarge small touch targets
        findViewById<View>(R.id.btnKeyboardToggle).expandTouchTarget(12)
        findViewById<View>(R.id.btnPenToggle).expandTouchTarget(12)
        btnTitleEdit.expandTouchTarget(12)

        // Title edit behavior (keep only the IME action)
        titleEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                exitTitleEditMode(save = true); true
            } else false
        }

        if (savedInstanceState == null && titleDisplay.text.isNullOrBlank()) {
            titleDisplay.text = getString(R.string.untitled)
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

        // Toolbar: Stylus (open/close only; DO NOT switch family here)
        btnStylus.setOnClickListener { v ->
            resetPan()

            // Never touch selection or toolFamily on toolbar open
            toggleStylusPopup(v)
            updateToolbarActiveStates()
        }





        // Toolbar: Highlighter — switch family + apply HL state, then open popup
        btnHighlighter.setOnClickListener { v ->
            resetPan()

            // Real tool switch (end any selection; arm HL)
            if (toolFamily != ToolFamily.HIGHLIGHTER) {
                inkCanvas.setSelectionToolNone(keepSelection = false)
                selectionArmed = false
                selectPopup?.dismiss()

                toolFamily = ToolFamily.HIGHLIGHTER
                applyHighlighterBrush()   // << sets HL brush + size + color (already defined below)
                updateToolbarActiveStates()
            }

            toggleHighlighterPopup(v)
        }


        // Toolbar: Eraser (open/close only; DO NOT switch family here)
        btnEraser.setOnClickListener { v ->
            resetPan()

            toggleEraserPopup(v)
            updateToolbarActiveStates()
        }




        // Selection popup (lasso/rect + mode)
        btnSelectRect.setOnClickListener { v -> toggleSelectPopup(v) }



        btnCameraPen.setOnClickListener { launchCameraFlow() }
        btnCameraKbd.setOnClickListener { launchCameraFlow() }
        btnGalleryPen.setOnClickListener { launchGalleryFlow() }
        btnGalleryKbd.setOnClickListener { launchGalleryFlow() }
        // Keyboard → Text popup
        findViewById<ImageButton>(R.id.btnText).setOnClickListener {
            // Insert a text box of a good size and immediately open the editor
            dismissAllPopups()
            inkCanvas.startTextBoxAtCenter(
                color = textColor,
                textSizeDp = textSizeDp,
                isBold = textBold,
                isItalic = textItalic
            )
            inkCanvas.setEditingSelectedText(true)
            toggleTextPopup(it) // keep the text popup visible while editing

        }


        // Overflow menus
        btnOverflowPen.setOnClickListener { showOverflowMenu(it) }
        btnOverflowKbd.setOnClickListener { showOverflowMenu(it) }

        updateToolbarActiveStates()


    }
    override fun onPause() {
        super.onPause()
        try {
            val bytes = inkCanvas.serialize()
            autosaveFile.outputStream().use { it.write(bytes) }
        } catch (_: Throwable) { /* ignore */ }
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

        // Persist latest pen color
        prefs.edit().putInt(PREF_PEN_COLOR, penFamilyColor).apply()
    }
    /**
     * Generic color palette dialog used by Text/Fill chips.
     * - If allowNone=true, shows a "None" action that returns null.
     * - Otherwise returns the picked color via onPicked.
     */
    private fun showColorPalette(anchor: View, current: Int, allowNone: Boolean, onPicked: (Int?) -> Unit) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_color_palette, null, false)
        val container = dialogView.findViewById<LinearLayout>(R.id.paletteContainer)

        val columns = 5
        val tileSize = 48.dp()
        val tileMargin = 8.dp()

        var tempColor = current
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

        // Custom color tile
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

        val builder = AlertDialog.Builder(this)
            .setTitle("Pick a color")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { d, _ ->
                onPicked(tempColor)
                d.dismiss()
                inkCanvas.requestFocus()
            }

        if (allowNone) {
            builder.setNeutralButton("None") { d, _ ->
                onPicked(null)
                d.dismiss()
                inkCanvas.requestFocus()
            }
        }

        builder.show()
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
        dismissAllPopups() // close any other open submenu
        stylusPopup = createStylusMenuPopup(anchor).also { popup ->
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            // PopupWindow requires a non-null background for outside touches to work:

            showPopupAnchoredWithinScreen(popup, anchor)
        }
    }


    private fun createStylusMenuPopup(anchor: View): PopupWindow {
        val content = inflateForPopup(R.layout.popup_stylus_menu, anchor)

        // --- Views from popup_stylus_menu.xml ---
        val btnFountain   = content.findViewById<ImageButton>(R.id.iconFountain)
        val btnCalligraphy= content.findViewById<ImageButton>(R.id.iconCalligraphy)
        val btnPen        = content.findViewById<ImageButton>(R.id.iconPen)
        val btnPencil     = content.findViewById<ImageButton>(R.id.iconPencil)
        val btnMarker     = content.findViewById<ImageButton>(R.id.iconMarker)

        val sizeSlider    = content.findViewById<SeekBar>(R.id.sizeSlider)
        val sizeValue     = content.findViewById<TextView>(R.id.sizeValue)
        val preview       = content.findViewById<BrushPreviewView>(R.id.brushPreview)

        // --- Helpers to reflect current brush state in the UI ---
        fun selectBrushButtons(type: BrushTypeLocal) {
            fun ImageButton.sel(on: Boolean) {
                isSelected = on
                alpha = if (on) 1f else 0.82f
            }
            btnFountain.sel(type == BrushTypeLocal.FOUNTAIN)
            btnCalligraphy.sel(type == BrushTypeLocal.CALLIGRAPHY)
            btnPen.sel(type == BrushTypeLocal.PEN)
            btnPencil.sel(type == BrushTypeLocal.PENCIL)
            btnMarker.sel(type == BrushTypeLocal.MARKER)
        }

        fun applySizeToUiAndCanvas(dp: Float, live: Boolean) {
            val clamped = dp.coerceIn(1f, 60f)
            sizeValue.text = getString(R.string.size_dp, clamped.toInt())

            preview?.setColor(penFamilyColor)
            preview?.setStrokeWidthDp(clamped)
            brushSizeDp = clamped
            if (live && toolFamily == ToolFamily.PEN_FAMILY) {
                inkCanvas.setStrokeWidthDp(brushSizeDp)
            }
        }

        // --- Initialize UI from current in-memory state ---
        selectBrushButtons(brushType)
        sizeSlider.max = 60
        sizeSlider.progress = brushSizeDp.toInt().coerceIn(1, sizeSlider.max)
        applySizeToUiAndCanvas(brushSizeDp, live = false)



        // --- Click listeners: change brush type, then apply ---
        val onBrushClick = View.OnClickListener { v ->
            brushType = when (v.id) {
                R.id.iconFountain    -> BrushTypeLocal.FOUNTAIN
                R.id.iconCalligraphy -> BrushTypeLocal.CALLIGRAPHY
                R.id.iconPen         -> BrushTypeLocal.PEN
                R.id.iconPencil      -> BrushTypeLocal.PENCIL
                R.id.iconMarker      -> BrushTypeLocal.MARKER
                else                 -> brushType
            }
            selectBrushButtons(brushType)

            // This is the moment the user actually CHOOSES a tool → switch family & clear selection
            if (toolFamily != ToolFamily.PEN_FAMILY) {
                inkCanvas.setSelectionToolNone(keepSelection = false)
                selectionArmed = false
                selectPopup?.dismiss()
                toolFamily = ToolFamily.PEN_FAMILY
            }
            applyPenFamilyBrush()
            updateToolbarActiveStates()
            inkCanvas.requestFocus()
        }


        btnFountain.setOnClickListener(onBrushClick)
        btnCalligraphy.setOnClickListener(onBrushClick)
        btnPen.setOnClickListener(onBrushClick)
        btnPencil.setOnClickListener(onBrushClick)
        btnMarker.setOnClickListener(onBrushClick)

        // --- Size slider (live) ---
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val dp = value.coerceAtLeast(1).toFloat()
                applySizeToUiAndCanvas(dp, live = true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Persist latest size only when pen family is active
                if (toolFamily == ToolFamily.PEN_FAMILY) {
                    // nothing else to persist here yet; size is in brushSizeDp already
                }
            }
        })



        // NEW: bind Style radios + Arrow Ends checkbox
        val rg = content.findViewById<RadioGroup>(R.id.rgStrokeStyle)
        val rbSolid  = content.findViewById<RadioButton>(R.id.rbStyleSolid)
        val rbDashed = content.findViewById<RadioButton>(R.id.rbStyleDashed)
        val rbDotted = content.findViewById<RadioButton>(R.id.rbStyleDotted)
        val cbArrow  = content.findViewById<CheckBox>(R.id.cbArrowEnds)
        val cbLock  = content.findViewById<CheckBox>(R.id.cbLockPenStyle)


// Initialize UI from current state
        when (penStrokeStyle) {
            InkCanvasView.StrokeStyle.SOLID  -> rbSolid.isChecked = true
            InkCanvasView.StrokeStyle.DASHED -> rbDashed.isChecked = true
            InkCanvasView.StrokeStyle.DOTTED -> rbDotted.isChecked = true
        }
        cbArrow.isChecked = penArrowEnds
        cbLock.isChecked = penLockStyle


        rg.setOnCheckedChangeListener { _, checkedId ->
            penStrokeStyle = when (checkedId) {
                R.id.rbStyleDashed -> InkCanvasView.StrokeStyle.DASHED
                R.id.rbStyleDotted -> InkCanvasView.StrokeStyle.DOTTED
                else -> InkCanvasView.StrokeStyle.SOLID
            }
            // live-apply for Pen only
            if (toolFamily == ToolFamily.PEN_FAMILY && brushType == BrushTypeLocal.PEN) {
                inkCanvas.setStrokeStyle(penStrokeStyle)
            }
        }

        cbArrow.setOnCheckedChangeListener { _, isChecked ->
            penArrowEnds = isChecked
            if (toolFamily == ToolFamily.PEN_FAMILY && brushType == BrushTypeLocal.PEN) {
                inkCanvas.setArrowEndsForNextStroke(penArrowEnds)
            }
        }
        cbLock.setOnCheckedChangeListener { _, isChecked ->
            penLockStyle = isChecked
            inkCanvas.setLockPenStyle(isChecked)
        }
        // Ensure radios reflect any recent auto-revert before showing
        syncPenStyleRadios()



        // Return a focusable popup that dismisses on outside-tap and restores canvas focus
        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            isOutsideTouchable = true
            elevation = 8f
            setOnDismissListener { stylusPopup = null; inkCanvas.requestFocus() }
        }
    }
    private fun resetPan() {
        // use your cached fields; no extra findViewById
        btnHandPen.isSelected = false
        btnHandKbd.isSelected = false
        inkCanvas.setPanMode(false)
    }


    // REPLACE
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
        // Use per-tool memory for color
        inkCanvas.setColor(penFamilyColor)

        // NEW: style + arrow (only affects next strokes where applicable, i.e., PEN)
        inkCanvas.setStrokeStyle(penStrokeStyle)
        inkCanvas.setArrowEndsForNextStroke(penArrowEnds)
        inkCanvas.setLockPenStyle(penLockStyle)
        // Ensure the UI matches the actual style
        val rg = stylusPopup?.contentView?.findViewById<RadioGroup>(R.id.rgStrokeStyle)
        val rbSolid = stylusPopup?.contentView?.findViewById<RadioButton>(R.id.rbStyleSolid)
        val rbDashed = stylusPopup?.contentView?.findViewById<RadioButton>(R.id.rbStyleDashed)
        val rbDotted = stylusPopup?.contentView?.findViewById<RadioButton>(R.id.rbStyleDotted)
        when (penStrokeStyle) {
            InkCanvasView.StrokeStyle.SOLID  -> rbSolid?.isChecked = true
            InkCanvasView.StrokeStyle.DASHED -> rbDashed?.isChecked = true
            InkCanvasView.StrokeStyle.DOTTED -> rbDotted?.isChecked = true
        }



        // Persist latest pen color
        prefs.edit().putInt(PREF_PEN_COLOR, penFamilyColor).apply()

        updateToolbarActiveStates()
    }

    // Sync the pen style radio group to match penStrokeStyle
    private fun syncPenStyleRadios() {
        val content = stylusPopup?.contentView ?: return
        val rbSolid  = content.findViewById<RadioButton>(R.id.rbStyleSolid)
        val rbDashed = content.findViewById<RadioButton>(R.id.rbStyleDashed)
        val rbDotted = content.findViewById<RadioButton>(R.id.rbStyleDotted)
        when (penStrokeStyle) {
            InkCanvasView.StrokeStyle.SOLID  -> rbSolid?.isChecked  = true
            InkCanvasView.StrokeStyle.DASHED -> rbDashed?.isChecked = true
            InkCanvasView.StrokeStyle.DOTTED -> rbDotted?.isChecked = true
        }
    }

    // ───────── Highlighter popup ─────────
    private fun toggleHighlighterPopup(anchor: View) {
        highlighterPopup?.let {
            if (it.isShowing) { it.dismiss(); highlighterPopup = null; return }
        }
        dismissAllPopups()
        highlighterPopup = createHighlighterPopup(anchor).also { popup ->
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            showPopupAnchoredWithinScreen(popup, anchor)
        }
    }




    private fun createHighlighterPopup(anchor: View): PopupWindow {
        val content = inflateForPopup(R.layout.popup_highlighter_menu, anchor)



        // Views
        val iconFree = content.findViewById<ImageButton>(R.id.iconHLFreeform)
        val iconLine = content.findViewById<ImageButton>(R.id.iconHLStraight)
        val chip     = content.findViewById<ImageButton>(R.id.chipHLColor)
        val slider   = content.findViewById<SeekBar>(R.id.sizeSliderHL)
        val sizeTxt  = content.findViewById<TextView>(R.id.sizeValueHL)
        val preview  = content.findViewById<BrushPreviewView?>(R.id.previewHL)

        // Reflect current state
        fun updateModeUI() {
            iconFree.isSelected = (highlighterMode == HighlighterMode.FREEFORM)
            iconLine.isSelected = (highlighterMode == HighlighterMode.STRAIGHT)
        }
        updateModeUI()

        chip.imageTintList = ColorStateList.valueOf(highlighterColor)
        slider.max = 60
        slider.progress = highlighterSizeDp.toInt().coerceIn(1, slider.max)
        sizeTxt.text = getString(R.string.size_dp, slider.progress)
        preview?.setColor(highlighterColor)
        preview?.setStrokeWidthDp(slider.progress.toFloat())


        // Mode toggle
        val modeClick = View.OnClickListener { v ->
            highlighterMode = if (v.id == R.id.iconHLStraight)
                HighlighterMode.STRAIGHT else HighlighterMode.FREEFORM
            updateModeUI()

            // The user chose a Highlighter mode → this is the real tool switch moment
            if (toolFamily != ToolFamily.HIGHLIGHTER) {
                inkCanvas.setSelectionToolNone(keepSelection = false)
                selectionArmed = false
                selectPopup?.dismiss()
                toolFamily = ToolFamily.HIGHLIGHTER
            }
            inkCanvas.setBrush(
                if (highlighterMode == HighlighterMode.FREEFORM)
                    BrushType.HIGHLIGHTER_FREEFORM
                else
                    BrushType.HIGHLIGHTER_STRAIGHT
            )
            updateToolbarActiveStates()
            inkCanvas.requestFocus()
        }

        iconFree.setOnClickListener(modeClick)
        iconLine.setOnClickListener(modeClick)

        // Color chip → advanced picker
        chip.setOnClickListener {
            showAdvancedColorPicker(highlighterColor) { picked ->
                // AFTER (use lighter default alpha ~30%)
                highlighterColor = if ((picked ushr 24) == 0xFF) {
                    (0x4D shl 24) or (picked and 0x00FFFFFF)
                } else picked

                chip.imageTintList = ColorStateList.valueOf(highlighterColor)
                preview?.setColor(highlighterColor)

                // Always live-apply
                inkCanvas.setColor(highlighterColor)

                prefs.edit().putInt(PREF_HL_COLOR, highlighterColor).apply()
            }
        }


        // Size slider (live update + persist on release)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceAtLeast(1)
                sizeTxt.text = getString(R.string.size_dp, v)
                highlighterSizeDp = v.toFloat()
                preview?.setStrokeWidthDp(highlighterSizeDp)

                // Always live-apply
                inkCanvas.setStrokeWidthDp(highlighterSizeDp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putFloat(PREF_HL_SIZE_DP, highlighterSizeDp).apply()
            }
        })

        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            isOutsideTouchable = true
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
        // Apply persisted size/color for HL
        inkCanvas.setStrokeWidthDp(highlighterSizeDp)
        inkCanvas.setColor(highlighterColor)

        // Persist HL state on each application
        prefs.edit()
            .putInt(PREF_HL_COLOR, highlighterColor)
            .putFloat(PREF_HL_SIZE_DP, highlighterSizeDp)
            .apply()

        updateToolbarActiveStates()
    }

    // ───────── Eraser popup ─────────
    private fun toggleEraserPopup(anchor: View) {
        eraserPopup?.let { if (it.isShowing) { it.dismiss(); eraserPopup = null; return } }
        dismissAllPopups()
        eraserPopup = createEraserPopup(anchor).also { p ->
            p.isOutsideTouchable = true
            p.isFocusable = true

            showPopupAnchoredWithinScreen(p, anchor)
        }
    }


    private fun createEraserPopup(anchor: View): PopupWindow {
        val content = inflateForPopup(R.layout.popup_eraser_menu, anchor)



        val btnStroke = content.findViewById<ImageButton>(R.id.iconEraseStroke)
        val btnArea   = content.findViewById<ImageButton>(R.id.iconEraseArea)
        val sizeBar   = content.findViewById<SeekBar>(R.id.sizeSliderEraser)
        val sizeTxt   = content.findViewById<TextView>(R.id.sizeValueEraser)
        val preview   = content.findViewById<EraserPreviewView>(R.id.previewEraser)
        val hlOnlySw  = content.findViewById<SwitchCompat>(R.id.switchHLOnly)


        fun updateModeUI() {
            btnStroke.isSelected = (eraserMode == EraserMode.STROKE)
            btnArea.isSelected   = (eraserMode == EraserMode.AREA)
        }
        updateModeUI()

        val onModeClick = View.OnClickListener { v ->
            eraserMode = if (v.id == R.id.iconEraseStroke) EraserMode.STROKE else EraserMode.AREA
            updateModeUI()

            // The user chose Eraser mode → this is the real tool switch moment
            if (toolFamily != ToolFamily.ERASER) {
                inkCanvas.setSelectionToolNone(keepSelection = false)
                selectionArmed = false
                selectPopup?.dismiss()
                toolFamily = ToolFamily.ERASER
            }
            inkCanvas.setBrush(
                if (eraserMode == EraserMode.AREA) BrushType.ERASER_AREA else BrushType.ERASER_STROKE
            )
            updateToolbarActiveStates()
            // keep popup open
            inkCanvas.requestFocus()
        }

        btnStroke.setOnClickListener(onModeClick)
        btnArea.setOnClickListener(onModeClick)

        sizeBar.max = 100
        sizeBar.progress = eraserSizeDp.toInt().coerceIn(1, 100)
        sizeTxt.text = getString(R.string.size_dp, sizeBar.progress)
        preview.setDiameterPx(sizeBar.progress.dp().toFloat())

        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceIn(1, 100)
                sizeTxt.text = getString(R.string.size_dp, v)
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
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            isOutsideTouchable = true
            elevation = 8f
            setOnDismissListener { eraserPopup = null; inkCanvas.requestFocus() }
        }
    }




    // ───────── Selection popup (lasso/rect + mode toggle) ─────────
    private fun toggleSelectPopup(anchor: View) {
        selectPopup?.let { if (it.isShowing) { it.dismiss(); selectPopup = null; return } }
        dismissAllPopups()
        selectPopup = createSelectPopup(anchor).also { p ->
            p.isOutsideTouchable = true
            p.isFocusable = true

            showPopupAnchoredWithinScreen(p, anchor)
        }
    }




    private fun createSelectPopup(anchor: View): PopupWindow {
        val content = inflateForPopup(R.layout.popup_select_menu, anchor)



        val iconLasso = content.findViewById<ImageButton>(R.id.iconSelectLasso)
        val iconRect  = content.findViewById<ImageButton>(R.id.iconSelectRect)
        val modeSw    = content.findViewById<SwitchCompat>(R.id.switchStrokeSelect)


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
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            isOutsideTouchable = true
            elevation = 8f
            setOnDismissListener { selectPopup = null; inkCanvas.requestFocus() }
        }
    }
    // ===== Shapes popup =====
    private var shapesPopup: PopupWindow? = null
    private var shapesStrokeDp: Float = 6f
    private var shapesStrokeColor: Int = Color.BLUE
    private var shapesFillColor: Int? = null // null => no fill

    // ===== Shapes popup =====
    private fun toggleShapesPopup(anchor: View) {
        shapesPopup?.let { popup ->
            if (popup.isShowing) {
                popup.dismiss()
                shapesPopup = null
                return
            }
        }
        dismissAllPopups()
        shapesPopup = createShapesPopup(anchor).also { popup ->
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            showPopupAnchoredWithinScreen(popup, anchor)
        }
    }
    private fun toggleTextPopup(anchor: View) {
        textPopup?.let { if (it.isShowing) { it.dismiss(); textPopup = null; return } }
        dismissAllPopups()

        val parent = (anchor.rootView as? ViewGroup) ?: window.decorView as ViewGroup
        val content = layoutInflater.inflate(R.layout.popup_text_menu, null)

        // --- Text popup chip wiring (3 recents + More…) ---
        val preview = content.findViewById<TextView>(R.id.previewText)
        val sizeSeek = content.findViewById<SeekBar>(R.id.sizeSeek)
        val sizeLabel = content.findViewById<TextView>(R.id.sizeLabel)
        val chkBold = content.findViewById<CheckBox>(R.id.chkBold)
        val chkItalic = content.findViewById<CheckBox>(R.id.chkItalic)
        val cornerSeek = content.findViewById<SeekBar>(R.id.cornerSeek)
        val cornerLabel = content.findViewById<TextView>(R.id.cornerLabel)
        // Text/Fill chips inside the popup content
        val chipTxt1 = content.findViewById<ImageButton>(R.id.chipTxt1)
        val chipTxt2 = content.findViewById<ImageButton>(R.id.chipTxt2)
        val chipTxt3 = content.findViewById<ImageButton>(R.id.chipTxt3)
        val chipBg1  = content.findViewById<ImageButton>(R.id.chipBg1)
        val chipBg2  = content.findViewById<ImageButton>(R.id.chipBg2)
        val chipBg3  = content.findViewById<ImageButton>(R.id.chipBg3)

        val chipBgNone = content.findViewById<TextView>(R.id.chipBgNone)
        fun setChipVisual(btn: ImageButton, @ColorInt color: Int) {
            btn.setImageResource(R.drawable.ic_tool_color)
            btn.background = ContextCompat.getDrawable(this, R.drawable.bg_color_chip_selector)
            btn.imageTintList = ColorStateList.valueOf(color)
        }
        @ColorInt fun getChipColor(btn: ImageButton): Int =
            btn.imageTintList?.defaultColor ?: Color.BLACK

        // Default palettes (Text = bold; Fill = lighter)
        val textDefaults = intArrayOf(
            0xFF000000.toInt(), // black
            0xFF1E88E5.toInt(), // blue 600
            0xFFE53935.toInt()  // red 600
        )
        val fillDefaults = intArrayOf(
            0xFFFFF59D.toInt(), // light yellow
            0xFFB2EBF2.toInt(), // light cyan
            0xFFF8BBD0.toInt()  // light pink
        )

// MRU recents (or defaults if none yet)
        var textRecents = loadRecentColors("text", textDefaults)
        var fillRecents = loadRecentColors("fill", fillDefaults)

// Paint chips
        setChipVisual(chipTxt1, textRecents[0]); setChipVisual(chipTxt2, textRecents[1]); setChipVisual(chipTxt3, textRecents[2])
        setChipVisual(chipBg1,  fillRecents[0]); setChipVisual(chipBg2,  fillRecents[1]); setChipVisual(chipBg3,  fillRecents[2])
// Track which chip is "selected" in this popup session (stylus semantics)
        var selTextChipId = R.id.chipTxt1
        var selFillChipId = R.id.chipBg1

// Text chips: tap selects; tap again opens the big palette; apply to canvas
        val onTextChipClick = View.OnClickListener { v ->
            val chip = v as ImageButton
            if (chip.id != selTextChipId) {
                selTextChipId = chip.id
                // Apply this chip's color immediately
                inkCanvas.applySelectedTextStyle(color = getChipColor(chip))
                // Promote to MRU: move selected color to slot 0 and repaint first chip
                val picked = getChipColor(chip)
                saveRecentColor("text", picked)
                textRecents = loadRecentColors("text", textDefaults)
                setChipVisual(chipTxt1, textRecents[0])
            } else {
                // Same chip tapped again -> open palette like stylus chips
                showColorPaletteDialog(chip) { picked ->
                    setChipVisual(chip, picked)
                    selTextChipId = chip.id
                    inkCanvas.applySelectedTextStyle(color = picked)
                    saveRecentColor("text", picked)
                    textRecents = loadRecentColors("text", textDefaults)
                    setChipVisual(chipTxt1, textRecents[0])
                }
            }
        }
        chipTxt1.setOnClickListener(onTextChipClick)
        chipTxt2.setOnClickListener(onTextChipClick)
        chipTxt3.setOnClickListener(onTextChipClick)

// Fill chips: same behavior; "None" clears fill
        chipBgNone.setOnClickListener { inkCanvas.applySelectedTextStyle(bg = null) }

        val onFillChipClick = View.OnClickListener { v ->
            val chip = v as ImageButton
            if (chip.id != selFillChipId) {
                selFillChipId = chip.id
                val picked = getChipColor(chip)
                inkCanvas.applySelectedTextStyle(bg = picked)
                saveRecentColor("fill", picked)
                fillRecents = loadRecentColors("fill", fillDefaults)
                setChipVisual(chipBg1, fillRecents[0])
            } else {
                showColorPaletteDialog(chip) { picked ->
                    setChipVisual(chip, picked)
                    selFillChipId = chip.id
                    inkCanvas.applySelectedTextStyle(bg = picked)
                    saveRecentColor("fill", picked)
                    fillRecents = loadRecentColors("fill", fillDefaults)
                    setChipVisual(chipBg1, fillRecents[0])
                }
            }
        }
        chipBg1.setOnClickListener(onFillChipClick)
        chipBg2.setOnClickListener(onFillChipClick)
        chipBg3.setOnClickListener(onFillChipClick)














        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            /* focusable = */ false
        ).apply {
            // Keep popup alive during IME show/animation & outside taps on canvas
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            elevation = 8f * resources.displayMetrics.density
        }
        textPopup = popup
        popup.showAsDropDown(anchor, 0, 0)

    }




    private fun createShapesPopup(anchor: View): PopupWindow {
        val content = inflateForPopup(R.layout.popup_shapes_menu, anchor)



        // Icon buttons
        content.findViewById<ImageButton>(R.id.btnShapeRect).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.RECT)
        }
        content.findViewById<ImageButton>(R.id.btnShapeTriEq).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.TRI_EQ)
        }
        content.findViewById<ImageButton>(R.id.btnShapeTriRight).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.TRI_RIGHT)
        }
        content.findViewById<ImageButton>(R.id.btnShapeCircle).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.CIRCLE)
        }
        content.findViewById<ImageButton>(R.id.btnShapeArc).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.ARC)
        }
        content.findViewById<ImageButton>(R.id.btnShapeLine).setOnClickListener {
            insertShapeFromPopup(InkCanvasView.ShapeKind.LINE)
        }

        // Stroke thickness
        val seek = content.findViewById<SeekBar>(R.id.seekShapeStroke)
        seek.progress = shapesStrokeDp.toInt().coerceIn(1, seek.max)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceIn(1, 48)
                shapesStrokeDp = v.toFloat()
                // Live update thickness on selected items (shape or stroke)
                inkCanvas.updateSelectedStrokeWidthDp(shapesStrokeDp)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Stroke & Fill color chips
        val chipStroke = content.findViewById<ImageButton>(R.id.chipShapeStrokeColor)
        val chipFill   = content.findViewById<ImageButton>(R.id.chipShapeFillColor)

// Initialize tints
        chipStroke.imageTintList = ColorStateList.valueOf(shapesStrokeColor)
        chipFill.imageTintList = ColorStateList.valueOf(shapesFillColor ?: Color.TRANSPARENT)

// Stroke color picker
        chipStroke.setOnClickListener {
            showAdvancedColorPicker(shapesStrokeColor) { picked ->
                shapesStrokeColor = picked
                chipStroke.imageTintList = ColorStateList.valueOf(shapesStrokeColor)
                // Live-update stroke color on selection
                inkCanvas.updateSelectedStrokeColor(shapesStrokeColor)
            }
        }

// Fill color picker (only meaningful if not "No fill")
        chipFill.setOnClickListener {
            val start = shapesFillColor ?: Color.TRANSPARENT
            showAdvancedColorPicker(start) { picked ->
                shapesFillColor = picked
                chipFill.imageTintList = ColorStateList.valueOf(shapesFillColor ?: Color.TRANSPARENT)
                inkCanvas.updateSelectedShapeFill(shapesFillColor)
            }
        }

        // No fill checkbox
        val chkNoFill = content.findViewById<CheckBox>(R.id.chkNoFill)
        chkNoFill.isChecked = (shapesFillColor == null)
        chipFill.isEnabled = !chkNoFill.isChecked
        chkNoFill.setOnCheckedChangeListener { _, isChecked ->
            shapesFillColor = if (isChecked) null else (shapesFillColor ?: Color.TRANSPARENT)
            chipFill.isEnabled = !isChecked
            inkCanvas.updateSelectedShapeFill(shapesFillColor)
        }


        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_popup))
            isOutsideTouchable = true
            elevation = 8f
            setOnDismissListener { shapesPopup = null; inkCanvas.requestFocus() }
        }
    }
    private fun insertShapeFromPopup(kind: InkCanvasView.ShapeKind) {
        val oneInchPx = resources.displayMetrics.xdpi           // ≈ 1"
        val strokePx  = inkCanvas.dpToPx(shapesStrokeDp)        // use canvas dp→px

        inkCanvas.insertShape(
            kind          = kind,
            approxSizePx  = oneInchPx,
            strokeWidthPx = strokePx,
            strokeColor   = shapesStrokeColor,
            fillColor     = shapesFillColor
        )

        shapesPopup?.dismiss()
        shapesPopup = null
        inkCanvas.requestFocus()
    }








    // Per-tool color memory (pen/highlighter already exist; add shapes)
    private var penToolColor: Int = Color.BLACK
    private var highlighterToolColor: Int = 0x66FFD54F.toInt()
    private var shapesToolColor: Int get() = shapesStrokeColor
        set(v) { shapesStrokeColor = v }


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
    private fun hasPermissionCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasPermissionMedia(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissionThenLaunch() {
        val perms = arrayListOf(android.Manifest.permission.CAMERA)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestMediaPermissionThenLaunch() {
        val media = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(arrayOf(media))
    }
    private fun launchCameraFlow() {
        // Prepare temp URI every time (in case prior was cleared)
        pendingCameraUri = createTempImageUri()
        if (!hasPermissionCamera()) {
            requestCameraPermissionThenLaunch()
            return
        }
        val uri = pendingCameraUri ?: return
        takePictureLauncher.launch(uri)

    }

    private fun launchGalleryFlow() {
        if (!hasPermissionMedia()) {
            requestMediaPermissionThenLaunch()
            return
        }
        pickImageLauncher.launch("image/*")
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_camera -> {
                launchCameraFlow(); true
            }
            R.id.action_gallery -> {
                launchGalleryFlow(); true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun decodeBitmapSafelyFromUri(uri: Uri, maxEdgePx: Int = 3000): android.graphics.Bitmap? {
        // 1) Bounds pass
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // 2) Compute inSampleSize
        val longEdge = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (longEdge / sample > maxEdgePx) sample *= 2

        // 3) Decode with sample
        val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts2) }
            ?: return null

        // 4) EXIF orientation
        return try {
            contentResolver.openInputStream(uri)?.use { inS ->
                val exif = androidx.exifinterface.media.ExifInterface(inS)
                when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> raw.rotate(90f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> raw.rotate(180f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> raw.rotate(270f)
                    else -> raw
                }
            } ?: raw
        } catch (_: Throwable) { raw }
    }

    private fun android.graphics.Bitmap.rotate(deg: Float): android.graphics.Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return android.graphics.Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }

    private fun createTempImageUri(): Uri {
        val imagesDir = File(cacheDir, "images").apply { if (!exists()) mkdirs() }
        val tmp = File.createTempFile("capture_", ".jpg", imagesDir)
        return FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            tmp
        )
    }
    // --- Recents memory (3 slots) ---
    private fun prefs() = getSharedPreferences("text_popup_prefs", MODE_PRIVATE)

    private fun loadRecentColors(kind: String, defaults: IntArray): IntArray {
        val p = prefs()
        return intArrayOf(
            p.getInt("${kind}_recent_0", defaults[0]),
            p.getInt("${kind}_recent_1", defaults[1]),
            p.getInt("${kind}_recent_2", defaults[2])
        )
    }

    private fun saveRecentColor(kind: String, color: Int) {
        // MRU: shift down, put new on [0]
        val a = loadRecentColors(kind, intArrayOf(color, color, color))
        val b = intArrayOf(color, a[0], a[1])
        prefs().edit()
            .putInt("${kind}_recent_0", b[0])
            .putInt("${kind}_recent_1", b[1])
            .putInt("${kind}_recent_2", b[2])
            .apply()
    }

    // --- Simple palette “like pen” (grid + custom hex) ---
    private fun showTextColorPalette(anchor: View, current: Int, onPicked: (Int)->Unit) {
        showColorPalette(anchor, current, allowNone = false) { c -> onPicked(c!!) }
    }

    private fun showFillColorPalette(anchor: View, current: Int?, onPicked: (Int?)->Unit) {
        showColorPalette(anchor, current ?: 0xFFFFF59D.toInt(), allowNone = true, onPicked = onPicked)
    }

    // NEW signature: optional onPicked lambda; stylus callers can omit it.
    private fun showColorPaletteDialog(targetChip: ImageButton, onPicked: ((Int) -> Unit)? = null) {
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

        // Custom color (unchanged)
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
                // NEW: call back for text/fill chips; stylus path stays intact if onPicked == null
                targetChip.imageTintList = ColorStateList.valueOf(tempColor)
                onPicked?.invoke(tempColor)
                if (onPicked == null && targetChip.id == selectedChipId) {
                    // stylus: apply to pen color if this chip is the selected one
                    penFamilyColor = tempColor
                    if (toolFamily == ToolFamily.PEN_FAMILY) inkCanvas.setColor(tempColor)
                }
                d.dismiss()
                inkCanvas.requestFocus()
            }
            .show()
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


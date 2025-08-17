package com.pelicankb.jobnotes

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.TouchDelegate
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Panels / groups
    private lateinit var panelPen: View
    private lateinit var panelKeyboard: View
    private lateinit var groupPenChips: View

    // Title
    private lateinit var noteTitle: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        noteTitle = findViewById(R.id.noteTitle)
        panelPen = findViewById(R.id.panelPen)
        panelKeyboard = findViewById(R.id.panelKeyboard)
        groupPenChips = findViewById(R.id.groupPenChips)

        // Default: Pen menu visible
        showPenMenu()

        // Expand hit area around the two toggle buttons
        findViewById<View>(R.id.btnKeyboardToggle).expandTouchTarget(extraDp = 12)
        findViewById<View>(R.id.btnPenToggle).expandTouchTarget(extraDp = 12)

        // Title behavior:
        // - Tap: request focus, select all, show keyboard
        // - When focus gained: select all (helps if user tabs in other ways)
        // - IME action Done: hide keyboard and clear focus
        noteTitle.setOnClickListener {
            noteTitle.requestFocus()
            noteTitle.selectAll()
            showKeyboard(noteTitle)
        }
        noteTitle.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                noteTitle.post { noteTitle.selectAll() }
            }
        }
        noteTitle.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // Ensure default text is present on first creation
        if (savedInstanceState == null && noteTitle.text.isNullOrBlank()) {
            noteTitle.setText("Untitled")
        }
    }

    // XML onClick handlers for the toolbar toggles
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

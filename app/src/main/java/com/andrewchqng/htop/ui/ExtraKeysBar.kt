package com.andrewchqng.htop.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.andrewchqng.htop.pty.MonitorMode

class ExtraKeysBar(context: Context) : HorizontalScrollView(context), TerminalView.ModifierDelegate {

    var onKeyBytes: ((ByteArray) -> Unit)? = null
    var onSpecialKeyCode: ((Int) -> Unit)? = null
    var onToggleKeyboard: (() -> Unit)? = null
    var onZoomDelta: ((Float) -> Unit)? = null
    var onRestartRequested: (() -> Unit)? = null
    var onToggleMonitorMode: (() -> Unit)? = null

    private var ctrlSticky = false
    private lateinit var ctrlButton: TextView
    private lateinit var modeButton: TextView

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }

    init {
        setBackgroundColor(0xFF161B22.toInt())
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        buildButtons()
    }

    override fun isCtrlActive(): Boolean = ctrlSticky

    override fun consumeCtrl(): Boolean {
        if (!ctrlSticky) {
            return false
        }
        ctrlSticky = false
        updateCtrlStyle()
        return true
    }

    private fun buildButtons() {
        addActionButton("KB") { onToggleKeyboard?.invoke() }
        addBytesButton("ESC", byteArrayOf(0x1B))
        addBytesButton("TAB", byteArrayOf('\t'.code.toByte()))
        ctrlButton = addActionButton("CTRL") {
            ctrlSticky = !ctrlSticky
            updateCtrlStyle()
        }
        updateCtrlStyle()

        addRepeatableKeyButton("←", KeyEvent.KEYCODE_DPAD_LEFT)
        addRepeatableKeyButton("↓", KeyEvent.KEYCODE_DPAD_DOWN)
        addRepeatableKeyButton("↑", KeyEvent.KEYCODE_DPAD_UP)
        addRepeatableKeyButton("→", KeyEvent.KEYCODE_DPAD_RIGHT)

        addBytesButton("/", "/".toByteArray())
        addBytesButton("\\", "\\".toByteArray())
        addBytesButton("SPC", " ".toByteArray())

        addKeyCodeButton("PGUP", KeyEvent.KEYCODE_PAGE_UP)
        addKeyCodeButton("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        addKeyCodeButton("HOME", KeyEvent.KEYCODE_MOVE_HOME)
        addKeyCodeButton("END", KeyEvent.KEYCODE_MOVE_END)

        addKeyCodeButton("F1", KeyEvent.KEYCODE_F1)
        addKeyCodeButton("F2", KeyEvent.KEYCODE_F2)
        addKeyCodeButton("F3", KeyEvent.KEYCODE_F3)
        addKeyCodeButton("F4", KeyEvent.KEYCODE_F4)
        addKeyCodeButton("F5", KeyEvent.KEYCODE_F5)
        addKeyCodeButton("F6", KeyEvent.KEYCODE_F6)
        addKeyCodeButton("F7", KeyEvent.KEYCODE_F7)
        addKeyCodeButton("F8", KeyEvent.KEYCODE_F8)
        addKeyCodeButton("F9", KeyEvent.KEYCODE_F9)
        addKeyCodeButton("F10", KeyEvent.KEYCODE_F10)

        addActionButton("A-") { onZoomDelta?.invoke(-1f) }
        addActionButton("A+") { onZoomDelta?.invoke(+1f) }
        addActionButton("↻") { onRestartRequested?.invoke() }
        modeButton = addActionButton("⇄top") { onToggleMonitorMode?.invoke() }
    }

    fun setMonitorMode(mode: MonitorMode) {
        modeButton.text = if (mode == MonitorMode.HTOP) "⇄top" else "⇄htop"
    }

    private fun makeButton(label: String): TextView {
        val btn = TextView(context).apply {
            text = label
            setTextColor(0xFFE6EDF3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = dp(40)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = createPillDrawable(0xFF21262D.toInt(), 0xFF30363D.toInt())
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(32),
        ).apply {
            marginEnd = dp(4)
        }
        row.addView(btn, lp)
        return btn
    }

    private fun addActionButton(label: String, onClick: () -> Unit): TextView {
        val btn = makeButton(label)
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun addBytesButton(label: String, bytes: ByteArray) {
        val btn = makeButton(label)
        btn.setOnClickListener { onKeyBytes?.invoke(bytes) }
    }

    private fun addKeyCodeButton(label: String, keyCode: Int) {
        val btn = makeButton(label)
        btn.setOnClickListener { onSpecialKeyCode?.invoke(keyCode) }
    }

    private fun addRepeatableKeyButton(label: String, keyCode: Int) {
        val btn = makeButton(label)
        val repeater = object : Runnable {
            override fun run() {
                onSpecialKeyCode?.invoke(keyCode)
                btn.postDelayed(this, 55L)
            }
        }
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onSpecialKeyCode?.invoke(keyCode)
                    v.removeCallbacks(repeater)
                    v.postDelayed(repeater, 320L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(repeater)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCtrlStyle() {
        if (ctrlSticky) {
            ctrlButton.background = createPillDrawable(0xFF1F6FEB.toInt(), 0xFF58A6FF.toInt())
            ctrlButton.setTextColor(0xFFFFFFFF.toInt())
        } else {
            ctrlButton.background = createPillDrawable(0xFF21262D.toInt(), 0xFF30363D.toInt())
            ctrlButton.setTextColor(0xFFE6EDF3.toInt())
        }
    }

    private fun createPillDrawable(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(fill)
            setStroke(1, stroke)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
}

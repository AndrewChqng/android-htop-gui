package com.andrewchqng.htop.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.andrewchqng.htop.terminal.TerminalBuffer
import com.andrewchqng.htop.terminal.TerminalColors
import com.andrewchqng.htop.terminal.TerminalEmulator
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface ModifierDelegate {
        fun isCtrlActive(): Boolean
        fun consumeCtrl(): Boolean
    }

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            updateTerminalGridSize()
            invalidate()
        }

    var onInputBytes: ((ByteArray) -> Unit)? = null
    var onGridSizeChanged: ((rows: Int, cols: Int, widthPx: Int, heightPx: Int) -> Unit)? = null
    var onFontSizeChanged: ((Float) -> Unit)? = null
    var modifierDelegate: ModifierDelegate? = null

    private val normalTypeface = Typeface.MONOSPACE
    private val boldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val boldItalicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = normalTypeface
        isSubpixelText = true
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val cursorPaint = Paint().apply {
        color = TerminalColors.CURSOR_COLOR
        alpha = 140
        style = Paint.Style.FILL
    }

    private var cellWidth = 14f
    private var cellHeight = 28f
    private var baselineOffset = 22f
    private var originX = 0f
    private var originY = 0f

    var terminalCols: Int = 80
        private set
    var terminalRows: Int = 24
        private set

    var fontSizeSp: Float = 11f
        set(value) {
            val clamped = value.coerceIn(7f, 28f)
            if (field != clamped) {
                field = clamped
                recomputeMetrics()
                updateTerminalGridSize()
                invalidate()
            }
        }

    private val redrawScheduled = AtomicBoolean(false)

    // Reusable snapshot buffers so onDraw never holds emulator.lock while calling Canvas
    private var snapCols = 0
    private var snapRows = 0
    private var snapChars = IntArray(0)
    private var snapFg = IntArray(0)
    private var snapBg = IntArray(0)
    private var snapAttrs = IntArray(0)
    private var rowCharScratch = CharArray(256)

    // Gesture & mouse state
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var accumDy = 0f
    private var accumDx = 0f
    private var touchMoved = false
    private var multiTouchUsed = false
    private var activeMouseButton = -1
    private var activeMouseDown = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (fontSizeSp * detector.scaleFactor).coerceIn(7f, 28f)
                if (abs(next - fontSizeSp) >= 0.05f) {
                    fontSizeSp = next
                    onFontSizeChanged?.invoke(fontSizeSp)
                }
                return true
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        recomputeMetrics()
    }

    fun notifyScreenUpdated() {
        if (redrawScheduled.compareAndSet(false, true)) {
            postInvalidateOnAnimation()
        }
    }

    private fun recomputeMetrics() {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            resources.displayMetrics,
        )
        textPaint.textSize = px
        textPaint.typeface = normalTypeface
        cellWidth = max(4f, textPaint.measureText("M"))
        val fm = textPaint.fontMetrics
        val rawHeight = fm.descent - fm.ascent + fm.leading
        cellHeight = max(8f, ceil(rawHeight))
        baselineOffset = -fm.ascent + (cellHeight - (fm.descent - fm.ascent)) * 0.5f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTerminalGridSize()
    }

    private var hasReportedSize = false
    private var lastReportedWidth = 0
    private var lastReportedHeight = 0

    private fun updateTerminalGridSize() {
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= cellWidth || availH <= cellHeight) {
            return
        }
        val newCols = max(1, floor(availW / cellWidth).toInt())
        val newRows = max(1, floor(availH / cellHeight).toInt())
        originX = paddingLeft.toFloat()
        originY = paddingTop.toFloat()

        val changed = !hasReportedSize ||
            newCols != terminalCols ||
            newRows != terminalRows ||
            width != lastReportedWidth ||
            height != lastReportedHeight
        terminalCols = newCols
        terminalRows = newRows
        emulator?.resize(newCols, newRows)
        if (changed) {
            hasReportedSize = true
            lastReportedWidth = width
            lastReportedHeight = height
            onGridSizeChanged?.invoke(newRows, newCols, width, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        redrawScheduled.set(false)
        canvas.drawColor(TerminalColors.DEFAULT_BG)

        val emu = emulator ?: return
        var cols = 0
        var rows = 0
        var cursorRow = 0
        var cursorCol = 0
        var cursorVisible = false

        synchronized(emu.lock) {
            val buf = emu.buffer
            cols = buf.cols
            rows = buf.rows
            cursorRow = buf.cursorRow
            cursorCol = buf.cursorCol
            cursorVisible = buf.cursorVisible
            val total = cols * rows
            if (snapChars.size < total) {
                snapChars = IntArray(total)
                snapFg = IntArray(total)
                snapBg = IntArray(total)
                snapAttrs = IntArray(total)
            }
            snapCols = cols
            snapRows = rows
            val scr = buf.active
            scr.chars.copyInto(snapChars, 0, 0, total)
            scr.fg.copyInto(snapFg, 0, 0, total)
            scr.bg.copyInto(snapBg, 0, 0, total)
            scr.attrs.copyInto(snapAttrs, 0, 0, total)
        }

        if (cols <= 0 || rows <= 0) {
            return
        }
        if (rowCharScratch.size < cols * 2) {
            rowCharScratch = CharArray(cols * 2)
        }

        for (r in 0 until rows) {
            val rowOff = r * cols
            val top = originY + r * cellHeight
            val bottom = top + cellHeight
            val baseline = top + baselineOffset

            // 1. Background fill runs
            var c = 0
            while (c < cols) {
                val idx = rowOff + c
                val effBg = effectiveBg(snapFg[idx], snapBg[idx], snapAttrs[idx])
                var runEnd = c + 1
                while (runEnd < cols) {
                    val nIdx = rowOff + runEnd
                    if (effectiveBg(snapFg[nIdx], snapBg[nIdx], snapAttrs[nIdx]) != effBg) {
                        break
                    }
                    runEnd++
                }
                if (effBg != TerminalColors.DEFAULT_BG) {
                    bgPaint.color = effBg
                    canvas.drawRect(
                        originX + c * cellWidth,
                        top,
                        originX + runEnd * cellWidth,
                        bottom,
                        bgPaint,
                    )
                }
                c = runEnd
            }

            // 2. Foreground text runs (batch contiguous ASCII, place non-ASCII/wide individually)
            c = 0
            while (c < cols) {
                val idx = rowOff + c
                val cp = snapChars[idx]
                if (cp == TerminalBuffer.WIDE_CONTINUATION || cp <= ' '.code) {
                    c++
                    continue
                }
                val attrs = snapAttrs[idx]
                if ((attrs and TerminalBuffer.ATTR_INVISIBLE) != 0) {
                    c++
                    continue
                }
                val effFg = effectiveFg(snapFg[idx], snapBg[idx], attrs)
                applyTextStyle(effFg, attrs)

                if (cp in 0x21..0x7E) {
                    val startCol = c
                    var charLen = 0
                    while (c < cols) {
                        val nIdx = rowOff + c
                        val nCp = snapChars[nIdx]
                        val nAttrs = snapAttrs[nIdx]
                        if (nCp !in 0x20..0x7E || nAttrs != attrs ||
                            effectiveFg(snapFg[nIdx], snapBg[nIdx], nAttrs) != effFg
                        ) {
                            break
                        }
                        rowCharScratch[charLen++] = nCp.toChar()
                        c++
                    }
                    val x = originX + startCol * cellWidth
                    canvas.drawText(rowCharScratch, 0, charLen, x, baseline, textPaint)
                    drawDecorations(canvas, x, x + (c - startCol) * cellWidth, top, bottom, baseline, effFg, attrs)
                } else {
                    val isWide = (c + 1 < cols && snapChars[idx + 1] == TerminalBuffer.WIDE_CONTINUATION)
                    val charCount = Character.toChars(cp, rowCharScratch, 0)
                    val x = originX + c * cellWidth
                    canvas.drawText(rowCharScratch, 0, charCount, x, baseline, textPaint)
                    val widthCells = if (isWide) 2 else 1
                    drawDecorations(canvas, x, x + widthCells * cellWidth, top, bottom, baseline, effFg, attrs)
                    c += widthCells
                }
            }
        }

        if (cursorVisible && cursorRow in 0 until rows && cursorCol in 0 until cols) {
            val left = originX + cursorCol * cellWidth
            val top = originY + cursorRow * cellHeight
            canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
        }
    }

    private fun effectiveFg(rawFg: Int, rawBg: Int, attrs: Int): Int {
        val reverse = (attrs and TerminalBuffer.ATTR_REVERSE) != 0
        var color = if (reverse) {
            if (rawBg != 0) rawBg else TerminalColors.DEFAULT_BG
        } else {
            if (rawFg != 0) rawFg else TerminalColors.DEFAULT_FG
        }
        if ((attrs and TerminalBuffer.ATTR_DIM) != 0) {
            color = (color and 0x00FFFFFF) or 0xAA000000.toInt()
        }
        return color
    }

    private fun effectiveBg(rawFg: Int, rawBg: Int, attrs: Int): Int {
        val reverse = (attrs and TerminalBuffer.ATTR_REVERSE) != 0
        return if (reverse) {
            if (rawFg != 0) rawFg else TerminalColors.DEFAULT_FG
        } else {
            if (rawBg != 0) rawBg else TerminalColors.DEFAULT_BG
        }
    }

    private fun applyTextStyle(fgColor: Int, attrs: Int) {
        textPaint.color = fgColor
        val bold = (attrs and TerminalBuffer.ATTR_BOLD) != 0
        val italic = (attrs and TerminalBuffer.ATTR_ITALIC) != 0
        textPaint.typeface = when {
            bold && italic -> boldItalicTypeface
            bold -> boldTypeface
            italic -> italicTypeface
            else -> normalTypeface
        }
    }

    private fun drawDecorations(
        canvas: Canvas,
        xStart: Float,
        xEnd: Float,
        top: Float,
        bottom: Float,
        baseline: Float,
        color: Int,
        attrs: Int,
    ) {
        if ((attrs and TerminalBuffer.ATTR_UNDERLINE) != 0) {
            linePaint.color = color
            val y = (baseline + 2f).coerceAtMost(bottom - 1f)
            canvas.drawLine(xStart, y, xEnd, y, linePaint)
        }
        if ((attrs and TerminalBuffer.ATTR_STRIKETHROUGH) != 0) {
            linePaint.color = color
            val y = (top + bottom) * 0.5f
            canvas.drawLine(xStart, y, xEnd, y, linePaint)
        }
    }

    private fun cellColForX(x: Float): Int =
        ((x - originX) / cellWidth).toInt().coerceIn(0, terminalCols - 1) + 1

    private fun cellRowForY(y: Float): Int =
        ((y - originY) / cellHeight).toInt().coerceIn(0, terminalRows - 1) + 1

    private fun isHardwareMouse(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    private fun mouseModifiers(event: MotionEvent): Int {
        var mod = 0
        val meta = event.metaState
        if ((meta and KeyEvent.META_SHIFT_ON) != 0) mod = mod or 4
        if ((meta and KeyEvent.META_ALT_ON) != 0) mod = mod or 8
        if ((meta and KeyEvent.META_CTRL_ON) != 0) mod = mod or 16
        return mod
    }

    private fun sendMouse(button: Int, col: Int, row: Int, pressed: Boolean, modifiers: Int = 0) {
        val emu = emulator ?: return
        val sgr: Boolean
        val tracking: Int
        synchronized(emu.lock) {
            sgr = emu.buffer.sgrMouseMode
            tracking = emu.buffer.mouseTrackingMode
        }
        val bytes = InputEncoder.encodeMouse(button, col, row, pressed, sgr, tracking, modifiers)
        if (bytes != null) {
            onInputBytes?.invoke(bytes)
        }
    }

    private fun isMouseTrackingActive(): Boolean {
        val emu = emulator ?: return false
        synchronized(emu.lock) {
            return emu.buffer.mouseTrackingMode != 0
        }
    }

    private fun isAppCursorKeys(): Boolean {
        val emu = emulator ?: return false
        synchronized(emu.lock) {
            return emu.buffer.applicationCursorKeys
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isHardwareMouse(event)) {
            return handleHardwareMouseTouch(event)
        }

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x
                downY = event.y
                lastDragX = event.x
                lastDragY = event.y
                accumDy = 0f
                accumDx = 0f
                touchMoved = false
                multiTouchUsed = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchUsed = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiTouchUsed || scaleDetector.isInProgress || event.pointerCount > 1) {
                    multiTouchUsed = true
                    return true
                }
                val dxTotal = event.x - downX
                val dyTotal = event.y - downY
                if (!touchMoved && (dxTotal * dxTotal + dyTotal * dyTotal) > touchSlop * touchSlop) {
                    touchMoved = true
                }
                if (touchMoved) {
                    accumDy += event.y - lastDragY
                    accumDx += event.x - lastDragX
                    lastDragX = event.x
                    lastDragY = event.y

                    val vStep = cellHeight.coerceAtLeast(16f)
                    val hStep = (cellWidth * 1.5f).coerceAtLeast(16f)

                    val col = cellColForX(event.x)
                    val row = cellRowForY(event.y)

                    if (abs(accumDy) >= abs(accumDx) * 0.8f) {
                        while (accumDy <= -vStep) {
                            accumDy += vStep
                            sendWheelOrArrow(down = true, col = col, row = row)
                        }
                        while (accumDy >= vStep) {
                            accumDy -= vStep
                            sendWheelOrArrow(down = false, col = col, row = row)
                        }
                    } else {
                        while (accumDx <= -hStep) {
                            accumDx += hStep
                            sendArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        }
                        while (accumDx >= hStep) {
                            accumDx -= hStep
                            sendArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!touchMoved && !multiTouchUsed && !scaleDetector.isInProgress) {
                    val col = cellColForX(event.x)
                    val row = cellRowForY(event.y)
                    if (isMouseTrackingActive()) {
                        sendMouse(InputEncoder.MOUSE_LEFT, col, row, pressed = true)
                        sendMouse(InputEncoder.MOUSE_LEFT, col, row, pressed = false)
                    }
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleHardwareMouseTouch(event: MotionEvent): Boolean {
        val col = cellColForX(event.x)
        val row = cellRowForY(event.y)
        val mods = mouseModifiers(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val btnState = event.buttonState
                activeMouseButton = when {
                    (btnState and MotionEvent.BUTTON_SECONDARY) != 0 -> InputEncoder.MOUSE_RIGHT
                    (btnState and MotionEvent.BUTTON_TERTIARY) != 0 -> InputEncoder.MOUSE_MIDDLE
                    else -> InputEncoder.MOUSE_LEFT
                }
                activeMouseDown = true
                sendMouse(activeMouseButton, col, row, pressed = true, modifiers = mods)
            }

            MotionEvent.ACTION_MOVE -> {
                val emu = emulator
                val tracking = if (emu != null) synchronized(emu.lock) { emu.buffer.mouseTrackingMode } else 0
                if (activeMouseDown && tracking >= 1002 && activeMouseButton >= 0) {
                    sendMouse(activeMouseButton + 32, col, row, pressed = true, modifiers = mods)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeMouseDown && activeMouseButton >= 0) {
                    sendMouse(activeMouseButton, col, row, pressed = false, modifiers = mods)
                }
                activeMouseDown = false
                activeMouseButton = -1
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isHardwareMouse(event) && !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return super.onGenericMotionEvent(event)
        }
        val col = cellColForX(event.x)
        val row = cellRowForY(event.y)
        val mods = mouseModifiers(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    val steps = abs(vScroll).toInt().coerceIn(1, 5)
                    val down = vScroll < 0f
                    repeat(steps) {
                        sendWheelOrArrow(down = down, col = col, row = row, modifiers = mods)
                    }
                    return true
                }
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (hScroll != 0f) {
                    val key = if (hScroll > 0f) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    sendArrowKey(key)
                    return true
                }
            }

            MotionEvent.ACTION_HOVER_MOVE -> {
                val emu = emulator
                val tracking = if (emu != null) synchronized(emu.lock) { emu.buffer.mouseTrackingMode } else 0
                if (tracking == 1003) {
                    sendMouse(35, col, row, pressed = true, modifiers = mods)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun sendWheelOrArrow(down: Boolean, col: Int, row: Int, modifiers: Int = 0) {
        if (isMouseTrackingActive()) {
            val btn = if (down) InputEncoder.MOUSE_WHEEL_DOWN else InputEncoder.MOUSE_WHEEL_UP
            sendMouse(btn, col, row, pressed = true, modifiers = modifiers)
        } else {
            sendArrowKey(if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    private fun sendArrowKey(keyCode: Int) {
        val bytes = InputEncoder.encodeKeyEvent(
            keyCode = keyCode,
            unicodeChar = 0,
            ctrl = false,
            alt = false,
            shift = false,
            appCursorKeys = isAppCursorKeys(),
        )
        if (bytes != null) {
            onInputBytes?.invoke(bytes)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    val ctrl = modifierDelegate?.consumeCtrl() ?: false
                    val bytes = InputEncoder.encodeText(text, ctrl = ctrl, alt = false)
                    if (bytes.isNotEmpty()) {
                        onInputBytes?.invoke(bytes)
                    }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 32)) {
                    onInputBytes?.invoke(byteArrayOf(0x7F))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    handleTerminalKeyDown(event.keyCode, event)
                } else {
                    super.sendKeyEvent(event)
                }
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onInputBytes?.invoke(byteArrayOf('\r'.code.toByte()))
                return true
            }

            override fun finishComposingText(): Boolean = true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.onKeyDown(keyCode, event)
        }
        if (handleTerminalKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) {
            val ctrl = modifierDelegate?.consumeCtrl() ?: false
            val bytes = InputEncoder.encodeText(event.characters, ctrl = ctrl, alt = event.isAltPressed)
            if (bytes.isNotEmpty()) {
                onInputBytes?.invoke(bytes)
                return true
            }
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    fun handleTerminalKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isModifierKey(keyCode)) {
            return false
        }
        val hwCtrl = event.isCtrlPressed
        val virtualCtrl = modifierDelegate?.consumeCtrl() ?: false
        val ctrl = hwCtrl || virtualCtrl
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        // When Ctrl is held on hardware keyboard, getUnicodeChar(meta) may return 0; strip Ctrl/Meta to extract letter
        val metaForChar = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val unicode = if (ctrl || alt) event.getUnicodeChar(metaForChar) else event.unicodeChar

        val bytes = InputEncoder.encodeKeyEvent(
            keyCode = keyCode,
            unicodeChar = unicode,
            ctrl = ctrl,
            alt = alt,
            shift = shift,
            appCursorKeys = isAppCursorKeys(),
        )
        if (bytes != null) {
            onInputBytes?.invoke(bytes)
            return true
        }
        return false
    }
}

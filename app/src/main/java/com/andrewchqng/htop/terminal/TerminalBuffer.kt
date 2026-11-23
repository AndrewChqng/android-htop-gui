package com.andrewchqng.htop.terminal

import kotlin.math.min

class TerminalBuffer(initialCols: Int = 80, initialRows: Int = 24) {

    class Screen(var cols: Int, var rows: Int) {
        var chars: IntArray = IntArray(cols * rows) { ' '.code }
        var fg: IntArray = IntArray(cols * rows)
        var bg: IntArray = IntArray(cols * rows)
        var attrs: IntArray = IntArray(cols * rows)

        fun resize(newCols: Int, newRows: Int) {
            if (newCols == cols && newRows == rows) {
                return
            }
            val newSize = newCols * newRows
            val newChars = IntArray(newSize) { ' '.code }
            val newFg = IntArray(newSize)
            val newBg = IntArray(newSize)
            val newAttrs = IntArray(newSize)

            val copyRows = min(rows, newRows)
            val copyCols = min(cols, newCols)
            for (r in 0 until copyRows) {
                val srcOff = r * cols
                val dstOff = r * newCols
                chars.copyInto(newChars, dstOff, srcOff, srcOff + copyCols)
                fg.copyInto(newFg, dstOff, srcOff, srcOff + copyCols)
                bg.copyInto(newBg, dstOff, srcOff, srcOff + copyCols)
                attrs.copyInto(newAttrs, dstOff, srcOff, srcOff + copyCols)
                if (copyCols > 0 && newChars[dstOff + copyCols - 1] == WIDE_CONTINUATION) {
                    newChars[dstOff + copyCols - 1] = ' '.code
                }
            }

            cols = newCols
            rows = newRows
            chars = newChars
            fg = newFg
            bg = newBg
            attrs = newAttrs
        }

        fun clearAll(fillBg: Int) {
            chars.fill(' '.code)
            fg.fill(0)
            bg.fill(fillBg)
            attrs.fill(0)
        }
    }

    data class SavedCursor(
        var row: Int = 0,
        var col: Int = 0,
        var fg: Int = 0,
        var bg: Int = 0,
        var attrs: Int = 0,
        var pendingWrap: Boolean = false,
        var g0DecGraphics: Boolean = false,
        var g1DecGraphics: Boolean = false,
        var useG1: Boolean = false,
        var originMode: Boolean = false,
    )

    var cols: Int = initialCols.coerceAtLeast(1)
        private set
    var rows: Int = initialRows.coerceAtLeast(1)
        private set

    private val mainScreen = Screen(cols, rows)
    private val altScreen = Screen(cols, rows)
    var isAltScreen: Boolean = false
        private set

    var active: Screen = mainScreen
        private set

    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var pendingWrap: Boolean = false
    var cursorVisible: Boolean = true

    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    var curFg: Int = 0
    var curBg: Int = 0
    var curAttrs: Int = 0

    var autoWrap: Boolean = true
    var originMode: Boolean = false
    var insertMode: Boolean = false
    var newLineMode: Boolean = false
    var applicationCursorKeys: Boolean = false
    var applicationKeypad: Boolean = false
    var mouseTrackingMode: Int = 0 // 0=off, 1000=normal, 1002=button-event, 1003=any-event
    var sgrMouseMode: Boolean = false
    var bracketedPasteMode: Boolean = false

    var g0DecGraphics: Boolean = false
    var g1DecGraphics: Boolean = false
    var useG1: Boolean = false

    private val savedMainCursor = SavedCursor()
    private val savedAltCursor = SavedCursor()

    fun useAlternateScreen(enable: Boolean, clearOnSwitch: Boolean = true, saveRestoreCursor: Boolean = true) {
        if (enable == isAltScreen) {
            if (enable && clearOnSwitch) {
                active.clearAll(0)
            }
            return
        }
        if (saveRestoreCursor) {
            saveCursor()
        }
        isAltScreen = enable
        active = if (enable) altScreen else mainScreen
        if (enable && clearOnSwitch) {
            active.clearAll(0)
        }
        if (saveRestoreCursor) {
            restoreCursor()
        }
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    fun saveCursor() {
        val target = if (isAltScreen) savedAltCursor else savedMainCursor
        target.row = cursorRow
        target.col = cursorCol
        target.fg = curFg
        target.bg = curBg
        target.attrs = curAttrs
        target.pendingWrap = pendingWrap
        target.g0DecGraphics = g0DecGraphics
        target.g1DecGraphics = g1DecGraphics
        target.useG1 = useG1
        target.originMode = originMode
    }

    fun restoreCursor() {
        val src = if (isAltScreen) savedAltCursor else savedMainCursor
        cursorRow = src.row.coerceIn(0, rows - 1)
        cursorCol = src.col.coerceIn(0, cols - 1)
        curFg = src.fg
        curBg = src.bg
        curAttrs = src.attrs
        pendingWrap = src.pendingWrap && cursorCol == cols - 1
        g0DecGraphics = src.g0DecGraphics
        g1DecGraphics = src.g1DecGraphics
        useG1 = src.useG1
        originMode = src.originMode
    }

    fun reset() {
        useAlternateScreen(enable = false, clearOnSwitch = false, saveRestoreCursor = false)
        mainScreen.clearAll(0)
        altScreen.clearAll(0)
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
        curFg = 0
        curBg = 0
        curAttrs = 0
        autoWrap = true
        originMode = false
        insertMode = false
        newLineMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        mouseTrackingMode = 0
        sgrMouseMode = false
        bracketedPasteMode = false
        g0DecGraphics = false
        g1DecGraphics = false
        useG1 = false
        savedMainCursor.apply {
            row = 0; col = 0; fg = 0; bg = 0; attrs = 0; pendingWrap = false
            g0DecGraphics = false; g1DecGraphics = false; useG1 = false; originMode = false
        }
        savedAltCursor.apply {
            row = 0; col = 0; fg = 0; bg = 0; attrs = 0; pendingWrap = false
            g0DecGraphics = false; g1DecGraphics = false; useG1 = false; originMode = false
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        val clampedCols = newCols.coerceAtLeast(1)
        val clampedRows = newRows.coerceAtLeast(1)
        if (clampedCols == cols && clampedRows == rows) {
            return
        }
        mainScreen.resize(clampedCols, clampedRows)
        altScreen.resize(clampedCols, clampedRows)
        cols = clampedCols
        rows = clampedRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    fun setCell(row: Int, col: Int, codePoint: Int, fg: Int, bg: Int, attrs: Int) {
        if (row !in 0 until rows || col !in 0 until cols) {
            return
        }
        val idx = row * cols + col
        // Clean up any orphaned half of an existing wide character
        if (active.chars[idx] == WIDE_CONTINUATION && col > 0) {
            active.chars[idx - 1] = ' '.code
        } else if (col + 1 < cols && active.chars[idx + 1] == WIDE_CONTINUATION) {
            active.chars[idx + 1] = ' '.code
        }
        active.chars[idx] = codePoint
        active.fg[idx] = fg
        active.bg[idx] = bg
        active.attrs[idx] = attrs
    }

    fun setWideCell(row: Int, col: Int, codePoint: Int, fg: Int, bg: Int, attrs: Int) {
        if (row !in 0 until rows || col !in 0 until (cols - 1)) {
            return
        }
        setCell(row, col, codePoint, fg, bg, attrs)
        val nextIdx = row * cols + col + 1
        if (col + 2 < cols && active.chars[nextIdx + 1] == WIDE_CONTINUATION) {
            active.chars[nextIdx + 1] = ' '.code
        }
        active.chars[nextIdx] = WIDE_CONTINUATION
        active.fg[nextIdx] = fg
        active.bg[nextIdx] = bg
        active.attrs[nextIdx] = attrs
    }

    fun clearRange(row: Int, startCol: Int, endColExclusive: Int, fillBg: Int = curBg) {
        if (row !in 0 until rows) {
            return
        }
        val start = startCol.coerceIn(0, cols)
        val end = endColExclusive.coerceIn(start, cols)
        if (start >= end) {
            return
        }
        val rowOff = row * cols
        if (start > 0 && active.chars[rowOff + start] == WIDE_CONTINUATION) {
            active.chars[rowOff + start - 1] = ' '.code
        }
        if (end < cols && active.chars[rowOff + end] == WIDE_CONTINUATION) {
            active.chars[rowOff + end] = ' '.code
        }
        active.chars.fill(' '.code, rowOff + start, rowOff + end)
        active.fg.fill(0, rowOff + start, rowOff + end)
        active.bg.fill(fillBg, rowOff + start, rowOff + end)
        active.attrs.fill(0, rowOff + start, rowOff + end)
    }

    fun clearRows(startRow: Int, endRowExclusive: Int, fillBg: Int = curBg) {
        val s = startRow.coerceIn(0, rows)
        val e = endRowExclusive.coerceIn(s, rows)
        if (s >= e) {
            return
        }
        val startIdx = s * cols
        val endIdx = e * cols
        active.chars.fill(' '.code, startIdx, endIdx)
        active.fg.fill(0, startIdx, endIdx)
        active.bg.fill(fillBg, startIdx, endIdx)
        active.attrs.fill(0, startIdx, endIdx)
    }

    fun scrollUp(top: Int = scrollTop, bottom: Int = scrollBottom, count: Int = 1, fillBg: Int = curBg) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)
        val n = count.coerceAtLeast(1)
        val regionHeight = b - t + 1
        if (n >= regionHeight) {
            clearRows(t, b + 1, fillBg)
            return
        }
        val srcStart = (t + n) * cols
        val srcEnd = (b + 1) * cols
        val dstStart = t * cols
        active.chars.copyInto(active.chars, dstStart, srcStart, srcEnd)
        active.fg.copyInto(active.fg, dstStart, srcStart, srcEnd)
        active.bg.copyInto(active.bg, dstStart, srcStart, srcEnd)
        active.attrs.copyInto(active.attrs, dstStart, srcStart, srcEnd)
        clearRows(b - n + 1, b + 1, fillBg)
    }

    fun scrollDown(top: Int = scrollTop, bottom: Int = scrollBottom, count: Int = 1, fillBg: Int = curBg) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)
        val n = count.coerceAtLeast(1)
        val regionHeight = b - t + 1
        if (n >= regionHeight) {
            clearRows(t, b + 1, fillBg)
            return
        }
        val srcStart = t * cols
        val srcEnd = (b - n + 1) * cols
        val dstStart = (t + n) * cols
        active.chars.copyInto(active.chars, dstStart, srcStart, srcEnd)
        active.fg.copyInto(active.fg, dstStart, srcStart, srcEnd)
        active.bg.copyInto(active.bg, dstStart, srcStart, srcEnd)
        active.attrs.copyInto(active.attrs, dstStart, srcStart, srcEnd)
        clearRows(t, t + n, fillBg)
    }

    fun insertChars(row: Int, col: Int, count: Int, fillBg: Int = curBg) {
        if (row !in 0 until rows || col !in 0 until cols) {
            return
        }
        val n = count.coerceIn(1, cols - col)
        val rowOff = row * cols
        val moveCount = cols - col - n
        if (moveCount > 0) {
            val srcStart = rowOff + col
            val dstStart = rowOff + col + n
            active.chars.copyInto(active.chars, dstStart, srcStart, srcStart + moveCount)
            active.fg.copyInto(active.fg, dstStart, srcStart, srcStart + moveCount)
            active.bg.copyInto(active.bg, dstStart, srcStart, srcStart + moveCount)
            active.attrs.copyInto(active.attrs, dstStart, srcStart, srcStart + moveCount)
        }
        clearRange(row, col, col + n, fillBg)
        if (cols > 0 && active.chars[rowOff + cols - 1] == WIDE_CONTINUATION && cols >= 2 &&
            active.chars[rowOff + cols - 2] == WIDE_CONTINUATION
        ) {
            active.chars[rowOff + cols - 1] = ' '.code
        }
    }

    fun deleteChars(row: Int, col: Int, count: Int, fillBg: Int = curBg) {
        if (row !in 0 until rows || col !in 0 until cols) {
            return
        }
        val n = count.coerceIn(1, cols - col)
        val rowOff = row * cols
        val moveCount = cols - col - n
        if (moveCount > 0) {
            val srcStart = rowOff + col + n
            val dstStart = rowOff + col
            active.chars.copyInto(active.chars, dstStart, srcStart, srcStart + moveCount)
            active.fg.copyInto(active.fg, dstStart, srcStart, srcStart + moveCount)
            active.bg.copyInto(active.bg, dstStart, srcStart, srcStart + moveCount)
            active.attrs.copyInto(active.attrs, dstStart, srcStart, srcStart + moveCount)
            if (active.chars[dstStart] == WIDE_CONTINUATION) {
                active.chars[dstStart] = ' '.code
            }
        }
        clearRange(row, cols - n, cols, fillBg)
    }

    fun rowText(row: Int): String {
        if (row !in 0 until rows) {
            return ""
        }
        val sb = StringBuilder(cols)
        val off = row * cols
        for (c in 0 until cols) {
            val cp = active.chars[off + c]
            if (cp == WIDE_CONTINUATION) {
                continue
            }
            if (cp <= 0) {
                sb.append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trimEnd()
    }

    companion object {
        const val WIDE_CONTINUATION = -1

        const val ATTR_BOLD = 1 shl 0
        const val ATTR_DIM = 1 shl 1
        const val ATTR_ITALIC = 1 shl 2
        const val ATTR_UNDERLINE = 1 shl 3
        const val ATTR_REVERSE = 1 shl 4
        const val ATTR_INVISIBLE = 1 shl 5
        const val ATTR_STRIKETHROUGH = 1 shl 6
    }
}

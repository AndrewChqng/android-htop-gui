package com.andrewchqng.htop.terminal

import java.nio.charset.StandardCharsets

class TerminalEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val onResponse: ((ByteArray) -> Unit)? = null,
) {
    val lock = Any()
    val buffer = TerminalBuffer(initialCols, initialRows)

    private enum class State {
        GROUND,
        ESCAPE,
        ESC_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        OSC_STRING,
        OSC_ESCAPE,
        STRING_IGNORE,
        STRING_ESCAPE,
    }

    private var state = State.GROUND
    private var escIntermediate = 0
    private var csiPrivatePrefix = 0
    private var csiIntermediate = 0
    private val csiRaw = StringBuilder(32)
    private var lastPrintedCodePoint = ' '.code

    // Incremental UTF-8 decoder state
    private var utf8CodePoint = 0
    private var utf8Remaining = 0
    private var utf8MinCodePoint = 0

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) {
            buffer.resize(cols, rows)
        }
    }

    fun reset() {
        synchronized(lock) {
            buffer.reset()
            state = State.GROUND
            escIntermediate = 0
            csiPrivatePrefix = 0
            csiIntermediate = 0
            csiRaw.setLength(0)
            utf8Remaining = 0
        }
    }

    fun process(data: ByteArray, length: Int = data.size) {
        synchronized(lock) {
            val len = length.coerceIn(0, data.size)
            for (i in 0 until len) {
                val b = data[i].toInt() and 0xFF
                processByte(b)
            }
        }
    }

    private fun processByte(b: Int) {
        // C0 controls always execute immediately in CSI/ESC unless inside OSC/DCS string
        when (state) {
            State.OSC_STRING -> {
                when (b) {
                    0x07 -> state = State.GROUND
                    0x1B -> state = State.OSC_ESCAPE
                    0x9C -> state = State.GROUND
                }
                return
            }

            State.OSC_ESCAPE -> {
                if (b == '\\'.code || b == 0x07) {
                    state = State.GROUND
                } else {
                    state = State.ESCAPE
                    processEscapeByte(b)
                }
                return
            }

            State.STRING_IGNORE -> {
                when (b) {
                    0x1B -> state = State.STRING_ESCAPE
                    0x9C -> state = State.GROUND
                }
                return
            }

            State.STRING_ESCAPE -> {
                if (b == '\\'.code) {
                    state = State.GROUND
                } else {
                    state = State.STRING_IGNORE
                }
                return
            }

            else -> Unit
        }

        // Complete or abort incomplete UTF-8 if a control/ASCII byte interrupts it
        if (utf8Remaining > 0) {
            if (b in 0x80..0xBF) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) {
                    val cp = if (utf8CodePoint >= utf8MinCodePoint &&
                        utf8CodePoint <= 0x10FFFF &&
                        utf8CodePoint !in 0xD800..0xDFFF
                    ) {
                        utf8CodePoint
                    } else {
                        0xFFFD
                    }
                    if (state == State.GROUND) {
                        printCodePoint(cp)
                    }
                }
                return
            } else {
                utf8Remaining = 0
            }
        }

        // Execute C0 control characters anywhere in GROUND / ESC / CSI
        if (b < 0x20 || b == 0x7F) {
            when (b) {
                0x07 -> Unit // BEL
                0x08 -> backspace()
                0x09 -> horizontalTab()
                0x0A, 0x0B, 0x0C -> lineFeed()
                0x0D -> carriageReturn()
                0x0E -> buffer.useG1 = true // SO
                0x0F -> buffer.useG1 = false // SI
                0x18, 0x1A -> state = State.GROUND // CAN / SUB
                0x1B -> {
                    state = State.ESCAPE
                    escIntermediate = 0
                }
            }
            return
        }

        when (state) {
            State.GROUND -> {
                when {
                    b < 0x80 -> printCodePoint(b)
                    b in 0xC2..0xDF -> {
                        utf8CodePoint = b and 0x1F
                        utf8Remaining = 1
                        utf8MinCodePoint = 0x80
                    }
                    b in 0xE0..0xEF -> {
                        utf8CodePoint = b and 0x0F
                        utf8Remaining = 2
                        utf8MinCodePoint = 0x800
                    }
                    b in 0xF0..0xF4 -> {
                        utf8CodePoint = b and 0x07
                        utf8Remaining = 3
                        utf8MinCodePoint = 0x10000
                    }
                    else -> printCodePoint(0xFFFD)
                }
            }

            State.ESCAPE -> processEscapeByte(b)
            State.ESC_INTERMEDIATE -> processEscIntermediate(b)
            State.CSI_ENTRY, State.CSI_PARAM -> processCsiByte(b)
            else -> state = State.GROUND
        }
    }

    private fun processEscapeByte(b: Int) {
        when (b) {
            '['.code -> {
                state = State.CSI_ENTRY
                csiPrivatePrefix = 0
                csiIntermediate = 0
                csiRaw.setLength(0)
            }
            ']'.code -> state = State.OSC_STRING
            'P'.code, '^'.code, '_'.code, 'X'.code -> state = State.STRING_IGNORE // DCS/PM/APC/SOS
            '('.code, ')'.code, '*'.code, '+'.code, '#'.code, '%'.code -> {
                escIntermediate = b
                state = State.ESC_INTERMEDIATE
            }
            '7'.code -> {
                buffer.saveCursor()
                state = State.GROUND
            }
            '8'.code -> {
                buffer.restoreCursor()
                state = State.GROUND
            }
            '='.code -> {
                buffer.applicationKeypad = true
                state = State.GROUND
            }
            '>'.code -> {
                buffer.applicationKeypad = false
                state = State.GROUND
            }
            'D'.code -> { // IND
                indexDown()
                state = State.GROUND
            }
            'E'.code -> { // NEL
                carriageReturn()
                indexDown()
                state = State.GROUND
            }
            'M'.code -> { // RI (Reverse Index)
                reverseIndex()
                state = State.GROUND
            }
            'c'.code -> { // RIS
                buffer.reset()
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun processEscIntermediate(b: Int) {
        when (escIntermediate) {
            '('.code -> {
                buffer.g0DecGraphics = (b == '0'.code)
                state = State.GROUND
            }
            ')'.code -> {
                buffer.g1DecGraphics = (b == '0'.code)
                state = State.GROUND
            }
            '#'.code -> {
                if (b == '8'.code) {
                    // DECALN screen alignment test
                    for (r in 0 until buffer.rows) {
                        for (c in 0 until buffer.cols) {
                            buffer.setCell(r, c, 'E'.code, 0, 0, 0)
                        }
                    }
                }
                state = State.GROUND
            }
            else -> {
                if (b in 0x20..0x2F) {
                    // keep consuming intermediate bytes
                } else {
                    state = State.GROUND
                }
            }
        }
    }

    private fun processCsiByte(b: Int) {
        when {
            state == State.CSI_ENTRY && (b == '?'.code || b == '>'.code || b == '='.code || b == '<'.code) -> {
                csiPrivatePrefix = b
                state = State.CSI_PARAM
            }
            b in '0'.code..'9'.code || b == ';'.code || b == ':'.code -> {
                csiRaw.append(b.toChar())
                state = State.CSI_PARAM
            }
            b in 0x20..0x2F -> {
                csiIntermediate = b
                state = State.CSI_PARAM
            }
            b in 0x40..0x7E -> {
                dispatchCsi(b)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun parseParams(): IntArray {
        if (csiRaw.isEmpty()) {
            return IntArray(0)
        }
        val raw = csiRaw.toString()
        val parts = raw.split(';')
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            out[i] = if (p.isEmpty()) -1 else p.substringBefore(':').toIntOrNull() ?: -1
        }
        return out
    }

    private fun paramAt(params: IntArray, idx: Int, defaultVal: Int): Int {
        if (idx >= params.size) return defaultVal
        val v = params[idx]
        return if (v <= 0) defaultVal else v
    }

    private fun paramRawAt(params: IntArray, idx: Int, defaultVal: Int): Int {
        if (idx >= params.size) return defaultVal
        val v = params[idx]
        return if (v < 0) defaultVal else v
    }

    private fun dispatchCsi(finalByte: Int) {
        val params = parseParams()
        if (csiPrivatePrefix == '?'.code) {
            dispatchPrivateCsi(finalByte, params)
            return
        }
        if (csiPrivatePrefix == '>'.code) {
            if (finalByte == 'c'.code) {
                sendResponse("\u001B[>0;10;0c")
            }
            return
        }
        if (csiPrivatePrefix != 0) {
            return
        }

        when (finalByte) {
            'A'.code -> { // CUU
                val n = paramAt(params, 0, 1)
                val minRow = if (buffer.originMode) buffer.scrollTop else 0
                buffer.cursorRow = (buffer.cursorRow - n).coerceAtLeast(minRow)
                buffer.pendingWrap = false
            }
            'B'.code, 'e'.code -> { // CUD / VPR
                val n = paramAt(params, 0, 1)
                val maxRow = if (buffer.originMode) buffer.scrollBottom else buffer.rows - 1
                buffer.cursorRow = (buffer.cursorRow + n).coerceAtMost(maxRow)
                buffer.pendingWrap = false
            }
            'C'.code, 'a'.code -> { // CUF / HPR
                val n = paramAt(params, 0, 1)
                buffer.cursorCol = (buffer.cursorCol + n).coerceAtMost(buffer.cols - 1)
                buffer.pendingWrap = false
            }
            'D'.code -> { // CUB
                val n = paramAt(params, 0, 1)
                buffer.cursorCol = (buffer.cursorCol - n).coerceAtLeast(0)
                buffer.pendingWrap = false
            }
            'E'.code -> { // CNL
                val n = paramAt(params, 0, 1)
                val maxRow = if (buffer.originMode) buffer.scrollBottom else buffer.rows - 1
                buffer.cursorRow = (buffer.cursorRow + n).coerceAtMost(maxRow)
                buffer.cursorCol = 0
                buffer.pendingWrap = false
            }
            'F'.code -> { // CPL
                val n = paramAt(params, 0, 1)
                val minRow = if (buffer.originMode) buffer.scrollTop else 0
                buffer.cursorRow = (buffer.cursorRow - n).coerceAtLeast(minRow)
                buffer.cursorCol = 0
                buffer.pendingWrap = false
            }
            'G'.code, '`'.code -> { // CHA / HPA
                val col = paramAt(params, 0, 1) - 1
                buffer.cursorCol = col.coerceIn(0, buffer.cols - 1)
                buffer.pendingWrap = false
            }
            'H'.code, 'f'.code -> { // CUP / HVP
                val reqRow = paramAt(params, 0, 1) - 1
                val reqCol = paramAt(params, 1, 1) - 1
                val targetRow = if (buffer.originMode) {
                    (buffer.scrollTop + reqRow).coerceIn(buffer.scrollTop, buffer.scrollBottom)
                } else {
                    reqRow.coerceIn(0, buffer.rows - 1)
                }
                buffer.cursorRow = targetRow
                buffer.cursorCol = reqCol.coerceIn(0, buffer.cols - 1)
                buffer.pendingWrap = false
            }
            'd'.code -> { // VPA
                val reqRow = paramAt(params, 0, 1) - 1
                buffer.cursorRow = if (buffer.originMode) {
                    (buffer.scrollTop + reqRow).coerceIn(buffer.scrollTop, buffer.scrollBottom)
                } else {
                    reqRow.coerceIn(0, buffer.rows - 1)
                }
                buffer.pendingWrap = false
            }
            'J'.code -> { // ED
                when (paramRawAt(params, 0, 0)) {
                    0 -> {
                        buffer.clearRange(buffer.cursorRow, buffer.cursorCol, buffer.cols)
                        buffer.clearRows(buffer.cursorRow + 1, buffer.rows)
                    }
                    1 -> {
                        buffer.clearRows(0, buffer.cursorRow)
                        buffer.clearRange(buffer.cursorRow, 0, buffer.cursorCol + 1)
                    }
                    2, 3 -> buffer.clearRows(0, buffer.rows)
                }
            }
            'K'.code -> { // EL
                when (paramRawAt(params, 0, 0)) {
                    0 -> buffer.clearRange(buffer.cursorRow, buffer.cursorCol, buffer.cols)
                    1 -> buffer.clearRange(buffer.cursorRow, 0, buffer.cursorCol + 1)
                    2 -> buffer.clearRange(buffer.cursorRow, 0, buffer.cols)
                }
            }
            'X'.code -> { // ECH
                val n = paramAt(params, 0, 1)
                val endCol = (buffer.cursorCol + n).coerceAtMost(buffer.cols)
                buffer.clearRange(buffer.cursorRow, buffer.cursorCol, endCol)
            }
            '@'.code -> { // ICH
                val n = paramAt(params, 0, 1)
                buffer.insertChars(buffer.cursorRow, buffer.cursorCol, n)
            }
            'P'.code -> { // DCH
                val n = paramAt(params, 0, 1)
                buffer.deleteChars(buffer.cursorRow, buffer.cursorCol, n)
            }
            'L'.code -> { // IL
                if (buffer.cursorRow in buffer.scrollTop..buffer.scrollBottom) {
                    val n = paramAt(params, 0, 1)
                    buffer.scrollDown(buffer.cursorRow, buffer.scrollBottom, n)
                    buffer.cursorCol = 0
                    buffer.pendingWrap = false
                }
            }
            'M'.code -> { // DL
                if (buffer.cursorRow in buffer.scrollTop..buffer.scrollBottom) {
                    val n = paramAt(params, 0, 1)
                    buffer.scrollUp(buffer.cursorRow, buffer.scrollBottom, n)
                    buffer.cursorCol = 0
                    buffer.pendingWrap = false
                }
            }
            'S'.code -> { // SU
                val n = paramAt(params, 0, 1)
                buffer.scrollUp(buffer.scrollTop, buffer.scrollBottom, n)
            }
            'T'.code -> { // SD
                val n = paramAt(params, 0, 1)
                buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom, n)
            }
            'b'.code -> { // REP
                val n = paramAt(params, 0, 1).coerceAtMost(buffer.cols * 2)
                for (i in 0 until n) {
                    printCodePoint(lastPrintedCodePoint)
                }
            }
            'r'.code -> { // DECSTBM
                val top = (paramAt(params, 0, 1) - 1).coerceIn(0, buffer.rows - 1)
                val bottom = (paramAt(params, 1, buffer.rows) - 1).coerceIn(0, buffer.rows - 1)
                if (top < bottom) {
                    buffer.scrollTop = top
                    buffer.scrollBottom = bottom
                } else {
                    buffer.scrollTop = 0
                    buffer.scrollBottom = buffer.rows - 1
                }
                buffer.cursorRow = if (buffer.originMode) buffer.scrollTop else 0
                buffer.cursorCol = 0
                buffer.pendingWrap = false
            }
            's'.code -> {
                if (params.isEmpty()) {
                    buffer.saveCursor()
                }
            }
            'u'.code -> {
                if (csiIntermediate == 0) {
                    buffer.restoreCursor()
                }
            }
            'm'.code -> applySgr()
            'h'.code -> {
                for (p in params) {
                    when (p) {
                        4 -> buffer.insertMode = true
                        20 -> buffer.newLineMode = true
                    }
                }
            }
            'l'.code -> {
                for (p in params) {
                    when (p) {
                        4 -> buffer.insertMode = false
                        20 -> buffer.newLineMode = false
                    }
                }
            }
            'n'.code -> { // DSR
                when (paramRawAt(params, 0, 0)) {
                    5 -> sendResponse("\u001B[0n")
                    6 -> sendResponse("\u001B[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R")
                }
            }
            'c'.code -> { // DA1
                if (paramRawAt(params, 0, 0) == 0) {
                    sendResponse("\u001B[?1;2c")
                }
            }
            't'.code -> Unit // Window manipulation / title stack (22;0;0t / 23;0;0t)
        }
    }

    private fun dispatchPrivateCsi(finalByte: Int, params: IntArray) {
        if (finalByte != 'h'.code && finalByte != 'l'.code) {
            return
        }
        val enable = (finalByte == 'h'.code)
        for (p in params) {
            when (p) {
                1 -> buffer.applicationCursorKeys = enable
                6 -> {
                    buffer.originMode = enable
                    buffer.cursorRow = if (enable) buffer.scrollTop else 0
                    buffer.cursorCol = 0
                    buffer.pendingWrap = false
                }
                7 -> buffer.autoWrap = enable
                25 -> buffer.cursorVisible = enable
                47 -> buffer.useAlternateScreen(enable, clearOnSwitch = enable, saveRestoreCursor = false)
                1000 -> buffer.mouseTrackingMode = if (enable) 1000 else 0
                1002 -> buffer.mouseTrackingMode = if (enable) 1002 else 0
                1003 -> buffer.mouseTrackingMode = if (enable) 1003 else 0
                1006 -> buffer.sgrMouseMode = enable
                1047 -> buffer.useAlternateScreen(enable, clearOnSwitch = enable, saveRestoreCursor = false)
                1048 -> if (enable) buffer.saveCursor() else buffer.restoreCursor()
                1049 -> buffer.useAlternateScreen(enable, clearOnSwitch = true, saveRestoreCursor = true)
                2004 -> buffer.bracketedPasteMode = enable
            }
        }
    }

    private fun applySgr() {
        val raw = csiRaw.toString()
        if (raw.isEmpty()) {
            resetSgr()
            return
        }
        val tokens = raw.split(';')
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.contains(':')) {
                val sub = tok.split(':').map { it.toIntOrNull() ?: -1 }
                applyExtendedColorSubparams(sub)
                i++
                continue
            }
            val code = if (tok.isEmpty()) 0 else (tok.toIntOrNull() ?: -1)
            when (code) {
                0 -> resetSgr()
                1 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_BOLD
                2 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_DIM
                3 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_ITALIC
                4 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_UNDERLINE
                7 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_REVERSE
                8 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_INVISIBLE
                9 -> buffer.curAttrs = buffer.curAttrs or TerminalBuffer.ATTR_STRIKETHROUGH
                21, 22 -> buffer.curAttrs = buffer.curAttrs and (TerminalBuffer.ATTR_BOLD or TerminalBuffer.ATTR_DIM).inv()
                23 -> buffer.curAttrs = buffer.curAttrs and TerminalBuffer.ATTR_ITALIC.inv()
                24 -> buffer.curAttrs = buffer.curAttrs and TerminalBuffer.ATTR_UNDERLINE.inv()
                27 -> buffer.curAttrs = buffer.curAttrs and TerminalBuffer.ATTR_REVERSE.inv()
                28 -> buffer.curAttrs = buffer.curAttrs and TerminalBuffer.ATTR_INVISIBLE.inv()
                29 -> buffer.curAttrs = buffer.curAttrs and TerminalBuffer.ATTR_STRIKETHROUGH.inv()
                in 30..37 -> buffer.curFg = TerminalColors.ansi16(code - 30)
                38 -> {
                    val consumed = parseExtendedColorSemicolon(tokens, i, isFg = true)
                    i += consumed
                }
                39 -> buffer.curFg = 0
                in 40..47 -> buffer.curBg = TerminalColors.ansi16(code - 40)
                48 -> {
                    val consumed = parseExtendedColorSemicolon(tokens, i, isFg = false)
                    i += consumed
                }
                49 -> buffer.curBg = 0
                in 90..97 -> buffer.curFg = TerminalColors.ansi16(8 + (code - 90))
                in 100..107 -> buffer.curBg = TerminalColors.ansi16(8 + (code - 100))
            }
            i++
        }
    }

    private fun parseExtendedColorSemicolon(tokens: List<String>, idx: Int, isFg: Boolean): Int {
        if (idx + 1 >= tokens.size) return 0
        val mode = tokens[idx + 1].toIntOrNull() ?: return 0
        if (mode == 5 && idx + 2 < tokens.size) {
            val cIdx = tokens[idx + 2].toIntOrNull() ?: 0
            val color = TerminalColors.indexed256(cIdx)
            if (isFg) buffer.curFg = color else buffer.curBg = color
            return 2
        }
        if (mode == 2 && idx + 4 < tokens.size) {
            val r = tokens[idx + 2].toIntOrNull() ?: 0
            val g = tokens[idx + 3].toIntOrNull() ?: 0
            val b = tokens[idx + 4].toIntOrNull() ?: 0
            val color = TerminalColors.rgb(r, g, b)
            if (isFg) buffer.curFg = color else buffer.curBg = color
            return 4
        }
        return 0
    }

    private fun applyExtendedColorSubparams(sub: List<Int>) {
        if (sub.size < 2) return
        val attr = sub[0]
        if (attr != 38 && attr != 48) return
        val isFg = (attr == 38)
        val mode = sub[1]
        if (mode == 5 && sub.size >= 3) {
            val color = TerminalColors.indexed256(sub[2].coerceAtLeast(0))
            if (isFg) buffer.curFg = color else buffer.curBg = color
        } else if (mode == 2) {
            val offset = if (sub.size >= 6) 3 else 2
            if (sub.size >= offset + 3) {
                val color = TerminalColors.rgb(
                    sub[offset].coerceAtLeast(0),
                    sub[offset + 1].coerceAtLeast(0),
                    sub[offset + 2].coerceAtLeast(0),
                )
                if (isFg) buffer.curFg = color else buffer.curBg = color
            }
        }
    }

    private fun resetSgr() {
        buffer.curFg = 0
        buffer.curBg = 0
        buffer.curAttrs = 0
    }

    private fun printCodePoint(rawCodePoint: Int) {
        val decGraphics = if (buffer.useG1) buffer.g1DecGraphics else buffer.g0DecGraphics
        val cp = if (decGraphics && rawCodePoint in 0x5F..0x7E) {
            mapDecGraphics(rawCodePoint)
        } else {
            rawCodePoint
        }

        val width = charWidth(cp)
        if (width == 0) {
            return
        }

        lastPrintedCodePoint = cp

        if (buffer.pendingWrap) {
            if (buffer.autoWrap) {
                carriageReturn()
                indexDown()
            } else {
                buffer.pendingWrap = false
            }
        }

        if (width == 2 && buffer.cols >= 2) {
            if (buffer.cursorCol == buffer.cols - 1) {
                if (buffer.autoWrap) {
                    buffer.setCell(buffer.cursorRow, buffer.cursorCol, ' '.code, buffer.curFg, buffer.curBg, 0)
                    carriageReturn()
                    indexDown()
                } else {
                    return
                }
            }
            if (buffer.insertMode) {
                buffer.insertChars(buffer.cursorRow, buffer.cursorCol, 2)
            }
            buffer.setWideCell(buffer.cursorRow, buffer.cursorCol, cp, buffer.curFg, buffer.curBg, buffer.curAttrs)
            if (buffer.cursorCol + 2 >= buffer.cols) {
                buffer.cursorCol = buffer.cols - 1
                buffer.pendingWrap = buffer.autoWrap
            } else {
                buffer.cursorCol += 2
            }
            return
        }

        if (buffer.insertMode) {
            buffer.insertChars(buffer.cursorRow, buffer.cursorCol, 1)
        }
        buffer.setCell(buffer.cursorRow, buffer.cursorCol, cp, buffer.curFg, buffer.curBg, buffer.curAttrs)
        if (buffer.cursorCol >= buffer.cols - 1) {
            buffer.cursorCol = buffer.cols - 1
            buffer.pendingWrap = buffer.autoWrap
        } else {
            buffer.cursorCol++
        }
    }

    private fun backspace() {
        if (buffer.pendingWrap) {
            buffer.pendingWrap = false
            return
        }
        if (buffer.cursorCol > 0) {
            buffer.cursorCol--
        }
    }

    private fun horizontalTab() {
        buffer.pendingWrap = false
        val next = ((buffer.cursorCol / 8) + 1) * 8
        buffer.cursorCol = next.coerceAtMost(buffer.cols - 1)
    }

    private fun lineFeed() {
        if (buffer.newLineMode) {
            carriageReturn()
        }
        indexDown()
    }

    private fun carriageReturn() {
        buffer.cursorCol = 0
        buffer.pendingWrap = false
    }

    private fun indexDown() {
        buffer.pendingWrap = false
        if (buffer.cursorRow == buffer.scrollBottom) {
            buffer.scrollUp(buffer.scrollTop, buffer.scrollBottom, 1)
        } else if (buffer.cursorRow < buffer.rows - 1) {
            buffer.cursorRow++
        }
    }

    private fun reverseIndex() {
        buffer.pendingWrap = false
        if (buffer.cursorRow == buffer.scrollTop) {
            buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom, 1)
        } else if (buffer.cursorRow > 0) {
            buffer.cursorRow--
        }
    }

    private fun sendResponse(text: String) {
        onResponse?.invoke(text.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun mapDecGraphics(ch: Int): Int = when (ch) {
        0x5F -> 0x00A0 // Blank
        0x60 -> 0x25C6 // Diamond ◆
        0x61 -> 0x2592 // Checkerboard ▒
        0x66 -> 0x00B0 // Degree °
        0x67 -> 0x00B1 // Plus/minus ±
        0x6A -> 0x2518 // Bottom-right ┘
        0x6B -> 0x2510 // Top-right ┐
        0x6C -> 0x250C // Top-left ┌
        0x6D -> 0x2514 // Bottom-left └
        0x6E -> 0x253C // Crossing ┼
        0x6F -> 0x23BA // Scan line 1
        0x70 -> 0x23BB // Scan line 3
        0x71 -> 0x2500 // Horizontal line ─
        0x72 -> 0x23BC // Scan line 7
        0x73 -> 0x23BD // Scan line 9
        0x74 -> 0x251C // Left tee ├
        0x75 -> 0x2524 // Right tee ┤
        0x76 -> 0x2534 // Bottom tee ┴
        0x77 -> 0x252C // Top tee ┬
        0x78 -> 0x2502 // Vertical line │
        0x79 -> 0x2264 // ≤
        0x7A -> 0x2265 // ≥
        0x7B -> 0x03C0 // π
        0x7C -> 0x2260 // ≠
        0x7D -> 0x00A3 // £
        0x7E -> 0x00B7 // ·
        else -> ch
    }

    private fun charWidth(cp: Int): Int {
        if (cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F) return 0
        if (cp < 0x300) return 1
        if (cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F || cp == 0xFEFF) return 0
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return 0
        }
        return if (isWideCodePoint(cp)) 2 else 1
    }

    private fun isWideCodePoint(cp: Int): Boolean {
        return cp >= 0x1100 && (
            cp <= 0x115F ||
                cp == 0x2329 || cp == 0x232A ||
                cp in 0x2E80..0x303E ||
                cp in 0x3040..0x3247 ||
                cp in 0x3250..0x4DBF ||
                cp in 0x4E00..0xA4C6 ||
                cp in 0xA960..0xA97C ||
                cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF ||
                cp in 0xFE10..0xFE19 ||
                cp in 0xFE30..0xFE6B ||
                cp in 0xFF01..0xFF60 ||
                cp in 0xFFE0..0xFFE6 ||
                cp in 0x1F300..0x1F64F ||
                cp in 0x1F900..0x1F9FF ||
                cp in 0x20000..0x2FFFD ||
                cp in 0x30000..0x3FFFD
            )
    }
}

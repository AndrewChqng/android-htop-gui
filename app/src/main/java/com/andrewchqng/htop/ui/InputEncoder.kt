package com.andrewchqng.htop.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.nio.charset.StandardCharsets

object InputEncoder {

    const val MOUSE_LEFT = 0
    const val MOUSE_MIDDLE = 1
    const val MOUSE_RIGHT = 2
    const val MOUSE_WHEEL_UP = 64
    const val MOUSE_WHEEL_DOWN = 65

    fun encodeMouse(
        button: Int,
        col: Int,
        row: Int,
        pressed: Boolean,
        sgrMode: Boolean,
        mouseTrackingMode: Int,
        modifiers: Int = 0,
    ): ByteArray? {
        if (mouseTrackingMode == 0) {
            return null
        }
        val c = col.coerceAtLeast(1)
        val r = row.coerceAtLeast(1)
        val cb = (button and 0xFF) or modifiers

        if (sgrMode) {
            val suffix = if (pressed || button >= 64) 'M' else 'm'
            return "\u001B[<$cb;$c;$r$suffix".toByteArray(StandardCharsets.US_ASCII)
        }

        if (c > 223 || r > 223) {
            return null
        }
        val x10Cb = if (!pressed && button < 64) (3 or modifiers) else cb
        return byteArrayOf(
            0x1B,
            '['.code.toByte(),
            'M'.code.toByte(),
            (32 + x10Cb).toByte(),
            (32 + c).toByte(),
            (32 + r).toByte(),
        )
    }

    fun encodeText(text: CharSequence, ctrl: Boolean, alt: Boolean): ByteArray {
        if (text.isEmpty()) {
            return ByteArray(0)
        }
        if (ctrl && text.length == 1) {
            val ctrlByte = mapCharToControlByte(text[0].code)
            if (ctrlByte != null) {
                return if (alt) byteArrayOf(0x1B, ctrlByte.toByte()) else byteArrayOf(ctrlByte.toByte())
            }
        }
        val utf8 = text.toString().toByteArray(StandardCharsets.UTF_8)
        return if (alt) byteArrayOf(0x1B) + utf8 else utf8
    }

    fun encodeKeyEvent(
        keyCode: Int,
        unicodeChar: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        appCursorKeys: Boolean,
    ): ByteArray? {
        val mod = 1 +
            (if (shift) 1 else 0) +
            (if (alt) 2 else 0) +
            (if (ctrl) 4 else 0)

        val special = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> encodeCursorKey('A', mod, appCursorKeys)
            KeyEvent.KEYCODE_DPAD_DOWN -> encodeCursorKey('B', mod, appCursorKeys)
            KeyEvent.KEYCODE_DPAD_RIGHT -> encodeCursorKey('C', mod, appCursorKeys)
            KeyEvent.KEYCODE_DPAD_LEFT -> encodeCursorKey('D', mod, appCursorKeys)
            KeyEvent.KEYCODE_MOVE_HOME -> encodeCursorKey('H', mod, appCursorKeys)
            KeyEvent.KEYCODE_MOVE_END -> encodeCursorKey('F', mod, appCursorKeys)

            KeyEvent.KEYCODE_INSERT -> encodeTildeKey(2, mod)
            KeyEvent.KEYCODE_FORWARD_DEL -> encodeTildeKey(3, mod)
            KeyEvent.KEYCODE_PAGE_UP -> encodeTildeKey(5, mod)
            KeyEvent.KEYCODE_PAGE_DOWN -> encodeTildeKey(6, mod)

            KeyEvent.KEYCODE_F1 -> encodeSs3OrCsiKey('P', mod)
            KeyEvent.KEYCODE_F2 -> encodeSs3OrCsiKey('Q', mod)
            KeyEvent.KEYCODE_F3 -> encodeSs3OrCsiKey('R', mod)
            KeyEvent.KEYCODE_F4 -> encodeSs3OrCsiKey('S', mod)
            KeyEvent.KEYCODE_F5 -> encodeTildeKey(15, mod)
            KeyEvent.KEYCODE_F6 -> encodeTildeKey(17, mod)
            KeyEvent.KEYCODE_F7 -> encodeTildeKey(18, mod)
            KeyEvent.KEYCODE_F8 -> encodeTildeKey(19, mod)
            KeyEvent.KEYCODE_F9 -> encodeTildeKey(20, mod)
            KeyEvent.KEYCODE_F10 -> encodeTildeKey(21, mod)
            KeyEvent.KEYCODE_F11 -> encodeTildeKey(23, mod)
            KeyEvent.KEYCODE_F12 -> encodeTildeKey(24, mod)

            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (alt) "\u001B\r" else "\r"
            KeyEvent.KEYCODE_TAB -> when {
                shift -> "\u001B[Z"
                alt -> "\u001B\t"
                else -> "\t"
            }
            KeyEvent.KEYCODE_DEL -> return if (alt) byteArrayOf(0x1B, 0x7F) else byteArrayOf(0x7F)
            KeyEvent.KEYCODE_SPACE -> when {
                ctrl -> return if (alt) byteArrayOf(0x1B, 0x00) else byteArrayOf(0x00)
                alt -> "\u001B "
                else -> " "
            }
            else -> null
        }

        if (special != null) {
            return special.toByteArray(StandardCharsets.US_ASCII)
        }

        if (ctrl) {
            val resolvedCode = if (unicodeChar > 0) unicodeChar else fallbackKeyCodeChar(keyCode)
            val ctrlByte = resolvedCode?.let { mapCharToControlByte(it) }
            if (ctrlByte != null) {
                return if (alt) byteArrayOf(0x1B, ctrlByte.toByte()) else byteArrayOf(ctrlByte.toByte())
            }
        }

        if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0) {
            val cleanChar = unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
            if (cleanChar > 0) {
                val str = String(Character.toChars(cleanChar))
                val utf8 = str.toByteArray(StandardCharsets.UTF_8)
                return if (alt) byteArrayOf(0x1B) + utf8 else utf8
            }
        }

        return null
    }

    private fun encodeCursorKey(finalChar: Char, mod: Int, appCursorKeys: Boolean): String = when {
        mod > 1 -> "\u001B[1;$mod$finalChar"
        appCursorKeys -> "\u001BO$finalChar"
        else -> "\u001B[$finalChar"
    }

    private fun encodeSs3OrCsiKey(finalChar: Char, mod: Int): String =
        if (mod > 1) "\u001B[1;$mod$finalChar" else "\u001BO$finalChar"

    private fun encodeTildeKey(code: Int, mod: Int): String =
        if (mod > 1) "\u001B[$code;$mod~" else "\u001B[$code~"

    private fun mapCharToControlByte(ch: Int): Int? = when {
        ch == ' '.code || ch == '@'.code || ch == '2'.code -> 0x00
        ch in 'a'.code..'z'.code -> ch - 'a'.code + 1
        ch in 'A'.code..'Z'.code -> ch - 'A'.code + 1
        ch == '['.code || ch == '3'.code -> 0x1B
        ch == '\\'.code || ch == '4'.code -> 0x1C
        ch == ']'.code || ch == '5'.code -> 0x1D
        ch == '^'.code || ch == '6'.code -> 0x1E
        ch == '_'.code || ch == '-'.code || ch == '/'.code || ch == '7'.code -> 0x1F
        ch == '?'.code || ch == '8'.code -> 0x7F
        else -> null
    }

    private fun fallbackKeyCodeChar(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
        KeyEvent.KEYCODE_MINUS -> '-'.code
        KeyEvent.KEYCODE_SLASH -> '/'.code
        KeyEvent.KEYCODE_AT -> '@'.code
        else -> null
    }
}

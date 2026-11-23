package com.andrewchqng.htop.ui

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class InputEncoderTest {

    private fun ascii(bytes: ByteArray?): String? =
        bytes?.let { String(it, StandardCharsets.US_ASCII) }

    @Test
    fun testSgrMouseEncoding() {
        assertEquals(
            "\u001B[<0;12;34M",
            ascii(InputEncoder.encodeMouse(InputEncoder.MOUSE_LEFT, 12, 34, pressed = true, sgrMode = true, mouseTrackingMode = 1000)),
        )
        assertEquals(
            "\u001B[<0;12;34m",
            ascii(InputEncoder.encodeMouse(InputEncoder.MOUSE_LEFT, 12, 34, pressed = false, sgrMode = true, mouseTrackingMode = 1000)),
        )
        assertEquals(
            "\u001B[<64;5;10M",
            ascii(InputEncoder.encodeMouse(InputEncoder.MOUSE_WHEEL_UP, 5, 10, pressed = true, sgrMode = true, mouseTrackingMode = 1000)),
        )
        assertEquals(
            "\u001B[<65;5;10M",
            ascii(InputEncoder.encodeMouse(InputEncoder.MOUSE_WHEEL_DOWN, 5, 10, pressed = false, sgrMode = true, mouseTrackingMode = 1000)),
        )
        assertNull(
            InputEncoder.encodeMouse(InputEncoder.MOUSE_LEFT, 5, 10, pressed = true, sgrMode = true, mouseTrackingMode = 0),
        )
    }

    @Test
    fun testX10MouseFallback() {
        val press = InputEncoder.encodeMouse(
            button = InputEncoder.MOUSE_LEFT,
            col = 10,
            row = 20,
            pressed = true,
            sgrMode = false,
            mouseTrackingMode = 1000,
        )
        assertArrayEquals(
            byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), 32, 42, 52),
            press,
        )
        val release = InputEncoder.encodeMouse(
            button = InputEncoder.MOUSE_LEFT,
            col = 10,
            row = 20,
            pressed = false,
            sgrMode = false,
            mouseTrackingMode = 1000,
        )
        assertArrayEquals(
            byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), 35, 42, 52),
            release,
        )
    }

    @Test
    fun testFunctionAndArrowKeys() {
        assertEquals(
            "\u001BOP",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_F1, 0, ctrl = false, alt = false, shift = false, appCursorKeys = false)),
        )
        assertEquals(
            "\u001B[15~",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_F5, 0, ctrl = false, alt = false, shift = false, appCursorKeys = false)),
        )
        assertEquals(
            "\u001B[21~",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_F10, 0, ctrl = false, alt = false, shift = false, appCursorKeys = false)),
        )
        assertEquals(
            "\u001B[A",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_DPAD_UP, 0, ctrl = false, alt = false, shift = false, appCursorKeys = false)),
        )
        assertEquals(
            "\u001BOA",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_DPAD_UP, 0, ctrl = false, alt = false, shift = false, appCursorKeys = true)),
        )
        assertEquals(
            "\u001B[1;5C",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0, ctrl = true, alt = false, shift = false, appCursorKeys = true)),
        )
        assertEquals(
            "\u001B[Z",
            ascii(InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_TAB, 0, ctrl = false, alt = false, shift = true, appCursorKeys = false)),
        )
    }

    @Test
    fun testControlAndAltAndTextEncoding() {
        assertArrayEquals(
            byteArrayOf(0x03),
            InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_C, 0, ctrl = true, alt = false, shift = false, appCursorKeys = false),
        )
        assertArrayEquals(
            byteArrayOf('a'.code.toByte()),
            InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_A, 'a'.code, ctrl = false, alt = false, shift = false, appCursorKeys = false),
        )
        assertArrayEquals(
            byteArrayOf(0x1B, 'x'.code.toByte()),
            InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_X, 'x'.code, ctrl = false, alt = true, shift = false, appCursorKeys = false),
        )
        assertArrayEquals(
            byteArrayOf(0x0C),
            InputEncoder.encodeText("l", ctrl = true, alt = false),
        )
        assertArrayEquals(
            byteArrayOf(0x7F),
            InputEncoder.encodeKeyEvent(KeyEvent.KEYCODE_DEL, 0, ctrl = false, alt = false, shift = false, appCursorKeys = false),
        )
    }
}

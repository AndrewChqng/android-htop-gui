package com.andrewchqng.htop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TerminalEmulatorTest {

    private fun feed(emu: TerminalEmulator, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        emu.process(bytes, bytes.size)
    }

    @Test
    fun testPendingWrapSemantics() {
        val emu = TerminalEmulator(initialCols = 5, initialRows = 3)
        feed(emu, "ABCDE")
        assertEquals(0, emu.buffer.cursorRow)
        assertEquals(4, emu.buffer.cursorCol)
        assertTrue(emu.buffer.pendingWrap)
        assertEquals("ABCDE", emu.buffer.rowText(0))

        feed(emu, "F")
        assertEquals(1, emu.buffer.cursorRow)
        assertEquals(1, emu.buffer.cursorCol)
        assertFalse(emu.buffer.pendingWrap)
        assertEquals("F", emu.buffer.rowText(1))
    }

    @Test
    fun testMultiParamPrivateModesAndHtopSequences() {
        val emu = TerminalEmulator(initialCols = 80, initialRows = 24)
        feed(
            emu,
            "\u001B[?1049h\u001B[22;0;0t\u001B[1;24r\u001B(B\u001B[m\u001B[4l\u001B[?7h" +
                "\u001B[H\u001B[2J\u001B[?1h\u001B=\u001B[?25l\u001B[?1006;1000h" +
                "\u001B[2d  \u001B[36m  0\u001B[39m\u001B[0;1m[\u001B[0m\u001B[32m|||||\u001B[90m  46.2%\u001B[39m]",
        )

        assertTrue(emu.buffer.isAltScreen)
        assertTrue(emu.buffer.applicationCursorKeys)
        assertTrue(emu.buffer.applicationKeypad)
        assertFalse(emu.buffer.cursorVisible)
        assertTrue(emu.buffer.sgrMouseMode)
        assertEquals(1000, emu.buffer.mouseTrackingMode)
        assertEquals("    0[|||||  46.2%]", emu.buffer.rowText(1))

        feed(emu, "\u001B[?1006;1000l\u001B[?25h\u001B[?1049l")
        assertFalse(emu.buffer.sgrMouseMode)
        assertEquals(0, emu.buffer.mouseTrackingMode)
        assertTrue(emu.buffer.cursorVisible)
        assertFalse(emu.buffer.isAltScreen)
    }

    @Test
    fun testSgrColors16And256AndTrueColor() {
        val emu = TerminalEmulator(initialCols = 20, initialRows = 4)
        feed(emu, "\u001B[90mA\u001B[38;5;196;48;5;235mB\u001B[38;2;12;34;56;48:2::200:100:50mC\u001B[0mD")

        val scr = emu.buffer.active
        assertEquals(TerminalColors.ansi16(8), scr.fg[0])
        assertEquals(TerminalColors.indexed256(196), scr.fg[1])
        assertEquals(TerminalColors.indexed256(235), scr.bg[1])
        assertEquals(TerminalColors.rgb(12, 34, 56), scr.fg[2])
        assertEquals(TerminalColors.rgb(200, 100, 50), scr.bg[2])
        assertEquals(0, scr.fg[3])
        assertEquals(0, scr.bg[3])
    }

    @Test
    fun testEditEraseAndScrollRegion() {
        val emu = TerminalEmulator(initialCols = 10, initialRows = 5)
        feed(emu, "row1\r\nrow2\r\nrow3\r\nrow4\r\nrow5")
        feed(emu, "\u001B[2;4r\u001B[4;1H\n")
        assertEquals("row1", emu.buffer.rowText(0))
        assertEquals("row3", emu.buffer.rowText(1))
        assertEquals("row4", emu.buffer.rowText(2))
        assertEquals("", emu.buffer.rowText(3))
        assertEquals("row5", emu.buffer.rowText(4))

        // ECH (erase chars without moving cursor)
        feed(emu, "\u001B[1;2H\u001B[2X")
        assertEquals("r  1", emu.buffer.rowText(0))
        assertEquals(1, emu.buffer.cursorCol)
    }

    @Test
    fun testSplitUtf8AndDecLineDrawing() {
        val emu = TerminalEmulator(initialCols = 20, initialRows = 3)
        val utf8Bytes = "├─htop".toByteArray(StandardCharsets.UTF_8)
        // Feed 1 byte at a time to test split multi-byte UTF-8 sequences
        for (b in utf8Bytes) {
            emu.process(byteArrayOf(b), 1)
        }
        assertEquals("├─htop", emu.buffer.rowText(0))

        feed(emu, "\r\n\u001B(0lqkx\u001B(B")
        assertEquals("┌─┐│", emu.buffer.rowText(1))
    }

    @Test
    fun testAltScreenSavesMainScreenAndDsrResponse() {
        var reply = ""
        val emu = TerminalEmulator(initialCols = 20, initialRows = 5) { bytes ->
            reply = String(bytes, StandardCharsets.US_ASCII)
        }
        feed(emu, "MAIN")
        feed(emu, "\u001B[?1049h\u001B[2;5HALT\u001B[6n")
        assertEquals("\u001B[2;8R", reply)
        assertEquals("    ALT", emu.buffer.rowText(1))

        feed(emu, "\u001B[?1049l")
        assertEquals("MAIN", emu.buffer.rowText(0))
        assertEquals(0, emu.buffer.cursorRow)
        assertEquals(4, emu.buffer.cursorCol)
    }

    @Test
    fun testToyboxTopStartupAndDsrScreenProbe() {
        var reply = ""
        val emu = TerminalEmulator(initialCols = 80, initialRows = 24) { bytes ->
            reply = String(bytes, StandardCharsets.US_ASCII)
        }
        feed(
            emu,
            "\u001B[s\u001B[999C\u001B[999B\u001B[6n\u001B[u\u001B[H\u001B[J\u001B[?25l" +
                "Tasks: 599 total,   2 running, 597 sleeping",
        )
        assertEquals("\u001B[24;80R", reply)
        assertEquals("Tasks: 599 total,   2 running, 597 sleeping", emu.buffer.rowText(0))
        assertFalse(emu.buffer.cursorVisible)
    }
}

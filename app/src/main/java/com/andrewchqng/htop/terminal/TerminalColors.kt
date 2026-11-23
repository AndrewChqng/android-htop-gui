package com.andrewchqng.htop.terminal

object TerminalColors {
    const val DEFAULT_BG: Int = 0xFF0D0F12.toInt()
    const val DEFAULT_FG: Int = 0xFFE6EDF3.toInt()
    const val CURSOR_COLOR: Int = 0xFF58A6FF.toInt()

    private val PALETTE_256: IntArray = IntArray(256).apply {
        val base16 = intArrayOf(
            0xFF0D0F12.toInt(), // 0: Black
            0xFFFF6B6B.toInt(), // 1: Red
            0xFF4ADE80.toInt(), // 2: Green
            0xFFFACC15.toInt(), // 3: Yellow
            0xFF60A5FA.toInt(), // 4: Blue
            0xFFC084FC.toInt(), // 5: Magenta
            0xFF22D3EE.toInt(), // 6: Cyan
            0xFFD0D7DE.toInt(), // 7: White
            0xFF7D8590.toInt(), // 8: Bright Black
            0xFFFF8E8E.toInt(), // 9: Bright Red
            0xFF86EFAC.toInt(), // 10: Bright Green
            0xFFFDE047.toInt(), // 11: Bright Yellow
            0xFF93C5FD.toInt(), // 12: Bright Blue
            0xFFD8B4FE.toInt(), // 13: Bright Magenta
            0xFF67E8F9.toInt(), // 14: Bright Cyan
            0xFFFFFFFF.toInt(), // 15: Bright White
        )
        base16.copyInto(this, 0)

        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var idx = 16
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    this[idx++] = (0xFF shl 24) or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
                }
            }
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            this[idx++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    fun ansi16(index: Int): Int = PALETTE_256[index and 0x0F]

    fun indexed256(index: Int): Int = PALETTE_256[index.coerceIn(0, 255)]

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}

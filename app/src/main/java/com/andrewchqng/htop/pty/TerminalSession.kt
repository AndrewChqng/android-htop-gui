package com.andrewchqng.htop.pty

interface TerminalSession {
    fun start(rows: Int, cols: Int, xpixel: Int, ypixel: Int): Boolean
    fun write(bytes: ByteArray)
    fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int)
    fun isRunning(): Boolean
    fun close()
}

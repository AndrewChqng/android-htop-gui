package com.andrewchqng.htop.pty

object PtyNative {
    init {
        System.loadLibrary("htoppty")
    }

    @JvmStatic
    external fun openPty(
        cmd: String,
        envp: Array<String>?,
        rows: Int,
        cols: Int,
        xpixel: Int,
        ypixel: Int,
    ): IntArray?

    @JvmStatic
    external fun setWindowSize(
        masterFd: Int,
        rows: Int,
        cols: Int,
        xpixel: Int,
        ypixel: Int,
    ): Int

    @JvmStatic
    external fun readFd(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    @JvmStatic
    external fun writeFd(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int): Int

    @JvmStatic
    external fun closeFd(fd: Int)
}

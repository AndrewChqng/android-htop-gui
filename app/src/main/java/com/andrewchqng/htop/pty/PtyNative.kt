package com.andrewchqng.htop.pty

import java.io.File

object PtyNative {
    @Volatile
    private var loaded = false

    init {
        tryLoad(null)
    }

    @Synchronized
    fun ensureLoaded(nativeLibDir: String? = null): Boolean {
        if (loaded) {
            return true
        }
        return tryLoad(nativeLibDir)
    }

    private fun tryLoad(nativeLibDir: String?): Boolean {
        try {
            System.loadLibrary("htoppty")
            loaded = true
            return true
        } catch (_: UnsatisfiedLinkError) {
            if (!nativeLibDir.isNullOrBlank()) {
                val soFile = File(nativeLibDir, "libhtoppty.so")
                if (soFile.exists()) {
                    try {
                        System.load(soFile.absolutePath)
                        loaded = true
                        return true
                    } catch (_: UnsatisfiedLinkError) {
                        // Ignore
                    }
                }
            }
        }
        return false
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

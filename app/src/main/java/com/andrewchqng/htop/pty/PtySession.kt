package com.andrewchqng.htop.pty

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PtySession(
    private val command: String,
    private val envp: Array<String>?,
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private val masterFd = AtomicInteger(-1)
    private val childPid = AtomicInteger(-1)
    private val closed = AtomicBoolean(false)
    private val exitReported = AtomicBoolean(false)
    private val fdLock = Any()

    @Volatile
    private var writerExecutor: ExecutorService? = null

    @Volatile
    private var readerThread: Thread? = null

    fun start(rows: Int, cols: Int, xpixel: Int, ypixel: Int): Boolean {
        val ids = PtyNative.openPty(command, envp, rows, cols, xpixel, ypixel) ?: return false
        if (ids.size < 2 || ids[0] < 0 || ids[1] <= 0) {
            if (ids.isNotEmpty() && ids[0] >= 0) {
                PtyNative.closeFd(ids[0])
            }
            return false
        }

        val fd = ids[0]
        val pid = ids[1]
        masterFd.set(fd)
        childPid.set(pid)

        val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "htop-pty-write").apply { isDaemon = true }
        }
        writerExecutor = writer

        val reader = Thread({
            val buffer = ByteArray(8192)
            while (!closed.get()) {
                val activeFd = masterFd.get()
                if (activeFd < 0) {
                    break
                }
                val n = PtyNative.readFd(activeFd, buffer, 0, buffer.size)
                if (n > 0) {
                    onOutput(buffer, n)
                } else {
                    break
                }
            }
            closeMasterFd()
        }, "htop-pty-read").apply {
            isDaemon = true
            start()
        }
        readerThread = reader

        Thread({
            val status = PtyNative.waitFor(pid)
            try {
                reader.join(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            closeMasterFd()
            writer.shutdown()
            if (exitReported.compareAndSet(false, true)) {
                onExit(status)
            }
        }, "htop-pty-wait").apply {
            isDaemon = true
            start()
        }

        return true
    }

    fun write(bytes: ByteArray) {
        if (closed.get() || bytes.isEmpty()) {
            return
        }
        val executor = writerExecutor ?: return
        if (executor.isShutdown) {
            return
        }
        val copy = bytes.copyOf()
        try {
            executor.execute {
                val fd = masterFd.get()
                if (fd >= 0 && !closed.get()) {
                    PtyNative.writeFd(fd, copy, 0, copy.size)
                }
            }
        } catch (_: Exception) {
            // Executor already shutting down during session teardown
        }
    }

    fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int) {
        if (closed.get() || rows <= 0 || cols <= 0) {
            return
        }
        synchronized(fdLock) {
            val fd = masterFd.get()
            if (fd >= 0) {
                PtyNative.setWindowSize(fd, rows, cols, xpixel, ypixel)
            }
        }
    }

    fun isRunning(): Boolean = !closed.get() && !exitReported.get() && childPid.get() > 0

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val pid = childPid.get()
        if (pid > 0) {
            PtyNative.sendSignal(pid, SIGHUP)
            PtyNative.sendSignal(pid, SIGTERM)
        }
        closeMasterFd()
        writerExecutor?.shutdownNow()
    }

    private fun closeMasterFd() {
        synchronized(fdLock) {
            val fd = masterFd.getAndSet(-1)
            if (fd >= 0) {
                PtyNative.closeFd(fd)
            }
        }
    }

    companion object {
        private const val SIGHUP = 1
        private const val SIGTERM = 15
    }
}

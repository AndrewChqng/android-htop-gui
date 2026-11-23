package com.andrewchqng.htop.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.andrewchqng.htop.pty.TerminalSession
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuPtySession(
    context: Context,
    private val command: String,
    private val envp: Array<String>?,
    private val outputListener: (ByteArray, Int) -> Unit,
    private val exitListener: (Int) -> Unit,
) : TerminalSession {

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val exitReported = AtomicBoolean(false)
    private val sizeLock = Any()

    private var latestRows = 24
    private var latestCols = 80
    private var latestXPixel = 0
    private var latestYPixel = 0

    private val aidlCallback = object : IShizukuPtyCallback.Stub() {
        override fun onOutput(data: ByteArray, length: Int) {
            if (!closed.get() && length > 0) {
                outputListener(data, length.coerceAtMost(data.size))
            }
        }

        override fun onExit(exitCode: Int) {
            reportExitOnce(exitCode)
        }
    }

    override fun start(rows: Int, cols: Int, xpixel: Int, ypixel: Int): Boolean {
        synchronized(sizeLock) {
            latestRows = rows.coerceAtLeast(1)
            latestCols = cols.coerceAtLeast(1)
            latestXPixel = xpixel.coerceAtLeast(0)
            latestYPixel = ypixel.coerceAtLeast(0)
        }
        if (!ShizukuHelper.isRunning()) {
            return false
        }
        startWithSharedConnection(appContext, this)
        return true
    }

    private fun startOnService(svc: IShizukuPtyService) {
        if (closed.get()) {
            return
        }
        val r: Int
        val c: Int
        val xp: Int
        val yp: Int
        synchronized(sizeLock) {
            r = latestRows
            c = latestCols
            xp = latestXPixel
            yp = latestYPixel
        }
        val started = runCatching {
            svc.start(command, envp, r, c, xp, yp, aidlCallback)
        }.getOrDefault(false)
        if (!started) {
            reportExitOnce(-1)
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed.get() || bytes.isEmpty()) {
            return
        }
        val copy = bytes.copyOf()
        ipcExecutor.execute {
            if (closed.get() || activeSession !== this) return@execute
            val svc = sharedService ?: return@execute
            runCatching { svc.write(copy) }
        }
    }

    override fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int) {
        if (closed.get() || rows <= 0 || cols <= 0) {
            return
        }
        synchronized(sizeLock) {
            latestRows = rows
            latestCols = cols
            latestXPixel = xpixel
            latestYPixel = ypixel
        }
        ipcExecutor.execute {
            if (closed.get() || activeSession !== this) return@execute
            val svc = sharedService ?: return@execute
            runCatching { svc.resize(rows, cols, xpixel, ypixel) }
        }
    }

    override fun isRunning(): Boolean = !closed.get() && !exitReported.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        ipcExecutor.execute {
            if (activeSession === this) {
                activeSession = null
                runCatching { sharedService?.stopSession() }
            }
        }
    }

    private fun reportExitOnce(code: Int) {
        if (!closed.get() && exitReported.compareAndSet(false, true)) {
            exitListener(code)
        }
    }

    companion object {
        private val ipcExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "htop-shizuku-ipc").apply { isDaemon = true }
        }

        @Volatile
        private var activeSession: ShizukuPtySession? = null

        @Volatile
        private var sharedService: IShizukuPtyService? = null

        @Volatile
        private var bindingInProgress = false

        @Volatile
        private var cachedArgs: Shizuku.UserServiceArgs? = null

        private val sharedConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bindingInProgress = false
                val svc = IShizukuPtyService.Stub.asInterface(binder)
                sharedService = svc
                ipcExecutor.execute {
                    val session = activeSession
                    if (session != null && !session.closed.get()) {
                        if (svc != null) {
                            session.startOnService(svc)
                        } else {
                            session.reportExitOnce(-1)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bindingInProgress = false
                sharedService = null
                ipcExecutor.execute {
                    activeSession?.reportExitOnce(-1)
                }
            }
        }

        private fun getArgs(appContext: Context): Shizuku.UserServiceArgs {
            cachedArgs?.let { return it }
            return Shizuku.UserServiceArgs(
                ComponentName(appContext, ShizukuPtyService::class.java),
            )
                .daemon(false)
                .processNameSuffix("htoppty")
                .debuggable(false)
                .version(3)
                .also { cachedArgs = it }
        }

        private fun startWithSharedConnection(appContext: Context, session: ShizukuPtySession) {
            val args = getArgs(appContext)
            ipcExecutor.execute {
                activeSession = session
                val existing = sharedService
                if (existing != null && existing.asBinder().isBinderAlive) {
                    session.startOnService(existing)
                    return@execute
                }
                sharedService = null
                if (!bindingInProgress) {
                    bindingInProgress = true
                    try {
                        Shizuku.bindUserService(args, sharedConnection)
                    } catch (_: Throwable) {
                        bindingInProgress = false
                        session.reportExitOnce(-1)
                    }
                }
            }
        }

        fun release(context: Context) {
            val appContext = context.applicationContext
            val args = getArgs(appContext)
            ipcExecutor.execute {
                activeSession = null
                val svc = sharedService
                sharedService = null
                bindingInProgress = false
                runCatching { svc?.stopSession() }
                runCatching { Shizuku.unbindUserService(args, sharedConnection, true) }
            }
        }
    }
}

package com.andrewchqng.htop.shizuku

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import com.andrewchqng.htop.pty.PtyNative
import com.andrewchqng.htop.pty.PtySession
import kotlin.system.exitProcess

@Keep
class ShizukuPtyService : IShizukuPtyService.Stub {

    private val nativeLibDir: String?
    private var activeSession: PtySession? = null
    private var activeCallbackBinder: IBinder? = null
    private var activeDeathRecipient: IBinder.DeathRecipient? = null

    @Volatile
    private var stopRequested = false

    constructor() : super() {
        nativeLibDir = null
    }

    @Keep
    constructor(context: Context) : super() {
        nativeLibDir = context.applicationInfo?.nativeLibraryDir
    }

    @Synchronized
    override fun start(
        command: String,
        envp: Array<String>?,
        rows: Int,
        cols: Int,
        xpixel: Int,
        ypixel: Int,
        callback: IShizukuPtyCallback?,
    ): Boolean {
        stopSession()
        if (callback == null) {
            return false
        }

        if (!PtyNative.ensureLoaded(nativeLibDir)) {
            Log.e(TAG, "Failed to load libhtoppty.so inside Shizuku UserService")
            return false
        }

        stopRequested = false
        val binder = callback.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            stopSession()
            exitProcess(0)
        }
        runCatching { binder.linkToDeath(deathRecipient, 0) }
        activeCallbackBinder = binder
        activeDeathRecipient = deathRecipient

        lateinit var created: PtySession
        val session = PtySession(
            command = command,
            envp = envp,
            onOutput = { buf, len ->
                if (!stopRequested && activeSession === created) {
                    val payload = if (len == buf.size) buf else buf.copyOf(len)
                    try {
                        callback.onOutput(payload, len)
                    } catch (_: RemoteException) {
                        stopSession()
                    }
                }
            },
            onExit = { code ->
                if (!stopRequested && activeSession === created) {
                    try {
                        callback.onExit(code)
                    } catch (_: RemoteException) {
                        // Client already disconnected
                    }
                }
            },
        )
        created = session
        activeSession = session
        if (!session.start(rows, cols, xpixel, ypixel)) {
            stopSession()
            return false
        }
        return true
    }

    override fun write(data: ByteArray?) {
        if (data != null && data.isNotEmpty()) {
            activeSession?.write(data)
        }
    }

    override fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int) {
        activeSession?.resize(rows, cols, xpixel, ypixel)
    }

    @Synchronized
    override fun stopSession() {
        stopRequested = true
        val binder = activeCallbackBinder
        val dr = activeDeathRecipient
        if (binder != null && dr != null) {
            runCatching { binder.unlinkToDeath(dr, 0) }
        }
        activeCallbackBinder = null
        activeDeathRecipient = null
        activeSession?.close()
        activeSession = null
    }

    override fun getUid(): Int = Process.myUid()

    @Synchronized
    override fun destroy() {
        stopSession()
        exitProcess(0)
    }

    companion object {
        private const val TAG = "ShizukuPtyService"
    }
}

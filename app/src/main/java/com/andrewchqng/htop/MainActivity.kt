package com.andrewchqng.htop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andrewchqng.htop.pty.BackendSelector
import com.andrewchqng.htop.pty.ExecutionBackend
import com.andrewchqng.htop.pty.HtopCommandBuilder
import com.andrewchqng.htop.pty.MonitorMode
import com.andrewchqng.htop.pty.PtySession
import com.andrewchqng.htop.pty.TerminalSession
import com.andrewchqng.htop.shizuku.ShizukuHelper
import com.andrewchqng.htop.shizuku.ShizukuPtySession
import com.andrewchqng.htop.terminal.TerminalEmulator
import com.andrewchqng.htop.ui.ExtraKeysBar
import com.andrewchqng.htop.ui.InputEncoder
import com.andrewchqng.htop.ui.TerminalView
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var exitBanner: LinearLayout
    private lateinit var exitStatusText: TextView
    private lateinit var switchModeBannerButton: Button
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysBar: ExtraKeysBar

    private var emulator: TerminalEmulator? = null
    private var session: TerminalSession? = null
    private var monitorMode = MonitorMode.HTOP
    private var activeBackend = ExecutionBackend.ROOT
    private var pendingShizukuStart = false
    private var autoFallbackAttempted = false
    private var hasStartedOnce = false
    private var lastCols = 0
    private var lastRows = 0
    private var lastWidthPx = 0
    private var lastHeightPx = 0

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ_CODE) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && pendingShizukuStart) {
                        pendingShizukuStart = false
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            startSession(ExecutionBackend.SHIZUKU)
                        } else {
                            appendTerminalNotice("Shizuku permission denied.\r\n")
                            showExitBanner("${monitorMode.label} (shizuku) denied")
                        }
                    }
                }
            }
        }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        // Binder became available while activity is alive
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF0D0F12.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF161B22.toInt()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val defaultFontSp = if (resources.configuration.smallestScreenWidthDp >= 600) 12f else 10.5f
        val savedFontSp = prefs.getFloat(KEY_FONT_SP, defaultFontSp)
        monitorMode = runCatching {
            MonitorMode.valueOf(prefs.getString(KEY_MONITOR_MODE, MonitorMode.HTOP.name).orEmpty())
        }.getOrDefault(MonitorMode.HTOP)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0F12.toInt())
        }

        exitBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF21262D.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        exitStatusText = TextView(this).apply {
            setTextColor(0xFFE6EDF3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        exitBanner.addView(exitStatusText)

        exitBanner.addView(makeBannerButton(getString(R.string.btn_restart_root)) {
            startSession(ExecutionBackend.ROOT, resetAutoFallback = false)
        })
        exitBanner.addView(makeBannerButton(getString(R.string.btn_use_shizuku)) {
            startSession(ExecutionBackend.SHIZUKU, resetAutoFallback = false)
        })
        exitBanner.addView(makeBannerButton(getString(R.string.btn_run_non_root)) {
            startSession(ExecutionBackend.NON_ROOT, resetAutoFallback = false)
        })
        switchModeBannerButton = makeBannerButton(bannerSwitchLabel(monitorMode)) {
            toggleMonitorMode(prefs)
        }
        exitBanner.addView(switchModeBannerButton)
        exitBanner.addView(makeBannerButton(getString(R.string.btn_close)) {
            finish()
        })

        terminalView = TerminalView(this).apply {
            fontSizeSp = savedFontSp
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        extraKeysBar = ExtraKeysBar(this).apply {
            setMonitorMode(monitorMode)
            setBackend(activeBackend)
        }
        terminalView.modifierDelegate = extraKeysBar

        rootLayout.addView(
            exitBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        rootLayout.addView(
            terminalView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        rootLayout.addView(
            extraKeysBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListener(shizukuBinderListener)
        }

        wireCallbacks(prefs)
        terminalView.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = parseRequestedBackend(intent)
        if (requested != null && hasStartedOnce) {
            startSession(requested, resetAutoFallback = true)
        }
    }

    private fun wireCallbacks(prefs: android.content.SharedPreferences) {
        terminalView.onInputBytes = { bytes ->
            session?.write(bytes)
        }

        terminalView.onFontSizeChanged = { newSize ->
            prefs.edit().putFloat(KEY_FONT_SP, newSize).apply()
        }

        terminalView.onGridSizeChanged = { rows, cols, widthPx, heightPx ->
            lastRows = rows
            lastCols = cols
            lastWidthPx = widthPx
            lastHeightPx = heightPx
            if (!hasStartedOnce) {
                hasStartedOnce = true
                val initial = BackendSelector.selectInitialBackend(
                    preferred = parseRequestedBackend(intent),
                    hasSuBinary = HtopCommandBuilder.hasSuBinary(),
                    shizukuRunning = ShizukuHelper.isRunning(),
                )
                startSession(initial, resetAutoFallback = true)
            } else {
                session?.resize(rows, cols, widthPx, heightPx)
            }
        }

        extraKeysBar.onKeyBytes = { bytes ->
            session?.write(bytes)
        }

        extraKeysBar.onSpecialKeyCode = { keyCode ->
            val appCursor = emulator?.let { synchronized(it.lock) { it.buffer.applicationCursorKeys } } ?: false
            val ctrl = extraKeysBar.consumeCtrl()
            val bytes = InputEncoder.encodeKeyEvent(
                keyCode = keyCode,
                unicodeChar = 0,
                ctrl = ctrl,
                alt = false,
                shift = false,
                appCursorKeys = appCursor,
            )
            if (bytes != null) {
                session?.write(bytes)
            }
        }

        extraKeysBar.onToggleKeyboard = {
            terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val imeVisible = ViewCompat.getRootWindowInsets(rootLayout)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            } else {
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        extraKeysBar.onZoomDelta = { delta ->
            val next = (terminalView.fontSizeSp + delta).coerceIn(7f, 28f)
            terminalView.fontSizeSp = next
            prefs.edit().putFloat(KEY_FONT_SP, next).apply()
        }

        extraKeysBar.onRestartRequested = {
            startSession(activeBackend, resetAutoFallback = false)
        }

        extraKeysBar.onToggleMonitorMode = {
            toggleMonitorMode(prefs)
        }

        extraKeysBar.onToggleBackend = {
            toggleBackend()
        }
    }

    private fun parseRequestedBackend(intent: Intent?): ExecutionBackend? {
        return when (intent?.getStringExtra(EXTRA_BACKEND)?.lowercase()) {
            "root" -> ExecutionBackend.ROOT
            "shizuku", "shz" -> ExecutionBackend.SHIZUKU
            "non_root", "non-root", "user" -> ExecutionBackend.NON_ROOT
            else -> null
        }
    }

    private fun toggleBackend() {
        val next = BackendSelector.nextBackend(
            current = activeBackend,
            hasSuBinary = HtopCommandBuilder.hasSuBinary(),
            shizukuRunning = ShizukuHelper.isRunning(),
        )
        startSession(next, resetAutoFallback = true)
    }

    private fun toggleMonitorMode(prefs: android.content.SharedPreferences) {
        monitorMode = if (monitorMode == MonitorMode.HTOP) MonitorMode.TOP else MonitorMode.HTOP
        prefs.edit().putString(KEY_MONITOR_MODE, monitorMode.name).apply()
        extraKeysBar.setMonitorMode(monitorMode)
        switchModeBannerButton.text = bannerSwitchLabel(monitorMode)
        startSession(activeBackend, resetAutoFallback = false)
    }

    private fun bannerSwitchLabel(mode: MonitorMode): String =
        if (mode == MonitorMode.HTOP) "Switch to top" else "Switch to htop"

    private fun startSession(backend: ExecutionBackend, resetAutoFallback: Boolean = false) {
        session?.close()
        session = null
        pendingShizukuStart = false
        if (resetAutoFallback) {
            autoFallbackAttempted = false
        }
        activeBackend = backend
        extraKeysBar.setBackend(backend)
        val activeMode = monitorMode

        val rows = lastRows.coerceAtLeast(1)
        val cols = lastCols.coerceAtLeast(1)

        val newEmulator = TerminalEmulator(
            initialCols = cols,
            initialRows = rows,
            onResponse = { reply -> session?.write(reply) },
        )
        emulator = newEmulator
        terminalView.emulator = newEmulator
        exitBanner.visibility = View.GONE

        if (backend == ExecutionBackend.SHIZUKU) {
            if (!ShizukuHelper.isRunning()) {
                appendTerminalNotice("Shizuku service is not running. Start Shizuku app first.\r\n")
                showExitBanner("${activeMode.label} (shizuku) unavailable")
                return
            }
            if (!ShizukuHelper.isPermissionGranted()) {
                pendingShizukuStart = true
                appendTerminalNotice("Requesting Shizuku permission...\r\n")
                if (!ShizukuHelper.requestPermission(SHIZUKU_REQ_CODE)) {
                    pendingShizukuStart = false
                    showExitBanner("${activeMode.label} (shizuku) permission failed")
                }
                return
            }
        }

        val configDir: String
        val homeDir: String
        if (backend == ExecutionBackend.SHIZUKU) {
            configDir = "/data/local/tmp/.android-htop-config"
            homeDir = "/data/local/tmp"
        } else {
            configDir = File(filesDir, "config").apply { mkdirs() }.absolutePath
            homeDir = filesDir.absolutePath
        }

        val command = HtopCommandBuilder.buildCommand(
            configDir = configDir,
            homeDir = homeDir,
            asRoot = (backend == ExecutionBackend.ROOT),
            mode = activeMode,
        )
        val envp = HtopCommandBuilder.buildEnvironment(homeDir = homeDir, configDir = configDir)
        val startTimeMs = SystemClock.elapsedRealtime()

        var createdSession: TerminalSession? = null
        val onOutput: (ByteArray, Int) -> Unit = { buf, len ->
            newEmulator.process(buf, len)
            terminalView.notifyScreenUpdated()
        }
        val onExit: (Int) -> Unit = { code ->
            runOnUiThread {
                if (!isDestroyed && !isFinishing && session === createdSession) {
                    val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                    if (BackendSelector.shouldAutoFallbackToShizuku(
                            failedBackend = backend,
                            exitCode = code,
                            runtimeMs = elapsed,
                            alreadyAttempted = autoFallbackAttempted,
                            shizukuRunning = ShizukuHelper.isRunning(),
                        )
                    ) {
                        autoFallbackAttempted = true
                        startSession(ExecutionBackend.SHIZUKU)
                    } else {
                        showExitBanner("${activeMode.label} (${backend.label}) exited ($code)")
                    }
                }
            }
        }

        val newSession: TerminalSession = if (backend == ExecutionBackend.SHIZUKU) {
            ShizukuPtySession(
                context = this,
                command = command,
                envp = envp,
                outputListener = onOutput,
                exitListener = onExit,
            )
        } else {
            PtySession(
                command = command,
                envp = envp,
                onOutput = onOutput,
                onExit = onExit,
            )
        }
        createdSession = newSession
        session = newSession
        if (!newSession.start(rows, cols, lastWidthPx, lastHeightPx)) {
            appendTerminalNotice("Failed to open ${backend.label} PTY session.\r\n")
            showExitBanner("Failed to start ${backend.label} PTY")
        }
        terminalView.requestFocus()
    }

    private fun appendTerminalNotice(text: String) {
        val emu = emulator ?: return
        val bytes = text.toByteArray()
        emu.process(bytes, bytes.size)
        terminalView.notifyScreenUpdated()
    }

    private fun showExitBanner(status: String) {
        exitStatusText.text = status
        exitBanner.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        }
        session?.close()
        session = null
        ShizukuPtySession.release(this)
        super.onDestroy()
    }

    private fun makeBannerButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF30363D.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32),
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    companion object {
        private const val PREFS_NAME = "htop_prefs"
        private const val KEY_FONT_SP = "font_sp"
        private const val KEY_MONITOR_MODE = "monitor_mode"
        private const val EXTRA_BACKEND = "backend"
        private const val SHIZUKU_REQ_CODE = 4201
    }
}

package com.andrewchqng.htop

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import com.andrewchqng.htop.pty.HtopCommandBuilder
import com.andrewchqng.htop.pty.PtySession
import com.andrewchqng.htop.terminal.TerminalEmulator
import com.andrewchqng.htop.ui.ExtraKeysBar
import com.andrewchqng.htop.ui.InputEncoder
import com.andrewchqng.htop.ui.TerminalView
import java.io.File

class MainActivity : Activity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var exitBanner: LinearLayout
    private lateinit var exitStatusText: TextView
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysBar: ExtraKeysBar

    private var emulator: TerminalEmulator? = null
    private var session: PtySession? = null
    private var lastStartedAsRoot = true
    private var hasStartedOnce = false
    private var lastCols = 0
    private var lastRows = 0
    private var lastWidthPx = 0
    private var lastHeightPx = 0

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
            startHtopSession(asRoot = true)
        })
        exitBanner.addView(makeBannerButton(getString(R.string.btn_run_non_root)) {
            startHtopSession(asRoot = false)
        })
        exitBanner.addView(makeBannerButton(getString(R.string.btn_close)) {
            finish()
        })

        terminalView = TerminalView(this).apply {
            fontSizeSp = savedFontSp
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        extraKeysBar = ExtraKeysBar(this)
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

        wireCallbacks(prefs)
        terminalView.requestFocus()
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
                startHtopSession(asRoot = true)
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
            startHtopSession(asRoot = lastStartedAsRoot)
        }
    }

    private fun startHtopSession(asRoot: Boolean) {
        session?.close()
        session = null
        lastStartedAsRoot = asRoot

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

        val configDir = File(filesDir, "config").apply { mkdirs() }.absolutePath
        val homeDir = filesDir.absolutePath
        val command = HtopCommandBuilder.buildCommand(
            configDir = configDir,
            homeDir = homeDir,
            asRoot = asRoot,
        )
        val envp = HtopCommandBuilder.buildEnvironment(homeDir = homeDir, configDir = configDir)

        val newSession = PtySession(
            command = command,
            envp = envp,
            onOutput = { buf, len ->
                newEmulator.process(buf, len)
                terminalView.notifyScreenUpdated()
            },
            onExit = { code ->
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        val mode = if (asRoot) "root" else "non-root"
                        exitStatusText.text = "htop ($mode) exited ($code)"
                        exitBanner.visibility = View.VISIBLE
                    }
                }
            },
        )
        session = newSession
        if (!newSession.start(rows, cols, lastWidthPx, lastHeightPx)) {
            val msg = "Failed to open PTY session.\r\n".toByteArray()
            newEmulator.process(msg, msg.size)
            terminalView.notifyScreenUpdated()
            exitStatusText.text = "Failed to start PTY"
            exitBanner.visibility = View.VISIBLE
        }
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        session?.close()
        session = null
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
    }
}

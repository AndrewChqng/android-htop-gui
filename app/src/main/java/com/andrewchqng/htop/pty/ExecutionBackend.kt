package com.andrewchqng.htop.pty

enum class ExecutionBackend(val label: String, val badge: String) {
    ROOT("root", "ROOT"),
    SHIZUKU("shizuku", "SHZ"),
    NON_ROOT("non-root", "USER"),
}

object BackendSelector {

    private const val QUICK_FAILURE_WINDOW_MS = 3_000L

    fun selectInitialBackend(
        preferred: ExecutionBackend?,
        hasSuBinary: Boolean,
        shizukuRunning: Boolean,
    ): ExecutionBackend {
        if (preferred != null) {
            return when (preferred) {
                ExecutionBackend.ROOT -> when {
                    hasSuBinary -> ExecutionBackend.ROOT
                    shizukuRunning -> ExecutionBackend.SHIZUKU
                    else -> ExecutionBackend.NON_ROOT
                }
                ExecutionBackend.SHIZUKU -> when {
                    shizukuRunning -> ExecutionBackend.SHIZUKU
                    hasSuBinary -> ExecutionBackend.ROOT
                    else -> ExecutionBackend.NON_ROOT
                }
                ExecutionBackend.NON_ROOT -> ExecutionBackend.NON_ROOT
            }
        }
        return when {
            hasSuBinary -> ExecutionBackend.ROOT
            shizukuRunning -> ExecutionBackend.SHIZUKU
            else -> ExecutionBackend.NON_ROOT
        }
    }

    fun shouldAutoFallbackToShizuku(
        failedBackend: ExecutionBackend,
        exitCode: Int,
        runtimeMs: Long,
        alreadyAttempted: Boolean,
        shizukuRunning: Boolean,
    ): Boolean {
        if (failedBackend != ExecutionBackend.ROOT || exitCode == 0 || alreadyAttempted || !shizukuRunning) {
            return false
        }
        return exitCode == 126 || exitCode == 13 || runtimeMs < QUICK_FAILURE_WINDOW_MS
    }

    fun nextBackend(
        current: ExecutionBackend,
        hasSuBinary: Boolean,
        shizukuRunning: Boolean,
    ): ExecutionBackend = when (current) {
        ExecutionBackend.ROOT -> when {
            shizukuRunning -> ExecutionBackend.SHIZUKU
            else -> ExecutionBackend.NON_ROOT
        }
        ExecutionBackend.SHIZUKU -> when {
            hasSuBinary -> ExecutionBackend.ROOT
            else -> ExecutionBackend.NON_ROOT
        }
        ExecutionBackend.NON_ROOT -> when {
            hasSuBinary -> ExecutionBackend.ROOT
            shizukuRunning -> ExecutionBackend.SHIZUKU
            else -> ExecutionBackend.NON_ROOT
        }
    }
}

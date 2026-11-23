package com.andrewchqng.htop.pty

enum class ExecutionBackend(val label: String, val badge: String) {
    ROOT("root", "ROOT"),
    SHIZUKU("shizuku", "SHZ"),
    NON_ROOT("non-root", "USER"),
}

object BackendSelector {

    fun selectInitialBackend(
        preferred: ExecutionBackend?,
        saved: ExecutionBackend? = null,
    ): ExecutionBackend = preferred ?: saved ?: ExecutionBackend.ROOT

    fun nextBackend(current: ExecutionBackend): ExecutionBackend = when (current) {
        ExecutionBackend.ROOT -> ExecutionBackend.SHIZUKU
        ExecutionBackend.SHIZUKU -> ExecutionBackend.NON_ROOT
        ExecutionBackend.NON_ROOT -> ExecutionBackend.ROOT
    }
}

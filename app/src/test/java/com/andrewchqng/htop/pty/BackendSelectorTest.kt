package com.andrewchqng.htop.pty

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendSelectorTest {

    @Test
    fun testSelectInitialBackendUsesPreferredThenSavedThenRootWithoutFallback() {
        assertEquals(
            ExecutionBackend.ROOT,
            BackendSelector.selectInitialBackend(preferred = null, saved = null),
        )
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.selectInitialBackend(preferred = null, saved = ExecutionBackend.SHIZUKU),
        )
        assertEquals(
            ExecutionBackend.NON_ROOT,
            BackendSelector.selectInitialBackend(preferred = ExecutionBackend.NON_ROOT, saved = ExecutionBackend.ROOT),
        )
        assertEquals(
            ExecutionBackend.ROOT,
            BackendSelector.selectInitialBackend(preferred = ExecutionBackend.ROOT, saved = ExecutionBackend.SHIZUKU),
        )
    }

    @Test
    fun testNextBackendCycling() {
        assertEquals(ExecutionBackend.SHIZUKU, BackendSelector.nextBackend(ExecutionBackend.ROOT))
        assertEquals(ExecutionBackend.NON_ROOT, BackendSelector.nextBackend(ExecutionBackend.SHIZUKU))
        assertEquals(ExecutionBackend.ROOT, BackendSelector.nextBackend(ExecutionBackend.NON_ROOT))
    }
}

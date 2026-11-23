package com.andrewchqng.htop.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {

    @Test
    fun testSelectInitialBackendPrefersRootThenShizukuThenNonRoot() {
        assertEquals(
            ExecutionBackend.ROOT,
            BackendSelector.selectInitialBackend(preferred = null, hasSuBinary = true, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.selectInitialBackend(preferred = null, hasSuBinary = false, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.NON_ROOT,
            BackendSelector.selectInitialBackend(preferred = null, hasSuBinary = false, shizukuRunning = false),
        )
    }

    @Test
    fun testSelectInitialBackendHonorsAndFallsBackPreferredBackend() {
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.selectInitialBackend(ExecutionBackend.SHIZUKU, hasSuBinary = true, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.ROOT,
            BackendSelector.selectInitialBackend(ExecutionBackend.SHIZUKU, hasSuBinary = true, shizukuRunning = false),
        )
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.selectInitialBackend(ExecutionBackend.ROOT, hasSuBinary = false, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.NON_ROOT,
            BackendSelector.selectInitialBackend(ExecutionBackend.NON_ROOT, hasSuBinary = true, shizukuRunning = true),
        )
    }

    @Test
    fun testAutoFallbackToShizukuConditions() {
        assertTrue(
            BackendSelector.shouldAutoFallbackToShizuku(
                failedBackend = ExecutionBackend.ROOT,
                exitCode = 13,
                runtimeMs = 5000L,
                alreadyAttempted = false,
                shizukuRunning = true,
            ),
        )
        assertTrue(
            BackendSelector.shouldAutoFallbackToShizuku(
                failedBackend = ExecutionBackend.ROOT,
                exitCode = 1,
                runtimeMs = 400L,
                alreadyAttempted = false,
                shizukuRunning = true,
            ),
        )
        assertFalse(
            BackendSelector.shouldAutoFallbackToShizuku(
                failedBackend = ExecutionBackend.ROOT,
                exitCode = 0,
                runtimeMs = 100L,
                alreadyAttempted = false,
                shizukuRunning = true,
            ),
        )
        assertFalse(
            BackendSelector.shouldAutoFallbackToShizuku(
                failedBackend = ExecutionBackend.ROOT,
                exitCode = 13,
                runtimeMs = 100L,
                alreadyAttempted = true,
                shizukuRunning = true,
            ),
        )
        assertFalse(
            BackendSelector.shouldAutoFallbackToShizuku(
                failedBackend = ExecutionBackend.SHIZUKU,
                exitCode = 13,
                runtimeMs = 100L,
                alreadyAttempted = false,
                shizukuRunning = true,
            ),
        )
    }

    @Test
    fun testNextBackendCycling() {
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.nextBackend(ExecutionBackend.ROOT, hasSuBinary = true, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.ROOT,
            BackendSelector.nextBackend(ExecutionBackend.SHIZUKU, hasSuBinary = true, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.NON_ROOT,
            BackendSelector.nextBackend(ExecutionBackend.ROOT, hasSuBinary = true, shizukuRunning = false),
        )
        assertEquals(
            ExecutionBackend.NON_ROOT,
            BackendSelector.nextBackend(ExecutionBackend.SHIZUKU, hasSuBinary = false, shizukuRunning = true),
        )
        assertEquals(
            ExecutionBackend.SHIZUKU,
            BackendSelector.nextBackend(ExecutionBackend.NON_ROOT, hasSuBinary = false, shizukuRunning = true),
        )
    }
}

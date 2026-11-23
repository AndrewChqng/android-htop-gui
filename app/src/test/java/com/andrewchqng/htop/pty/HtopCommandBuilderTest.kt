package com.andrewchqng.htop.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtopCommandBuilderTest {

    @Test
    fun testShellQuoteSingle() {
        assertEquals("'simple'", HtopCommandBuilder.shellQuoteSingle("simple"))
        assertEquals("'a'\\''b'", HtopCommandBuilder.shellQuoteSingle("a'b"))
        assertEquals("'path with spaces'", HtopCommandBuilder.shellQuoteSingle("path with spaces"))
    }

    @Test
    fun testBuildCommandRootUsesMagiskInteractivePty() {
        val cmd = HtopCommandBuilder.buildCommand(
            configDir = "/data/user/0/com.andrewchqng.htop/files/config",
            homeDir = "/data/user/0/com.andrewchqng.htop/files",
            asRoot = true,
            extraArgs = listOf("-d", "10"),
        )
        assertTrue(cmd.contains("TERM=xterm-256color"))
        assertTrue(cmd.contains("TERMINFO="))
        assertTrue(cmd.contains("XDG_CONFIG_HOME="))
        assertTrue(cmd.contains("/files/config/htop"))
        assertTrue(cmd.contains("stty raw -echo"))
        assertTrue(cmd.contains("exec \"\$SU\" -i -c \"\$CMD\""))
        assertTrue(cmd.contains("exec \"\$SU\" -c \"\$CMD\""))
        assertTrue(cmd.contains("'-d'"))
        assertTrue(cmd.contains("'10'"))
    }

    @Test
    fun testBuildCommandNonRootSkipsSuAndHasNoTopAutoFallback() {
        val cmd = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
        )
        assertTrue(cmd.contains("exec \"\$HTOP\""))
        assertTrue(cmd.contains("exit 127"))
        assertTrue(cmd.contains("Error: system htop binary not found on device."))
        assertFalse(cmd.contains("exec \"\$TOP\""))
        assertFalse(cmd.contains("su -i -c"))
    }

    @Test
    fun testBuildCommandTopModeSkipsHtopAndRunsTop() {
        val rootTop = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = true,
            extraArgs = listOf("-H"),
            mode = MonitorMode.TOP,
        )
        assertFalse(rootTop.contains("/system_ext/bin/htop"))
        assertTrue(rootTop.contains("exec \"\$TOP\" -d 1"))
        assertTrue(rootTop.contains("-H"))
        assertTrue(rootTop.contains("exec \"\$SU\" -i -c \"\$CMD\""))

        val nonRootTop = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
            extraArgs = listOf("-H"),
            mode = MonitorMode.TOP,
        )
        assertFalse(nonRootTop.contains("/system_ext/bin/htop"))
        assertTrue(nonRootTop.contains("exec \"\$TOP\" -d 1 '-H'"))
        assertFalse(nonRootTop.contains("su -i -c"))
    }

    @Test
    fun testBuildCommandHtopModeDoesNotAutoFallbackToBundledOrTop() {
        val cmd = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
            mode = MonitorMode.HTOP,
            bundledHtopPath = "/data/app/pkg/lib/arm64/libhtop_bundled.so",
        )
        assertFalse(cmd.contains("libhtop_bundled.so"))
        assertFalse(cmd.contains("exec \"\$TOP\""))
        assertTrue(cmd.contains("exec \"\$HTOP\""))
        assertTrue(cmd.contains("exit 127"))
    }

    @Test
    fun testBuildCommandBundledHtopModeRunsBundledWithTmpFallback() {
        val bundledPath = "/data/app/com.andrewchqng.htop/lib/arm64/libhtop_bundled.so"
        val nonRoot = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
            extraArgs = listOf("-d", "5"),
            mode = MonitorMode.BUNDLED_HTOP,
            bundledHtopPath = bundledPath,
        )
        assertFalse(nonRoot.contains("/system_ext/bin/htop"))
        assertTrue(nonRoot.contains("BUNDLED='$bundledPath'"))
        assertTrue(nonRoot.contains("/data/local/tmp/.android-htop-bundled"))
        assertTrue(nonRoot.contains("exec \"\$BUNDLED\" '-d' '5'"))
        assertTrue(nonRoot.contains("exec \"\$TMP_HTOP\" '-d' '5'"))
        assertFalse(nonRoot.contains("su -i -c"))

        val root = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = true,
            mode = MonitorMode.BUNDLED_HTOP,
            bundledHtopPath = bundledPath,
        )
        assertTrue(root.contains("libhtop_bundled.so"))
        assertTrue(root.contains("exec \"\$SU\" -i -c \"\$CMD\""))
    }

    @Test
    fun testBuildCommandBundledHtopModeMissingPathExits127() {
        val cmdNull = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
            mode = MonitorMode.BUNDLED_HTOP,
            bundledHtopPath = null,
        )
        assertTrue(cmdNull.contains("exit 127"))
        assertFalse(cmdNull.contains("exec \"\$BUNDLED\""))

        val cmdBlank = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
            mode = MonitorMode.BUNDLED_HTOP,
            bundledHtopPath = "  ",
        )
        assertTrue(cmdBlank.contains("exit 127"))
    }

    @Test
    fun testBuildEnvironmentSetsExpectedVars() {
        val env = HtopCommandBuilder.buildEnvironment("/tmp/home", "/tmp/cfg")
        val map = env.associate { entry ->
            val idx = entry.indexOf('=')
            entry.substring(0, idx) to entry.substring(idx + 1)
        }
        assertEquals("xterm-256color", map["TERM"])
        assertEquals("C.UTF-8", map["LANG"])
        assertEquals("/tmp/home", map["HOME"])
        assertEquals("/tmp/cfg", map["XDG_CONFIG_HOME"])
        assertTrue(map["PATH"]!!.contains("/product/bin"))
    }
}

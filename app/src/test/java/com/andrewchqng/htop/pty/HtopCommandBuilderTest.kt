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
    fun testBuildCommandNonRootSkipsSu() {
        val cmd = HtopCommandBuilder.buildCommand(
            configDir = "/data/data/pkg/files/config",
            homeDir = "/data/data/pkg/files",
            asRoot = false,
        )
        assertTrue(cmd.contains("exec \"\$HTOP\""))
        assertFalse(cmd.contains("su -i -c"))
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

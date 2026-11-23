package com.andrewchqng.htop.pty

enum class MonitorMode(val label: String, val badge: String) {
    HTOP("htop", "HTOP"),
    BUNDLED_HTOP("htop (bundled)", "BND"),
    TOP("top", "TOP"),
}

object HtopCommandBuilder {

    private const val EXTENDED_PATH =
        "/product/bin:/debug_ramdisk:/system_ext/bin:/system/bin:/system/xbin:/sbin:/su/bin:/vendor/bin"

    fun shellQuoteSingle(arg: String): String {
        val sb = StringBuilder(arg.length + 8)
        sb.append('\'')
        for (ch in arg) {
            if (ch == '\'') {
                sb.append("'\\''")
            } else {
                sb.append(ch)
            }
        }
        sb.append('\'')
        return sb.toString()
    }

    private fun StringBuilder.appendQuotedArgs(args: List<String>) {
        for (arg in args) {
            append(' ').append(shellQuoteSingle(arg))
        }
    }

    private fun StringBuilder.appendTopLookupAndExec(notFoundMsg: String, args: List<String>) {
        append("TOP=\$(command -v top 2>/dev/null); ")
        append("if [ -z \"\$TOP\" ]; then ")
        append("for p in /system/bin/top /system/xbin/top /vendor/bin/top /data/data/com.termux/files/usr/bin/top; do ")
        append("if [ -x \"\$p\" ]; then TOP=\"\$p\"; break; fi; ")
        append("done; ")
        append("fi; ")
        append("if [ -z \"\$TOP\" ]; then echo ").append(shellQuoteSingle(notFoundMsg)).append(" >&2; exit 127; fi; ")
        append("exec \"\$TOP\" -d 1")
        appendQuotedArgs(args)
    }

    fun buildCommand(
        configDir: String,
        homeDir: String,
        asRoot: Boolean,
        extraArgs: List<String> = emptyList(),
        mode: MonitorMode = MonitorMode.HTOP,
        bundledHtopPath: String? = null,
    ): String {
        val quotedConfig = shellQuoteSingle(configDir)
        val quotedHome = shellQuoteSingle(homeDir)

        val inner = StringBuilder().apply {
            append("export TERM=xterm-256color; ")
            append("export COLORTERM=truecolor; ")
            append("export LANG=C.UTF-8; ")
            append("export LC_ALL=C.UTF-8; ")
            append("for d in /system_ext/etc/terminfo /system/etc/terminfo /etc/terminfo /data/data/com.termux/files/usr/share/terminfo; do ")
            append("if [ -d \"\$d\" ]; then export TERMINFO=\"\$d\"; break; fi; ")
            append("done; ")
            append("mkdir -p ").append(quotedConfig).append(" ").append(shellQuoteSingle("$configDir/htop")).append(" 2>/dev/null || true; ")
            append("export XDG_CONFIG_HOME=").append(quotedConfig).append("; ")
            append("export HOME=").append(quotedHome).append("; ")

            when (mode) {
                MonitorMode.HTOP -> {
                    append("HTOP=\$(command -v htop 2>/dev/null); ")
                    append("if [ -z \"\$HTOP\" ]; then ")
                    append("for p in /system_ext/bin/htop /system/bin/htop /system/xbin/htop /data/local/tmp/htop /data/data/com.termux/files/usr/bin/htop; do ")
                    append("if [ -x \"\$p\" ]; then HTOP=\"\$p\"; break; fi; ")
                    append("done; ")
                    append("fi; ")
                    append("if [ -n \"\$HTOP\" ]; then ")
                    append("case \"\$HTOP\" in /data/data/com.termux/*) ")
                    append("export LD_LIBRARY_PATH=\"/data/data/com.termux/files/usr/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"; ")
                    append("if [ -d /data/data/com.termux/files/usr/share/terminfo ]; then ")
                    append("export TERMINFO=/data/data/com.termux/files/usr/share/terminfo; ")
                    append("fi;; ")
                    append("esac; ")
                    append("exec \"\$HTOP\"")
                    appendQuotedArgs(extraArgs)
                    append("; fi; ")
                    append("echo 'Error: system htop binary not found on device.' >&2; exit 127")
                }
                MonitorMode.BUNDLED_HTOP -> {
                    if (bundledHtopPath.isNullOrBlank()) {
                        append("echo 'Error: bundled htop binary is unavailable for this device ABI.' >&2; exit 127")
                    } else {
                        append("BUNDLED=").append(shellQuoteSingle(bundledHtopPath)).append("; ")
                        append("if [ -r \"\$BUNDLED\" ]; then ")
                        append("if [ -x \"\$BUNDLED\" ] && \"\$BUNDLED\" --version >/dev/null 2>&1; then ")
                        append("exec \"\$BUNDLED\"")
                        appendQuotedArgs(extraArgs)
                        append("; elif [ -w /data/local/tmp ]; then ")
                        append("TMP_HTOP=/data/local/tmp/.android-htop-bundled; ")
                        append("if ! cmp -s \"\$BUNDLED\" \"\$TMP_HTOP\" 2>/dev/null; then ")
                        append("cp \"\$BUNDLED\" \"\$TMP_HTOP.new.\$\$\" && chmod 700 \"\$TMP_HTOP.new.\$\$\" && mv -f \"\$TMP_HTOP.new.\$\$\" \"\$TMP_HTOP\"; ")
                        append("fi; ")
                        append("if [ -x \"\$TMP_HTOP\" ]; then exec \"\$TMP_HTOP\"")
                        appendQuotedArgs(extraArgs)
                        append("; fi; ")
                        append("fi; fi; ")
                        append("echo 'Error: failed to execute bundled htop binary.' >&2; exit 127")
                    }
                }
                MonitorMode.TOP -> {
                    appendTopLookupAndExec("Error: top binary not found on device.", extraArgs)
                }
            }
        }.toString()

        if (!asRoot) {
            return inner
        }

        val quotedInner = shellQuoteSingle(inner)
        return buildString {
            append("export PATH=\"").append(EXTENDED_PATH).append(":\${PATH}\"; ")
            append("CMD=").append(quotedInner).append("; ")
            append("if [ \"\$(id -u)\" = \"0\" ]; then eval \"\$CMD\"; ")
            append("else ")
            append("SU=\$(command -v su 2>/dev/null); ")
            append("if [ -z \"\$SU\" ]; then ")
            append("for p in /product/bin/su /debug_ramdisk/su /system/bin/su /system/xbin/su /sbin/su /su/bin/su; do ")
            append("if [ -x \"\$p\" ]; then SU=\"\$p\"; break; fi; ")
            append("done; ")
            append("fi; ")
            append("if [ -z \"\$SU\" ]; then echo \"Error: su binary not found on device.\" >&2; exit 126; fi; ")
            append("if \"\$SU\" --help 2>&1 | grep -q -- \"-i\"; then ")
            append("stty raw -echo 2>/dev/null || true; ")
            append("exec \"\$SU\" -i -c \"\$CMD\"; ")
            append("else ")
            append("exec \"\$SU\" -c \"\$CMD\"; ")
            append("fi; ")
            append("fi")
        }
    }

    fun buildEnvironment(homeDir: String, configDir: String): Array<String> {
        val env = LinkedHashMap<String, String>(System.getenv())
        val currentPath = env["PATH"].orEmpty()
        env["PATH"] = if (currentPath.isEmpty()) EXTENDED_PATH else "$EXTENDED_PATH:$currentPath"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["HOME"] = homeDir
        env["XDG_CONFIG_HOME"] = configDir
        if (env["TERMINFO"].isNullOrEmpty()) {
            env["TERMINFO"] = "/system_ext/etc/terminfo"
        }
        return env.entries.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}

# Android htop

Lightweight native Android application that launches the device's local `htop` binary as `root` inside a native pseudo-terminal (PTY) and passes through touch, hardware mouse/scroll wheel, hardware keyboard, virtual keyboard (IME), and a compact extra-keys toolbar directly to the LineageOS `htop` TUI.

## Architecture & Features

- **Native PTY Bridge ([`pty.c`](app/src/main/cpp/pty.c), [`PtyNative.kt`](app/src/main/java/com/andrewchqng/htop/pty/PtyNative.kt), [`PtySession.kt`](app/src/main/java/com/andrewchqng/htop/pty/PtySession.kt))**:
  - Opens `/dev/ptmx` with `TIOCSWINSZ`, `IUTF8`, and clean FD/signal hygiene before `execve("/system/bin/sh", ...)`.
  - Runs dedicated background reader, writer, and process-waiter threads so UI/touch callbacks never block the main thread.
- **Root `htop` Launcher ([`HtopCommandBuilder.kt`](app/src/main/java/com/andrewchqng/htop/pty/HtopCommandBuilder.kt))**:
  - Sets `TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=C.UTF-8`, auto-detects device `TERMINFO` (e.g., `/system_ext/etc/terminfo`), and points `XDG_CONFIG_HOME` to app-private `files/config` so `htoprc` persists across runs.
  - Uses `stty raw -echo` + `su -i -c` on MagiskSU (falling back to `su -c`) so `SIGWINCH` window resizes and raw input sequences propagate cleanly to root `htop`.
- **VT100 / xterm-256color Emulator & Canvas Renderer ([`TerminalEmulator.kt`](app/src/main/java/com/andrewchqng/htop/terminal/TerminalEmulator.kt), [`TerminalBuffer.kt`](app/src/main/java/com/andrewchqng/htop/terminal/TerminalBuffer.kt), [`TerminalView.kt`](app/src/main/java/com/andrewchqng/htop/ui/TerminalView.kt))**:
  - Supports alternate screen (`?1049h`/`l`), SGR 16/256/24-bit colors, DEC line-drawing & UTF-8, scroll regions (`DECSTBM`), `DECCKM` (`?1h`/`l`), and SGR 1006 / X10 mouse tracking (`?1006;1000h`).
  - Lock-free `Canvas` snapshot rendering with zero per-frame allocations.
- **Full Input Passthrough ([`InputEncoder.kt`](app/src/main/java/com/andrewchqng/htop/ui/InputEncoder.kt), [`ExtraKeysBar.kt`](app/src/main/java/com/andrewchqng/htop/ui/ExtraKeysBar.kt))**:
  - **Touch**: tap rows, column headers, or `F1`–`F10` footer buttons (`SGR 1006` press+release); vertical drag scrolls the list (`wheel 64/65`); horizontal drag pans wide command lines (`Left`/`Right` arrows); two-finger pinch zooms font size (`7sp`–`28sp`) with live `SIGWINCH` reflow.
  - **Hardware Mouse**: left/middle/right clicks, drag tracking, and vertical/horizontal scroll wheel (`AXIS_VSCROLL`/`AXIS_HSCROLL`).
  - **Keyboard & IME**: hardware keyboard (`F1`–`F12`, arrows, `Ctrl` combos, `Alt` combos, `Shift+Tab`), virtual IME (`BaseInputConnection`), and an extra-keys toolbar (`KB`, `ESC`, `TAB`, sticky `CTRL`, repeatable arrows, `F1`–`F10`, `A-`/`A+`, `↻`).

## Build & Install

Requirements: Android SDK (`compileSdk 36`, NDK `28.2.13676358`, CMake `3.22.1`), JDK 21, `adb`.

```bash
JAVA_HOME=/home/roboy/.jdks/jdk-21 ./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.andrewchqng.htop/.MainActivity
```

### Release Signing

Release builds use APK Signature Scheme v2/v3 (PKCS12, RSA 4096-bit, `isMinifyEnabled = true`, `isShrinkResources = true`, no Google dependency metadata blobs). Copy [`keystore.properties.example`](keystore.properties.example) to a gitignored `keystore.properties` file or provide environment variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), then run:

```bash
JAVA_HOME=/home/roboy/.jdks/jdk-21 ./gradlew testDebugUnitTest assembleRelease
```

---

*Built with assistance from Antigravity (Google DeepMind). Experimental root system utility — behavior depends on your ROM's `htop` binary, `terminfo` database, and `su`/SELinux policy; verify on your target device before relying on it in production workflows.*

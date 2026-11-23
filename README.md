# android-htop-gui

Lightweight native Android application that launches `htop` inside a native pseudo-terminal (PTY) — via root (`su`), [Shizuku](https://shizuku.rikka.app/), or non-root — and passes through touch, hardware mouse/scroll wheel, hardware keyboard, virtual keyboard (IME), and a compact extra-keys toolbar directly to the TUI.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.andrewchqng.htop%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FAndrewChqng%2Fandroid-htop-gui%22%2C%22author%22%3A%22AndrewChqng%22%2C%22name%22%3A%22Android%20htop%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="56"></a>

![Android htop running as root on LineageOS](docs/screenshots/htop-root.png)

## Requirements & Execution Modes

- **Root Access (`su`), Shizuku Mode, or Non-Root (Manual Selection)**: Select the execution backend manually via the leading **`ROOT`** / **`SHZ`** / **`USER`** pill at the start of the extra-keys toolbar or from the exit banner (no automatic fallback):
  - **Root (`su`)**: MagiskSU / KernelSU with full `/proc` visibility and process signaling (`F7`/`F8`/`F9`). Uses `stty raw -echo` + `su -i -c` on MagiskSU for live `SIGWINCH` resize propagation (with `su -c` support).
  - **Shizuku Mode**: Runs the native `/dev/ptmx` PTY inside a [Shizuku](https://shizuku.rikka.app/) `UserService` (`ShizukuPtyService`) using Shizuku's privileged `shell` (UID `2000`, `readproc`) or root context.
  - **Non-Root (`USER`)**: Standard unprivileged app UID context.
- **LineageOS / System `htop`, Bundled `htop`, or System `top` (Manual Selection)**: Choose the monitor binary via the **`HTOP`** / **`BND`** / **`TOP`** pill at the start of the extra-keys toolbar or from the exit banner (no automatic fallback between modes):
  - **System `htop` (`HTOP`, default)**: Launches `/system_ext/bin/htop`, `/system/bin/htop`, `/system/xbin/htop`, `/data/local/tmp/htop`, or Termux `htop`.
  - **Bundled `htop` (`BND`, [`arter97/android-htop`](https://github.com/arter97/android-htop) 3.5.3)**: Runs the self-contained `libhtop_bundled.so` binary packaged via `jniLibs` (statically linked against `ncurses` with compiled-in fallback terminfo entries, no `INTERNET` permission required).
  - **System `top` (`TOP`)**: Launches Android's built-in `/system/bin/top -d 1`.

## Screenshots

| Root Mode (`htop` on LineageOS) | Shizuku Mode (`F5` Tree View, No Root) |
| :---: | :---: |
| ![Root Mode htop](docs/screenshots/htop-root.png) | ![Shizuku Mode F5 Tree View](docs/screenshots/htop-tree-shizuku.png) |
| **`ROOT`** — native `/system_ext/bin/htop` with full `/proc` visibility and extra-keys bar | **`SHZ`** — Shizuku `UserService` PTY + `F5` process tree view without root |

| Interactive `F3` Search | Built-in `top` Fallback (`⇄top` / `⇄htop`) |
| :---: | :---: |
| ![Interactive F3 Search](docs/screenshots/htop-search.png) | ![Shizuku Mode top Fallback](docs/screenshots/top-shizuku.png) |
| Live **`F3` Search** (`F4` filter, `F6` sort, pinch-zoom & touch scroll/tap) | Automatic **`top -d 1`** fallback and one-tap live mode switching |

## Architecture & Features

- **Native PTY Bridge ([`pty.c`](app/src/main/cpp/pty.c), [`PtyNative.kt`](app/src/main/java/com/andrewchqng/htop/pty/PtyNative.kt), [`PtySession.kt`](app/src/main/java/com/andrewchqng/htop/pty/PtySession.kt))**:
  - Opens `/dev/ptmx` with `TIOCSWINSZ`, `IUTF8`, and clean FD/signal hygiene before `execve("/system/bin/sh", ...)`.
  - Runs dedicated background reader, writer, and process-waiter threads so UI/touch callbacks never block the main thread.
- **Process Monitor Modes & Command Builder ([`HtopCommandBuilder.kt`](app/src/main/java/com/andrewchqng/htop/pty/HtopCommandBuilder.kt))**:
  - Sets `TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=C.UTF-8`, auto-detects device `TERMINFO` (e.g., `/system_ext/etc/terminfo`), and points `XDG_CONFIG_HOME` to app-private `files/config` (or `/data/local/tmp/.android-htop-config` under Shizuku) so `htoprc` persists across runs.
  - Supports **System `htop`** (`HTOP`), **Bundled `htop`** (`BND`, from `nativeLibraryDir/libhtop_bundled.so` with `/data/local/tmp/.android-htop-bundled` fallback for strict SELinux `shell` contexts), and **System `top`** (`TOP`), without automatic cross-mode fallback.
  - Uses `stty raw -echo` + `su -i -c` on MagiskSU (falling back to `su -c`) so `SIGWINCH` window resizes and raw input sequences propagate cleanly.
- **VT100 / xterm-256color Emulator & Canvas Renderer ([`TerminalEmulator.kt`](app/src/main/java/com/andrewchqng/htop/terminal/TerminalEmulator.kt), [`TerminalBuffer.kt`](app/src/main/java/com/andrewchqng/htop/terminal/TerminalBuffer.kt), [`TerminalView.kt`](app/src/main/java/com/andrewchqng/htop/ui/TerminalView.kt))**:
  - Supports alternate screen (`?1049h`/`l`), SGR 16/256/24-bit colors, DEC line-drawing & UTF-8, scroll regions (`DECSTBM`), `DECCKM` (`?1h`/`l`), and SGR 1006 / X10 mouse tracking (`?1006;1000h`).
  - Lock-free `Canvas` snapshot rendering with zero per-frame allocations.
- **Full Input Passthrough ([`InputEncoder.kt`](app/src/main/java/com/andrewchqng/htop/ui/InputEncoder.kt), [`ExtraKeysBar.kt`](app/src/main/java/com/andrewchqng/htop/ui/ExtraKeysBar.kt))**:
  - **Touch**: tap rows, column headers, or `F1`–`F10` footer buttons (`SGR 1006` press+release); vertical drag scrolls the list (`wheel 64/65`); horizontal drag pans wide command lines (`Left`/`Right` arrows); two-finger pinch zooms font size (`7sp`–`28sp`) with live `SIGWINCH` reflow.
  - **Hardware Mouse**: left/middle/right clicks, drag tracking, and vertical/horizontal scroll wheel (`AXIS_VSCROLL`/`AXIS_HSCROLL`).
  - **Keyboard & IME**: hardware keyboard (`F1`–`F12`, arrows, `Ctrl` combos, `Alt` combos, `Shift+Tab`), virtual IME (`BaseInputConnection`), and an extra-keys toolbar (`ROOT`/`SHZ`/`USER`, `HTOP`/`BND`/`TOP`, `KB`, `ESC`, `TAB`, sticky `CTRL`, repeatable arrows, `F1`–`F10`, `A-`/`A+`, `↻`).

## Rebuilding & Auto-Updating Bundled `htop`

[`arter97/android-htop`](https://github.com/arter97/android-htop) is mounted as a Git submodule at `third_party/android-htop` and invoked through the wrapper [`scripts/build-bundled-htop.sh`](scripts/build-bundled-htop.sh) (which builds `arm64-v8a`, `armeabi-v7a`, and `x86_64`, verifies 16 KB ELF page alignment, installs `app/src/main/jniLibs/<abi>/libhtop_bundled.so`, and writes [`third_party/VERSIONS.txt`](third_party/VERSIONS.txt)).

- **Automatic Updates**: [`.github/workflows/update-bundled-htop.yml`](.github/workflows/update-bundled-htop.yml) runs daily (and on `workflow_dispatch`) to sync the `arter97/android-htop` submodule and upstream `htop`/`ncurses` releases, rebuild `jniLibs`, verify unit tests, and commit updates to `main`.
- **Local Rebuild**:

```bash
ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.2.13676358 \
  ./scripts/build-bundled-htop.sh
```

## Build & Install

Requirements: Android SDK (`compileSdk 36`, NDK `28.2.13676358`, CMake `3.22.1`), JDK 21, `adb`.

Builds produce per-ABI split APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`), a combined `universal` APK, and a bundletool APK Set archive (`app-<buildType>.apks`) under `app/build/outputs/apk/<buildType>/`:

```bash
JAVA_HOME=/home/roboy/.jdks/jdk-21 ./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell am start -n com.andrewchqng.htop/.MainActivity
```

### Release Signing

Release builds use APK Signature Scheme v2/v3 (PKCS12, RSA 4096-bit, `isMinifyEnabled = true`, `isShrinkResources = true`, no Google dependency metadata blobs). Copy [`keystore.properties.example`](keystore.properties.example) to a gitignored `keystore.properties` file or provide environment variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), then run:

```bash
JAVA_HOME=/home/roboy/.jdks/jdk-21 ./gradlew testDebugUnitTest assembleRelease
```

## Credits & License Notices

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for full open-source attributions (`arter97/android-htop`, `htop-dev/htop` GPL-2.0-only, GNU `ncurses`, and `Shizuku`).

---

*Built with assistance from Antigravity (Google DeepMind). Experimental root/Shizuku system utility — behavior depends on your device/ROM `htop` or bundled `htop` binary, SELinux policy, and `su`/Shizuku permissions; verify on your target device before relying on it in production workflows.*

# Third-Party Notices

This project includes or adapts third-party open-source components:

## android-htop-gui
- **License**: GNU General Public License v3.0 or later (`GPL-3.0-or-later`)
- **Component**: Original application source code
- **Copyright**: Andrew Chang and contributors

## arter97/android-htop
- **Repository**: <https://github.com/arter97/android-htop>
- **Component**: Git submodule at `third_party/android-htop`, invoked via [`scripts/build-bundled-htop.sh`](scripts/build-bundled-htop.sh)
- **Usage**: Cross-compilation workflow and compiled-in fallback terminfo strategy for self-contained Android `htop` binaries.

## htop
- **Repository**: <https://github.com/htop-dev/htop>
- **License**: GNU General Public License v2.0 only (`GPL-2.0-only`)
- **Component**: Bundled standalone executable `app/src/main/jniLibs/<abi>/libhtop_bundled.so`
- **Usage**: Launched as a separate subprocess via native PTY `execve` when the user explicitly chooses **Bundled htop (`BND`)**. Exact build configuration and source reproduction steps are provided in [`scripts/build-bundled-htop.sh`](scripts/build-bundled-htop.sh) and [`third_party/VERSIONS.txt`](third_party/VERSIONS.txt).

## GNU ncurses
- **Project**: <https://invisible-island.net/ncurses/> / <https://www.gnu.org/software/ncurses/>
- **License**: MIT/X11-style permissive license
- **Component**: Statically linked (`libncursesw.a` with `--disable-database` and compiled-in fallback terminfo entries) inside `libhtop_bundled.so`.

## Shizuku
- **Repository**: <https://github.com/RikkaApps/Shizuku-API>
- **License**: Apache License 2.0
- **Component**: `dev.rikka.shizuku:api` & `dev.rikka.shizuku:provider` (`13.1.5`).

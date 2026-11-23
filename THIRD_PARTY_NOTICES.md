# Third-Party Notices

This project includes or adapts third-party open-source components:

## arter97/android-htop
- **Repository**: <https://github.com/arter97/android-htop>
- **Component**: [`third_party/android-htop/build.sh`](third_party/android-htop/build.sh)
- **Usage**: Cross-compilation workflow and compiled-in fallback terminfo strategy for self-contained Android `htop` binaries.

## htop
- **Repository**: <https://github.com/htop-dev/htop>
- **License**: GNU General Public License v2.0 only (`GPL-2.0-only`)
- **Component**: Bundled standalone executable `app/src/main/jniLibs/<abi>/libhtop_bundled.so`
- **Usage**: Launched as a separate subprocess via native PTY `execve` when the user explicitly chooses **Bundled htop (`BND`)**. Exact build configuration and source reproduction steps are provided in [`third_party/android-htop/build.sh`](third_party/android-htop/build.sh) and [`third_party/android-htop/VERSIONS.txt`](third_party/android-htop/VERSIONS.txt).

## GNU ncurses
- **Project**: <https://invisible-island.net/ncurses/> / <https://www.gnu.org/software/ncurses/>
- **License**: MIT/X11-style permissive license
- **Component**: Statically linked (`libncursesw.a` with `--disable-database` and compiled-in fallback terminfo entries) inside `libhtop_bundled.so`.

## Shizuku
- **Repository**: <https://github.com/RikkaApps/Shizuku-API>
- **License**: Apache License 2.0
- **Component**: `dev.rikka.shizuku:api` & `dev.rikka.shizuku:provider` (`13.1.5`).

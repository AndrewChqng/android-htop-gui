#!/usr/bin/env bash
#
# Wrapper around the third_party/android-htop (arter97/android-htop) git submodule.
# Builds self-contained htop binaries for all app ABIs (arm64-v8a, armeabi-v7a, x86_64),
# verifies 16 KB ELF LOAD alignment on 64-bit ABIs, installs them into
# app/src/main/jniLibs/<abi>/libhtop_bundled.so, and records build metadata in
# third_party/VERSIONS.txt while keeping the submodule working tree clean.
#
# Offline / F-Droid build support:
#   - Defaults HTOP_VERSION and NCURSES_VERSION from third_party/VERSIONS.txt so
#     resolve_versions() does not need network access.
#   - Accepts NCURSES_SRC_DIR and HTOP_SRC_DIR (e.g. from F-Droid srclibs) or
#     pre-populated third_party/android-htop/build/{ncurses-*,htop-*} directories,
#     runs autogen.sh/autoreconf when ./configure is absent (git checkouts), and
#     touches archive markers so third_party/android-htop/build.sh performs zero
#     network downloads.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_DIR="$ROOT_DIR/third_party/android-htop"
WORK_DIR="${WORK:-$SUBMODULE_DIR/build}"
JNI_TARGET_DIR="$ROOT_DIR/app/src/main/jniLibs"
VERSIONS_FILE="$ROOT_DIR/third_party/VERSIONS.txt"

PASSTHROUGH_ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --clean-prebuilt)
      rm -f "$JNI_TARGET_DIR"/*/libhtop_bundled.so
      shift
      ;;
    --jni-dir)
      JNI_TARGET_DIR="$2"
      shift 2
      ;;
    --ncurses-src)
      NCURSES_SRC_DIR="$2"
      shift 2
      ;;
    --htop-src)
      HTOP_SRC_DIR="$2"
      shift 2
      ;;
    *)
      PASSTHROUGH_ARGS+=("$1")
      shift
      ;;
  esac
done

export ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
export API="${API:-26}"
export WORK="$WORK_DIR"

# Default pinned versions from third_party/VERSIONS.txt so offline builds never query the network.
if [ -z "${NCURSES_VERSION:-}" ] && [ -f "$VERSIONS_FILE" ]; then
  NCURSES_VERSION="$(sed -n 's/^ncurses=//p' "$VERSIONS_FILE" | head -n1)"
  export NCURSES_VERSION
fi
if [ -z "${HTOP_VERSION:-}" ] && [ -f "$VERSIONS_FILE" ]; then
  HTOP_VERSION="$(sed -n 's/^htop=//p' "$VERSIONS_FILE" | head -n1)"
  export HTOP_VERSION
fi

# Auto-detect ANDROID_NDK_HOME from ANDROID_HOME / ANDROID_SDK_ROOT if unset (e.g. on F-Droid).
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
  else
    SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
      SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n1)"
    fi
    if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/ndk" ]; then
      LATEST_LOCAL_NDK="$(find "$SDK_DIR/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
      if [ -n "$LATEST_LOCAL_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_LOCAL_NDK"
      fi
    fi
  fi
fi

if [ ! -x "$SUBMODULE_DIR/build.sh" ]; then
  echo "Submodule not initialized at $SUBMODULE_DIR; running git submodule update --init"
  git -C "$ROOT_DIR" submodule update --init --recursive
fi

mkdir -p "$WORK_DIR"

# Stage offline ncurses source directory if provided (e.g. F-Droid srclibs).
if [ -n "${NCURSES_SRC_DIR:-}" ]; then
  [ -d "$NCURSES_SRC_DIR" ] || { echo "NCURSES_SRC_DIR does not exist: $NCURSES_SRC_DIR" >&2; exit 1; }
  if [ -z "${NCURSES_VERSION:-}" ] && [ -f "$NCURSES_SRC_DIR/VERSION" ]; then
    NCURSES_VERSION="$(awk '{print $2}' "$NCURSES_SRC_DIR/VERSION" | head -n1)"
    export NCURSES_VERSION
  fi
  target_ncurses="$WORK_DIR/ncurses-$NCURSES_VERSION"
  if [ "$(cd "$NCURSES_SRC_DIR" && pwd)" != "$(mkdir -p "$target_ncurses" && cd "$target_ncurses" && pwd)" ]; then
    rm -rf "$target_ncurses"
    cp -a "$NCURSES_SRC_DIR" "$target_ncurses"
  fi
fi

# Stage offline htop source directory if provided (e.g. F-Droid srclibs).
if [ -n "${HTOP_SRC_DIR:-}" ]; then
  [ -d "$HTOP_SRC_DIR" ] || { echo "HTOP_SRC_DIR does not exist: $HTOP_SRC_DIR" >&2; exit 1; }
  target_htop="$WORK_DIR/htop-${HTOP_VERSION#v}"
  if [ "$(cd "$HTOP_SRC_DIR" && pwd)" != "$(mkdir -p "$target_htop" && cd "$target_htop" && pwd)" ]; then
    rm -rf "$target_htop"
    cp -a "$HTOP_SRC_DIR" "$target_htop"
  fi
fi

# If unpacked source directories already exist in WORK_DIR, prepare configure (for git checkouts)
# and touch archive markers so third_party/android-htop/build.sh skips curl & tar.
if [ -n "${NCURSES_VERSION:-}" ] && [ -d "$WORK_DIR/ncurses-$NCURSES_VERSION" ]; then
  [ -f "$WORK_DIR/ncurses-$NCURSES_VERSION.tar.gz" ] || : > "$WORK_DIR/ncurses-$NCURSES_VERSION.tar.gz"
fi
if [ -n "${HTOP_VERSION:-}" ] && [ -d "$WORK_DIR/htop-${HTOP_VERSION#v}" ]; then
  htop_dir="$WORK_DIR/htop-${HTOP_VERSION#v}"
  if [ ! -x "$htop_dir/configure" ]; then
    echo "==> Running autogen.sh / autoreconf in $htop_dir"
    ( cd "$htop_dir" && if [ -x ./autogen.sh ]; then ./autogen.sh; else autoreconf -fi; fi )
  fi
  [ -f "$WORK_DIR/htop-$HTOP_VERSION.tar.xz" ] || : > "$WORK_DIR/htop-$HTOP_VERSION.tar.xz"
fi

"$SUBMODULE_DIR/build.sh" "${PASSTHROUGH_ARGS[@]}"

READELF=""
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" ]; then
  READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
elif command -v llvm-readelf >/dev/null 2>&1; then
  READELF="$(command -v llvm-readelf)"
elif command -v readelf >/dev/null 2>&1; then
  READELF="$(command -v readelf)"
fi

for abi in $ABIS; do
  bin="$SUBMODULE_DIR/dist/$abi/htop"
  [ -f "$bin" ] || { echo "Missing built binary: $bin" >&2; exit 1; }

  if [ -n "$READELF" ]; then
    case "$abi" in
      arm64-v8a|x86_64)
        if ! "$READELF" -l "$bin" | grep -q '0x4000'; then
          echo "ERROR: $abi binary is not 16 KB (0x4000) page-aligned (use Android NDK r28+)" >&2
          exit 1
        fi
        ;;
    esac
  fi

  mkdir -p "$JNI_TARGET_DIR/$abi"
  install -m 755 "$bin" "$JNI_TARGET_DIR/$abi/libhtop_bundled.so"
done

SUBMODULE_COMMIT="$(git -C "$SUBMODULE_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
{
  echo "submodule_commit=$SUBMODULE_COMMIT"
  cat "$SUBMODULE_DIR/dist/versions.txt"
} > "$VERSIONS_FILE"

echo "==> Installed bundled htop binaries into $JNI_TARGET_DIR"
cat "$VERSIONS_FILE"

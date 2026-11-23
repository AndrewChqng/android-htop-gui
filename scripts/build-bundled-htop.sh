#!/usr/bin/env bash
#
# Wrapper around the third_party/android-htop (arter97/android-htop) git submodule.
# Builds self-contained htop binaries for all app ABIs (arm64-v8a, armeabi-v7a, x86_64),
# verifies 16 KB ELF LOAD alignment on 64-bit ABIs, installs them into
# app/src/main/jniLibs/<abi>/libhtop_bundled.so, and records build metadata in
# third_party/VERSIONS.txt while keeping the submodule working tree clean.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_DIR="$ROOT_DIR/third_party/android-htop"
JNI_TARGET_DIR="${1:-$ROOT_DIR/app/src/main/jniLibs}"
VERSIONS_FILE="$ROOT_DIR/third_party/VERSIONS.txt"

export ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
export API="${API:-26}"

if [ ! -x "$SUBMODULE_DIR/build.sh" ]; then
  echo "Submodule not initialized at $SUBMODULE_DIR; running git submodule update --init"
  git -C "$ROOT_DIR" submodule update --init --recursive
fi

"$SUBMODULE_DIR/build.sh" "$@"

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

SUBMODULE_COMMIT="$(git -C "$SUBMODULE_DIR" rev-parse HEAD)"
{
  echo "submodule_commit=$SUBMODULE_COMMIT"
  cat "$SUBMODULE_DIR/dist/versions.txt"
} > "$VERSIONS_FILE"

echo "==> Installed bundled htop binaries into $JNI_TARGET_DIR"
cat "$VERSIONS_FILE"

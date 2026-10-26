#!/usr/bin/env bash
# Usage: android/scripts/build-android.sh [ndk-version]   (default 27.0.12077973)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
S0="$HERE/.."
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/${1:-27.0.12077973}}"
CMAKE="${CMAKE:-$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake}"
NINJA="$(dirname "$CMAKE")/ninja"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for abi in arm64-v8a x86_64; do
  B="$S0/build/android-$abi"
  rm -rf "$B"
  "$CMAKE" -S "$HERE" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release
  "$CMAKE" --build "$B"
  cp "$B/libshinsou_sandbox_core.so" "$B/libshinsou_sandbox_core.unstripped.so"
  "$BIN/llvm-strip" --strip-unneeded "$B/libshinsou_sandbox_core.so"
  echo "== $abi"
  ls -l "$B/libshinsou_sandbox_core.unstripped.so" "$B/libshinsou_sandbox_core.so" | awk '{print $5, $9}'
  echo "-- NEEDED"; "$BIN/llvm-readelf" -d "$B/libshinsou_sandbox_core.so" | grep NEEDED
  echo "-- undefined symbols"; "$BIN/llvm-nm" -D --undefined-only "$B/libshinsou_sandbox_core.so" | awk '{print $2}' | grep -v '^_\?JNI' | sort | tr '\n' ' '; echo
  echo "-- exported"; "$BIN/llvm-nm" -D --defined-only "$B/libshinsou_sandbox_core.so" | awk '{print $3}' | tr '\n' ' '; echo
done

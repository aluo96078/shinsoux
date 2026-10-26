#!/bin/sh
# Clean reproduction of the iOS-WASM S0 prototype: guest module, WAMR fast/classic, native
# reference and the (optional) wasm2c reference; runs the scenario tests for each.
# Usage: wasm/run-all.sh [--clean] [--bench]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
S0=$(dirname "$HERE")
B=$S0/build
if [ "${1:-}" = "--clean" ]; then
  rm -rf "$B"/wasm "$B"/wasm-fast "$B"/wasm-classic "$B"/wasm-native "$B"/wasm-w2c "$B"/wasm-fast-nothr
  shift
fi
"$HERE/build-guest.sh" "$B/wasm"
"$HERE/check-imports.sh" "$B/wasm/shinsou_sandbox.wasm"
export SW_WASM="$B/wasm/shinsou_sandbox.wasm"

build() { # name, cmake args...
  name=$1; shift
  cmake -S "$HERE" -B "$B/$name" "$@" > /dev/null
  cmake --build "$B/$name" -j8 2>&1 | grep -E "error|warning: " || true
}
build wasm-native -DSW_BACKEND=native
build wasm-fast -DWAMR_FAST_INTERP=1
build wasm-classic -DWAMR_FAST_INTERP=0 -DWAMR_BLOCK_CACHE=4096
[ -x /opt/homebrew/opt/wabt/bin/wasm2c ] && build wasm-w2c -DSW_BACKEND=w2c

status=0
run_tests() { # dir, env...
  d=$1; shift
  echo "===== $d"
  (cd "$B/$d" && env "$@" ./sw_test bootstrap.js) || status=1
}
run_tests wasm-native SW_X=1
run_tests wasm-fast SW_WASM="$SW_WASM"
# classic frames are ~5x bigger and the interpreter ~10-20x slower than fast: give it room.
run_tests wasm-classic SW_WASM="$SW_WASM" SW_WASM_STACK=33554432 SW_TIME_SCALE=20
[ -d "$B/wasm-w2c" ] && run_tests wasm-w2c SW_X=1

if [ "${1:-}" = "--bench" ]; then "$HERE/run-bench.sh"; fi
exit $status

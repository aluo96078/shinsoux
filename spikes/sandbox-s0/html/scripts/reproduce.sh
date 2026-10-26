#!/bin/sh
# Reproduces every number in html/README.md from a clean build directory.
#   usage: scripts/reproduce.sh [fuzz-seconds]   (default 30; the README run used 600)
# Needs: cmake, clang, python3, a JDK + jsoup-1.18.3.jar in the Gradle cache (golden oracle),
#        Homebrew LLVM + wasi-libc (wasm32 + libFuzzer; steps are skipped with a note if missing).
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
B=$HERE/../build
FUZZ=${1:-30}
rm -rf "$B/html" "$B/html-asan" "$B/html-golden" "$B/html-wasm" "$B/html-libfuzzer"

echo "== release build + unit tests"
cmake -S "$HERE" -B "$B/html" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B/html" -j8 >/dev/null
"$B/html/dom_test"

echo "== ASan+UBSan build: unit tests, golden run, stand-alone fuzz"
cmake -S "$HERE" -B "$B/html-asan" -DSBX_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null
cmake --build "$B/html-asan" -j8 >/dev/null
"$B/html-asan/dom_test"
"$B/html-asan/golden_run" "$HERE/golden" "$HERE/golden/corpus-selectors.txt" "$HERE/golden/extra-selectors.txt" >/dev/null
"$B/html-asan/fuzz_dom" "$FUZZ"

echo "== golden comparison against jsoup 1.18.3"
"$HERE/scripts/run-golden.sh"

echo "== wasm32-wasi compile"
if [ -x /opt/homebrew/opt/llvm/bin/clang ] && [ -d /opt/homebrew/opt/wasi-libc/share/wasi-sysroot ]; then "$HERE/scripts/build-wasm.sh"; else echo "skipped (no Homebrew LLVM / wasi-libc)"; fi

echo "== benchmark"
F=$HERE/golden/fixtures
"$B/html/bench_dom" "$F/dm5-home.html" "$F/manhuagui-home.html"
"$B/html/bench_js" "$B/html/bootstrap.js" "$F/dm5-home.html" "$F/manhuagui-home.html"

echo "== libFuzzer"
if [ -x /opt/homebrew/opt/llvm/bin/clang ]; then "$HERE/scripts/run-fuzz.sh" "$FUZZ" 2>&1 | grep -E "^Done|stat::(number_of_executed|new_units|peak)|ERROR|SUMMARY"; else echo "skipped (no clang with libFuzzer)"; fi

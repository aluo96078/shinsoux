#!/bin/sh
# Reference build with no host import: same core + QuickJS as a native executable and as a
# wasm32-wasi module for wasmtime (JIT, reference only). Usage: wasm/build-standalone.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
S0=$(dirname "$HERE")
OUT=$S0/build/wasm-standalone
LLVM=${LLVM_PREFIX:-/opt/homebrew/opt/llvm}
LLD=${LLD_PREFIX:-/opt/homebrew/opt/lld}
SYS=${WASI_SYSROOT:-$(ls -d /opt/homebrew/Cellar/wasi-libc/*/share/wasi-sysroot | tail -1)}
RT=${WASI_RT:-$(ls /opt/homebrew/Cellar/wasi-runtimes/*/share/wasi-runtimes/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a | tail -1)}
Q=$S0/third_party/quickjs
SRC="$HERE/standalone.c $S0/core/src/shinsou_sandbox_core.c $Q/dtoa.c $Q/libregexp.c $Q/libunicode.c $Q/quickjs.c"
mkdir -p "$OUT"
python3 -I "$S0/scripts/extract-bootstrap.py" "$OUT/bootstrap.js"
"$LLVM/bin/clang" -O2 -D_GNU_SOURCE -w -I"$Q" -I"$S0/core/src" $SRC -o "$OUT/standalone-native" -lm
PATH="$LLVM/bin:$LLD/bin:$PATH" clang --target=wasm32-wasip1 --sysroot="$SYS" -O2 -D_GNU_SOURCE -w -I"$Q" -I"$S0/core/src" \
  -fuse-ld=lld -Wl,-z,stack-size=4194304 -Wl,--initial-memory=33554432 -Wl,--max-memory=268435456 \
  $SRC "$RT" -o "$OUT/standalone.wasm"
echo "native:   (cd $OUT && ./standalone-native bootstrap.js)"
echo "wasmtime: (cd $OUT && wasmtime run --dir=. standalone.wasm bootstrap.js)"

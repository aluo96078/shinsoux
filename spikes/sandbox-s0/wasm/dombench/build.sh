#!/bin/sh
# Builds the lexbor benchmark three ways into build/wasm-dombench: native, WAMR (fast interpreter), wasm2c.
# Usage: wasm/dombench/build.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
S0=$(cd "$HERE/../.." && pwd)
OUT=$S0/build/wasm-dombench
LLVM=${LLVM_PREFIX:-/opt/homebrew/opt/llvm}
LLD=${LLD_PREFIX:-/opt/homebrew/opt/lld}
SYS=${WASI_SYSROOT:-$(ls -d /opt/homebrew/Cellar/wasi-libc/*/share/wasi-sysroot | tail -1)}
RT=${WASI_RT:-$(ls /opt/homebrew/Cellar/wasi-runtimes/*/share/wasi-runtimes/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a | tail -1)}
WABT=${WABT_PREFIX:-/opt/homebrew/opt/wabt}
LXB=$S0/third_party/lexbor/source
H=$S0/html/src
export PATH="$LLVM/bin:$LLD/bin:$PATH"
mkdir -p "$OUT"
SRC=""
for m in core css dom encoding html ns selectors tag; do SRC="$SRC $(find "$LXB/lexbor/$m" -name '*.c')"; done
SRC="$SRC $LXB/lexbor/ports/posix/lexbor/core/memory.c $H/sandbox_dom.c"
INC="-I$LXB -I$H"
# native
clang -O2 -w $INC $SRC "$HERE/db.c" "$HERE/db_main.c" -o "$OUT/dombench_native" -lm
# wasm module (reactor, only env.host_clock_ns imported)
clang --target=wasm32-wasip1 --sysroot="$SYS" -O2 -w -ffunction-sections -fdata-sections $INC -mexec-model=reactor -fuse-ld=lld \
  -Wl,--no-entry -Wl,--gc-sections -Wl,--strip-all -Wl,-z,stack-size=4194304 -Wl,--initial-memory=16777216 -Wl,--max-memory=1073741824 \
  $SRC "$HERE/db.c" "$RT" -o "$OUT/dombench.wasm"
"$WABT/bin/wasm-objdump" -x -j Import "$OUT/dombench.wasm" | sed -n '/Import\[/,$p'
ls -l "$OUT/dombench.wasm"
# WAMR fast interpreter (library built by the main wasm CMake project: build/wasm-fast)
WAMR=$S0/third_party/wamr
VM=$S0/build/wasm-fast/libvmlib.a
[ -f "$VM" ] || { echo "build wasm/run-all.sh first (needs build/wasm-fast/libvmlib.a)"; exit 1; }
clang -O2 -w -I"$WAMR/core/iwasm/include" "$HERE/db_wamr.c" "$VM" -o "$OUT/dombench_wamr" -lm -lpthread
# wasm2c
mkdir -p "$OUT/w2c"
"$WABT/bin/wasm2c" "$OUT/dombench.wasm" -n dombench --num-outputs 8 -o "$OUT/w2c/dombench.c"
cp "$OUT/w2c/dombench.h" "$OUT/w2c/dombench.h" 2>/dev/null || true
W2C="-DWASM_RT_USE_MMAP=0 -DWASM_RT_MEMCHECK_BOUNDS_CHECK=1 -DWASM_RT_MAX_CALL_STACK_DEPTH=100000"
clang -O2 -w $W2C -I"$OUT/w2c" -I"$WABT/include" -I"$WABT/share/wabt/wasm2c" \
  "$OUT"/w2c/dombench_*.c "$HERE/db_w2c.c" "$WABT/share/wabt/wasm2c/wasm-rt-impl.c" "$WABT/share/wabt/wasm2c/wasm-rt-mem-impl.c" \
  "$WABT/share/wabt/wasm2c/wasm-rt-exceptions-impl.c" -o "$OUT/dombench_w2c" -lpthread
echo built "$OUT"

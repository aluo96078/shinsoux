#!/bin/sh
# Builds build/wasm/shinsou_sandbox.wasm (wasm32-wasip1, reactor). Usage: wasm/build-guest.sh [out_dir]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
S0=$(dirname "$HERE")
OUT=${1:-$S0/build/wasm}
LLVM=${LLVM_PREFIX:-/opt/homebrew/opt/llvm}
LLD=${LLD_PREFIX:-/opt/homebrew/opt/lld}
SYS=${WASI_SYSROOT:-$(ls -d /opt/homebrew/Cellar/wasi-libc/*/share/wasi-sysroot | tail -1)}
RT=${WASI_RT:-$(ls /opt/homebrew/Cellar/wasi-runtimes/*/share/wasi-runtimes/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a | tail -1)}
export PATH="$LLVM/bin:$LLD/bin:$PATH"
Q=$S0/third_party/quickjs
STACK=${WASM_STACK:-2097152}      # shadow stack; JS limit (1 MiB) must stay below this
INIT_MEM=${WASM_INIT_MEM:-8388608}
MAX_MEM=${WASM_MAX_MEM:-134217728} # 128 MiB linear memory ceiling (ADR §7)
mkdir -p "$OUT/obj"
CF="--target=wasm32-wasip1 --sysroot=$SYS -O2 -D_GNU_SOURCE -I$Q -I$S0/core/src -I$HERE"
for f in dtoa libregexp libunicode quickjs; do
  clang $CF -w -c "$Q/$f.c" -o "$OUT/obj/$f.o"
done
clang $CF -Wall -Wextra -c "$S0/core/src/shinsou_sandbox_core.c" -o "$OUT/obj/core.o"
clang $CF -Wall -Wextra -c "$HERE/guest.c" -o "$OUT/obj/guest.o"
clang --target=wasm32-wasip1 --sysroot=$SYS -O2 -mexec-model=reactor -fuse-ld=lld \
  -Wl,--no-entry -Wl,--gc-sections -Wl,--strip-all \
  -Wl,-z,stack-size=$STACK -Wl,--initial-memory=$INIT_MEM -Wl,--max-memory=$MAX_MEM \
  "$OUT"/obj/*.o "$RT" -o "$OUT/shinsou_sandbox.wasm"
ls -l "$OUT/shinsou_sandbox.wasm"

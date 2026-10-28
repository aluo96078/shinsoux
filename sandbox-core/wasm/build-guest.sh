#!/bin/sh
# Builds the iOS sandbox guest (ADR-0002 §4.5): sandbox-core as wasm32-wasip1, reactor.
#   wasm/build-guest.sh <out_dir>   ->  <out_dir>/shinsou_sandbox.wasm
# Needs clang/lld with the wasm32 target, wasi-libc and the wasm32 compiler-rt builtins (Homebrew:
# llvm lld wasi-libc wasi-runtimes); override with LLVM_PREFIX, LLD_PREFIX, WASI_SYSROOT, WASI_RT.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CORE=$(dirname "$HERE")
OUT=${1:?usage: build-guest.sh <out_dir>}
LLVM=${LLVM_PREFIX:-/opt/homebrew/opt/llvm}
LLD=${LLD_PREFIX:-/opt/homebrew/opt/lld}
SYS=${WASI_SYSROOT:-$(ls -d /opt/homebrew/Cellar/wasi-libc/*/share/wasi-sysroot | tail -1)}
RT=${WASI_RT:-$(ls /opt/homebrew/Cellar/wasi-runtimes/*/share/wasi-runtimes/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a | tail -1)}
export PATH="$LLVM/bin:$LLD/bin:$PATH"
Q=$CORE/third_party/quickjs
L=$CORE/third_party/lexbor/source
# Shadow stack above the 4 MiB JS stack ceiling the driver accepts; linear memory capped at 160 MiB
# (64 MiB heap + 64 MiB DOM + lexbor slack + frame buffers; the instance may lower it further).
STACK=${WASM_STACK:-6291456}
INIT_MEM=${WASM_INIT_MEM:-16777216}
MAX_MEM=${WASM_MAX_MEM:-167772160}
rm -rf "$OUT/obj" && mkdir -p "$OUT/obj"
CF="--target=wasm32-wasip1 --sysroot=$SYS -O2 -DSSC_DRIVER_SINGLE_THREAD -DLEXBOR_STATIC -I$Q -I$CORE/src -I$L"
i=0
compile() { i=$((i + 1)); clang $CF "$@" -o "$OUT/obj/$i.o"; }
for f in dtoa libregexp libunicode quickjs; do compile -D_GNU_SOURCE -w -c "$Q/$f.c"; done
for m in core css dom encoding html ns selectors tag; do
  for f in $(find "$L/lexbor/$m" -name '*.c'); do compile -w -c "$f"; done
done
compile -w -c "$L/lexbor/ports/posix/lexbor/core/memory.c"
for f in ssc_frames ssc_core ssc_dom_js sbx_dom ssc_driver; do compile -Wall -Wextra -c "$CORE/src/$f.c"; done
python3 -I "$CORE/scripts/embed-bootstrap.py" \
  "$CORE/../composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt" "$OUT/ssc_bootstrap_js.c"
compile -c "$OUT/ssc_bootstrap_js.c"
compile -Wall -Wextra -c "$HERE/ssc_wasm_guest.c"
clang --target=wasm32-wasip1 --sysroot=$SYS -O2 -mexec-model=reactor -fuse-ld=lld \
  -Wl,--no-entry -Wl,--gc-sections -Wl,--strip-all \
  -Wl,-z,stack-size=$STACK -Wl,--initial-memory=$INIT_MEM -Wl,--max-memory=$MAX_MEM \
  "$OUT"/obj/*.o "$RT" -o "$OUT/shinsou_sandbox.wasm"
"$HERE/check-imports.sh" "$OUT/shinsou_sandbox.wasm"

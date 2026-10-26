#!/bin/sh
# Compile-only check (plus a size-measuring link) of sandbox_dom + the used lexbor modules for wasm32-wasi.
# Uses Homebrew LLVM and wasi-libc; nothing is executed.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
B=${1:-$HERE/../build/html-wasm}
LLVM=${LLVM:-/opt/homebrew/opt/llvm}
SYSROOT=${WASI_SYSROOT:-/opt/homebrew/opt/wasi-libc/share/wasi-sysroot}
CC="$LLVM/bin/clang --target=wasm32-wasip1 --sysroot=$SYSROOT"
LXB=$HERE/../third_party/lexbor/source
rm -rf "$B"; mkdir -p "$B/obj"
SRC=""
for m in core css dom encoding html ns selectors tag; do SRC="$SRC $(find "$LXB/lexbor/$m" -name '*.c')"; done
SRC="$SRC $LXB/lexbor/ports/posix/lexbor/core/memory.c $HERE/src/sandbox_dom.c"
CFLAGS="-Oz -ffunction-sections -fdata-sections -fvisibility=hidden -I$LXB -I$HERE/src -w"
for f in $SRC; do
  o=$B/obj/$(echo "$f" | sed 's#[/.]#_#g').o
  $CC $CFLAGS -c "$f" -o "$o"
done
$LLVM/bin/llvm-ar rcs "$B/libsandbox_dom_wasm32.a" "$B"/obj/*.o
ls -l "$B/libsandbox_dom_wasm32.a"
# size-measuring link: export only the sbx_dom_* API, drop everything unreachable
EXPORTS=$(grep -o 'sbx_dom_[a-z_]*(' "$HERE/src/sandbox_dom.h" | tr -d '(' | sort -u | sed 's/^/--export=/' | tr '\n' ' ')
$LLVM/bin/clang --target=wasm32-wasip1 --sysroot="$SYSROOT" -Oz -nostartfiles -fuse-ld=lld \
  -Wl,--no-entry -Wl,--gc-sections -Wl,--strip-all $(for e in $EXPORTS; do printf -- '-Wl,%s ' "$e"; done) \
  -Wl,--allow-undefined -o "$B/sandbox_dom.wasm" "$B/libsandbox_dom_wasm32.a" 2>&1 | head -20 || true
ls -l "$B/sandbox_dom.wasm" 2>/dev/null || echo "link step skipped"

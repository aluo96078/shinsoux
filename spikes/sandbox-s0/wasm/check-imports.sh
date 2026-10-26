#!/bin/sh
# Prints the import/export lists of the guest module and fails if anything but env.host_bridge /
# env.host_clock_ms is imported (no WASI file/socket/clock/random API).
set -eu
S0=$(cd "$(dirname "$0")/.." && pwd)
WASM=${1:-$S0/build/wasm/shinsou_sandbox.wasm}
OBJDUMP=${WASM_OBJDUMP:-/opt/homebrew/bin/wasm-objdump}
"$OBJDUMP" -x -j Import "$WASM" | sed -n '/Import\[/,$p'
"$OBJDUMP" -x -j Export "$WASM" | sed -n '/Export\[/,$p'
"$OBJDUMP" -x -j Memory "$WASM" | sed -n '/Memory\[/,$p'
BAD=$("$OBJDUMP" -x -j Import "$WASM" | grep '<-' | grep -v -E '<- env\.(host_bridge|host_clock_ms)$' || true)
if [ -n "$BAD" ]; then echo "UNEXPECTED IMPORTS:"; echo "$BAD"; exit 1; fi
echo "import check OK"

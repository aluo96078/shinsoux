#!/bin/sh
# Fails unless the guest imports exactly the four host functions (ADR-0002 §4.5): no WASI file,
# socket, environment or clock API may reach the module.
set -eu
WASM=${1:?usage: check-imports.sh <module.wasm>}
OBJDUMP=${WASM_OBJDUMP:-$(command -v wasm-objdump || echo /opt/homebrew/bin/wasm-objdump)}
imports=$("$OBJDUMP" -x -j Import "$WASM" | sed -n 's/.*<- \(.*\)$/\1/p' | sort | tr '\n' ' ')
expected="env.host_now_ms env.host_read env.host_timezone_offset env.host_write "
if [ "$imports" != "$expected" ]; then
  echo "unexpected guest imports: $imports (expected $expected)" >&2
  exit 1
fi
echo "guest imports: $imports($(wc -c < "$WASM" | tr -d ' ') bytes)"

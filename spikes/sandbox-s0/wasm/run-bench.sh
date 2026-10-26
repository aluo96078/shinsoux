#!/bin/sh
# Runs sw_bench for every flavour present under build/ and prints raw RESULT lines per backend.
# Usage: wasm/run-bench.sh [reps]   (run on an otherwise idle machine; classic takes ~10+ minutes)
set -eu
S0=$(cd "$(dirname "$0")/.." && pwd)
REPS=${1:-3}
for d in wasm-native wasm-w2c wasm-fast wasm-classic; do
  [ -x "$S0/build/$d/sw_bench" ] || continue
  echo "== $d"
  R=$REPS; [ "$d" = wasm-classic ] && R=1   # classic: minutes per run, one rep
  (cd "$S0/build/$d" && SW_WASM="$S0/build/wasm/shinsou_sandbox.wasm" SW_WASM_STACK=$([ "$d" = wasm-classic ] && echo 33554432 || echo 16777216) ./sw_bench bootstrap.js "$R")
done

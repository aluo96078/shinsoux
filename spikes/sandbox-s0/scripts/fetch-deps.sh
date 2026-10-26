#!/usr/bin/env bash
# Fetches pinned third-party sources into third_party/ (not committed).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party
fetch() { # name url tag
  if [ -d "third_party/$1/.git" ]; then echo "$1 already present"; return; fi
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$3" "$2" "third_party/$1"
  echo "$1 $3 $(git -C "third_party/$1" rev-parse HEAD)"
}
fetch quickjs https://github.com/quickjs-ng/quickjs.git v0.17.0
fetch wamr https://github.com/bytecodealliance/wasm-micro-runtime.git WAMR-2.4.5
fetch lexbor https://github.com/lexbor/lexbor.git v3.0.1

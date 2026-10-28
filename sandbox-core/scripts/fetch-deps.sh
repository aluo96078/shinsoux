#!/usr/bin/env bash
# Fetches the pinned third-party sources into third_party/ (not committed; ADR-0002 §16).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party
fetch() { # name url tag
  if [ -d "third_party/$1/.git" ]; then echo "$1 already present"; return; fi
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$3" "$2" "third_party/$1"
  echo "$1 $3 $(git -C "third_party/$1" rev-parse HEAD)"
}
fetch quickjs https://github.com/quickjs-ng/quickjs.git v0.17.0
fetch lexbor https://github.com/lexbor/lexbor.git v3.0.1
# The iOS sandbox's interpreter (ADR-0002 §4.5); S0 measured this version on device.
fetch wamr https://github.com/bytecodealliance/wasm-micro-runtime.git WAMR-2.4.5

# Local fixes on top of the pinned tags (each patch says why); re-running is a no-op.
apply_patch() { # name patch
  if git -C "third_party/$1" apply --reverse --check "../../$2" 2>/dev/null; then
    echo "$2 already applied"
  else
    git -C "third_party/$1" apply "../../$2"
    echo "applied $2"
  fi
}
for p in patches/quickjs-*.patch; do apply_patch quickjs "$p"; done

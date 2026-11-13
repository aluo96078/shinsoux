#!/usr/bin/env bash
# Fetches the pinned third-party sources into third_party/ (not committed; ADR-0002 §16).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party
# Each source is pinned to a commit, not only to its tag: a tag can be moved upstream, and this
# code is the sandbox's trust boundary. A checkout that is not at the pinned commit is refused.
fetch() { # name url tag commit
  if [ ! -d "third_party/$1/.git" ]; then
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$3" "$2" "third_party/$1"
  fi
  local head
  head="$(git -C "third_party/$1" rev-parse HEAD)"
  if [ "$head" != "$4" ]; then
    echo "third_party/$1 is at $head; $3 is pinned to $4." >&2
    echo "Remove third_party/$1 to fetch it again; if the upstream tag moved, do not build." >&2
    exit 1
  fi
  echo "$1 $3 $head"
}
fetch quickjs https://github.com/quickjs-ng/quickjs.git v0.17.0 6d46d07d04041b40f4f49eaa7fdebe44c314c699
fetch lexbor https://github.com/lexbor/lexbor.git v3.0.1 7e278c0188489bfce6b71ced0cc900cbb31e6244
# The iOS sandbox's interpreter (ADR-0002 §4.5); S0 measured this version on device.
fetch wamr https://github.com/bytecodealliance/wasm-micro-runtime.git WAMR-2.4.5 25bd7eb63e828e4bd242cc9b38d260b4b31c6605

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

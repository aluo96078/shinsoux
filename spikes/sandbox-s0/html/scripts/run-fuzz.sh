#!/bin/sh
# libFuzzer run with ASan+UBSan.  usage: run-fuzz.sh [seconds] (default 600)
# Needs a clang that ships libclang_rt.fuzzer (Homebrew LLVM: /opt/homebrew/opt/llvm/bin/clang).
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
B=$HERE/../build/html-libfuzzer
CLANG=${CLANG:-/opt/homebrew/opt/llvm/bin/clang}
SECS=${1:-600}
CC=$CLANG cmake -S "$HERE" -B "$B" -DSBX_LIBFUZZER=ON -DCMAKE_BUILD_TYPE=Debug -DCMAKE_C_COMPILER="$CLANG" >/dev/null
cmake --build "$B" --target fuzz_libfuzzer -j8 >/dev/null
mkdir -p "$B/corpus" "$B/crashes"
# seed corpus: 1 selector-length byte + selector + document, built from the golden fixtures
python3 -I - "$HERE/golden" "$B/corpus" <<'PY'
import sys, os
src, dst = sys.argv[1:3]
sels = [b"a[href*=comic]", b"li:nth-child(2n+1) > a", b"div:has(img):not(.x)", b"p:contains(next)", b"ul > li:eq(1)", b"> a", b"h2 + p, h3 ~ p"]
i = 0
for name in sorted(os.listdir(src + "/fixtures")):
    html = open(src + "/fixtures/" + name, "rb").read()[:60000]
    for s in sels:
        open("%s/seed%03d" % (dst, i), "wb").write(bytes([len(s)]) + s + html); i += 1
PY
"$B/fuzz_libfuzzer" "$B/corpus" -max_len=65536 -timeout=10 -rss_limit_mb=2048 -max_total_time="$SECS" -artifact_prefix="$B/crashes/" -print_final_stats=1

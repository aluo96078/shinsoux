#!/bin/sh
# Rebuilds and compares sandbox_dom against the jsoup oracle.  Output: build/html-golden/report.txt
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
B=$HERE/../build
cmake -S "$HERE" -B "$B/html" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B/html" --target golden_run -j8 >/dev/null
mkdir -p "$B/html-golden"
[ -f "$B/html-golden/expected.txt" ] || "$HERE/scripts/run-oracle.sh" "$B/html-golden"
"$B/html/golden_run" "$HERE/golden" "$HERE/golden/corpus-selectors.txt" "$HERE/golden/extra-selectors.txt" > "$B/html-golden/actual.txt"
python3 -I "$HERE/scripts/compare_golden.py" "$B/html-golden/expected.txt" "$B/html-golden/actual.txt" "$@" > "$B/html-golden/report.txt"
head -24 "$B/html-golden/report.txt"
# absUrl resolution table
[ -f "$B/html-golden/urls-expected.txt" ] || {
  JSOUP=${JSOUP_JAR:-$(find "$HOME/.gradle/caches/modules-2" -name 'jsoup-1.18.3.jar' | head -1)}
  java -cp "$JSOUP:$B/html-golden/classes" Oracle --urls "$HERE/golden/urls.txt" > "$B/html-golden/urls-expected.txt"
}
"$B/html/golden_run" --urls "$HERE/golden/urls.txt" > "$B/html-golden/urls-actual.txt"
if diff -q "$B/html-golden/urls-expected.txt" "$B/html-golden/urls-actual.txt" >/dev/null; then echo "absUrl table: all $(wc -l < "$HERE/golden/urls.txt" | tr -d ' ') cases identical"; else echo "absUrl table: DIFFERENCES"; diff "$B/html-golden/urls-expected.txt" "$B/html-golden/urls-actual.txt" || true; fi

#!/bin/sh
# Produces the jsoup 1.18.3 expectation file.  Needs a JDK and the jsoup jar from the Gradle cache.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JSOUP=${JSOUP_JAR:-$(find "$HOME/.gradle/caches/modules-2" -name 'jsoup-1.18.3.jar' | head -1)}
OUT=${1:-$HERE/../build/html-golden}
mkdir -p "$OUT/classes"
javac -cp "$JSOUP" -d "$OUT/classes" "$HERE/oracle/Oracle.java"
java -cp "$JSOUP:$OUT/classes" Oracle "$HERE/golden" "$HERE/golden/corpus-selectors.txt" "$HERE/golden/extra-selectors.txt" > "$OUT/expected.txt"
wc -l "$OUT/expected.txt"

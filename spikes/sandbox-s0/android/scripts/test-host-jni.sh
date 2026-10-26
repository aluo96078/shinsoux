#!/usr/bin/env bash
# Builds the JNI library for the host JVM and runs android/test/HostJniTest.java.
# Needs build/native/bootstrap.js from the repo-level S0 build (cmake -S spikes/sandbox-s0 -B spikes/sandbox-s0/build/native).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"; S0="$HERE/.."
B="$S0/build/android-host"; OUT="$B/classes"
rm -rf "$B"; mkdir -p "$OUT"
cmake -S "$HERE" -B "$B" -DSSC_HOST_JNI=ON -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B" >/dev/null
javac -d "$OUT" "$HERE"/src/main/java/dev/shinsou/sandbox/*.java "$HERE/test/HostJniTest.java"
java -Djava.library.path="$B" -cp "$OUT" HostJniTest "${BOOTSTRAP:-$S0/build/native/bootstrap.js}"

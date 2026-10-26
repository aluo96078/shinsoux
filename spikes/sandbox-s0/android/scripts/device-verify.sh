#!/usr/bin/env bash
# On-device S0 verification (needs a connected API 29+ device/emulator and `adb`).
# Usage: android/scripts/device-verify.sh [PIPE|SHARED_MEMORY]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.shinsou.sandbox.harness
STRAT="${1:-PIPE}"

( cd "$HERE" && gradle :harness:assembleDebug )   # also requires build/native/bootstrap.js
"$ADB" install -r "$HERE/harness/build/outputs/apk/debug/harness-debug.apk"
"$ADB" logcat -c
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" --es strategy "$STRAT"
sleep 45
echo "== harness log"; "$ADB" logcat -d -s SSC-S0:I
echo "== processes (each package should have its own pid and an isolated uid like u0i1234)"
"$ADB" shell "ps -A -o USER,PID,PPID,RSS,NAME | grep -E '$PKG'"
echo "== isolation probes (socket/open/exec must all be errno != OK)"
"$ADB" logcat -d -s SSC-S0-PROBE:I
echo "== PSS per process"
"$ADB" shell dumpsys meminfo "$PKG" | sed -n 1,40p

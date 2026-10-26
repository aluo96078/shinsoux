#!/bin/sh
# Clean build + every macOS S0 check. Usage: spikes/sandbox-s0/macos/run-all.sh   (needs cmake, python3, codesign)
set -e
S0=$(cd "$(dirname "$0")/.." && pwd)
B="$S0/build/macos"
rm -rf "$B"
cmake -S "$S0/macos" -B "$B" -DCMAKE_BUILD_TYPE=Release
cmake --build "$B"
echo "=== isolation probe"; python3 -I "$S0/macos/tests/run_probe.py" "$B"
echo "=== host scenarios (Seatbelt)"; python3 -I "$S0/macos/tests/test_helper.py" "$B" --sandbox=seatbelt
echo "=== host scenarios (no sandbox, control)"; python3 -I "$S0/macos/tests/test_helper.py" "$B" --sandbox=none
echo "=== host scenarios (App Sandbox entitlement)"; python3 -I "$S0/macos/tests/test_helper.py" "$B" --exe=shinsou-sandbox-appsandbox --sandbox=none
echo "=== bench: Seatbelt";   python3 -I "$S0/macos/tests/bench.py" "$B" --sandbox=seatbelt
echo "=== bench: App Sandbox"; python3 -I "$S0/macos/tests/bench.py" "$B" --exe=shinsou-sandbox-appsandbox --sandbox=none
echo "=== bench: no sandbox"; python3 -I "$S0/macos/tests/bench.py" "$B" --sandbox=none

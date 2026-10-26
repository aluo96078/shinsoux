#!/bin/sh
# Builds and runs the S0 benchmark on a connected iPhone (ADR-0002 §16.1).
#   ios/build.sh            build library + app, install, run both benchmarks, copy results
# Requires Xcode (DEVELOPER_DIR), a signing identity for team 5UKY38ZLK6, and the wasm/ builds:
#   build/wasm/shinsou_sandbox.wasm, build/wasm-fast/bootstrap.js, build/wasm-dombench/dombench.wasm
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
S0=$(cd "$HERE/.." && pwd)
B=$S0/build
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
BUNDLE=dev.aluo.shinsoux.s0bench

# 1. resources
R=$HERE/Resources
rm -rf "$R"; mkdir -p "$R"
cp "$B/wasm/shinsou_sandbox.wasm" "$B/wasm-fast/bootstrap.js" "$B/wasm-dombench/dombench.wasm" "$R/"
cp "$S0/html/golden/fixtures/manhuagui-home.html" "$S0/html/golden/fixtures/dm5-home.html" "$R/"

# 2. static library for iphoneos arm64
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
cmake -S "$HERE" -B "$B/ios-cmake" -DCMAKE_BUILD_TYPE=Release -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_OSX_SYSROOT="$SDK" >/dev/null
cmake --build "$B/ios-cmake" -j8 >/dev/null
mkdir -p "$B/ios-lib"; cp "$B/ios-cmake/libs0bench.a" "$B/ios-lib/"

# 3. app
(cd "$HERE" && xcodegen generate --quiet)
xcodebuild -project "$HERE/S0Bench.xcodeproj" -scheme S0Bench -configuration Release -sdk iphoneos \
  -derivedDataPath "$B/ios-dd" -allowProvisioningUpdates -quiet build
APP=$(ls -d "$B"/ios-dd/Build/Products/Release-iphoneos/S0Bench.app)
echo "APP $APP"

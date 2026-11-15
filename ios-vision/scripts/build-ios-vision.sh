#!/usr/bin/env bash
# ADR-0005 §4 (iOS): fetches ONNX Runtime and builds OpenCV for the manga translation, per Apple
# SDK, into OUT/<sdk>/ (libonnxruntime.a, libopencv2.a, opencv2.framework headers) and
# OUT/onnxruntime-headers. Sources are pinned by version and SHA-256. Re-running is a no-op.
#
# OpenCV's own iOS release has no arm64 simulator slice and carries every module, so it is built
# from source with only core, imgproc and photo (the Objective-C wrappers included).
set -euo pipefail
OUT="${1:?usage: build-ios-vision.sh OUT_DIR}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
WORK="$OUT/work"; mkdir -p "$WORK"

ORT_VERSION=1.30.0
ORT_SHA256=e6f1670c14406fd9f082bb400ab197a9b0a9646058ca6366e440642e2b54a2ea
OPENCV_VERSION=4.14.0
OPENCV_SHA256=ee8fb9b30eb60850431b4656447080e3737b56e45719c92b67f245950609f86e
DEPLOYMENT_TARGET=16.0

fetch() { # url sha256 file
  if [ ! -f "$3" ] || [ "$(shasum -a 256 "$3" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL -o "$3.part" "$1"
    [ "$(shasum -a 256 "$3.part" | cut -d' ' -f1)" = "$2" ] || { echo "checksum mismatch: $1" >&2; rm -f "$3.part"; exit 1; }
    mv "$3.part" "$3"
  fi
}

# ---- ONNX Runtime (the CocoaPods C API archive: a static xcframework) ----
if [ ! -f "$OUT/iphonesimulator/libonnxruntime.a" ]; then
  fetch "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-$ORT_VERSION.zip" "$ORT_SHA256" "$WORK/ort.zip"
  rm -rf "$WORK/ort"; mkdir -p "$WORK/ort"; unzip -q "$WORK/ort.zip" -d "$WORK/ort"
  xc="$WORK/ort/onnxruntime.xcframework"
  mkdir -p "$OUT/iphoneos" "$OUT/iphonesimulator"
  cp "$xc/ios-arm64/onnxruntime.framework/onnxruntime" "$OUT/iphoneos/libonnxruntime.a"
  lipo -thin arm64 "$xc/ios-arm64_x86_64-simulator/onnxruntime.framework/onnxruntime" -output "$OUT/iphonesimulator/libonnxruntime.a"
  rm -rf "$OUT/onnxruntime-headers"; cp -R "$WORK/ort/Headers" "$OUT/onnxruntime-headers"
fi

# ---- OpenCV (core, imgproc, photo) ----
if [ ! -f "$OUT/iphonesimulator/libopencv2.a" ]; then
  fetch "https://github.com/opencv/opencv/archive/refs/tags/$OPENCV_VERSION.tar.gz" "$OPENCV_SHA256" "$WORK/opencv.tar.gz"
  src="$WORK/opencv-$OPENCV_VERSION"
  if [ ! -d "$src" ]; then
    tar xzf "$WORK/opencv.tar.gz" -C "$WORK"
    # its build script reads the current git branch
    git -C "$src" init -q && git -C "$src" -c user.email=b@local -c user.name=build commit -q --allow-empty -m "$OPENCV_VERSION"
  fi
  without=()
  for m in calib3d dnn features2d flann gapi highgui imgcodecs ml objdetect stitching video videoio xfeatures2d; do without+=(--without "$m"); done
  # No image codecs either: OpenCV's bundled libjpeg-turbo exports the same jpeg_* symbols as the
  # one inside Skia (Compose's image decoder). Linked into one binary, only one survives, and
  # Skia then fails every JPEG ("Failed to Image::makeFromEncoded") - every cover went blank.
  without+=(--disable JPEG --disable PNG --disable TIFF --disable WEBP --disable OPENJPEG --disable JASPER --disable OPENEXR --disable AVIF --disable IMGCODEC_HDR --disable IMGCODEC_SUNRASTER --disable IMGCODEC_PXM --disable IMGCODEC_PFM)
  # CMake's Xcode generator occasionally loses a configure-time probe ("Subprocess killed"):
  # retry the whole build a few times before giving up.
  built=0
  for attempt in 1 2 3; do
    rm -rf "$WORK/opencv-build"
    if IPHONEOS_DEPLOYMENT_TARGET=$DEPLOYMENT_TARGET python3 "$src/platforms/apple/build_xcframework.py" --out "$WORK/opencv-build" \
      --iphoneos_archs arm64 --iphonesimulator_archs arm64 --build_only_specified_archs "${without[@]}" --disable-swift; then
      built=1; break
    fi
    echo "OpenCV build attempt $attempt failed; retrying" >&2
  done
  [ "$built" = 1 ] || { echo "OpenCV build failed" >&2; exit 1; }
  xc="$WORK/opencv-build/opencv2.xcframework"
  for pair in "iphoneos:ios-arm64" "iphonesimulator:ios-arm64-simulator"; do
    sdk="${pair%%:*}"; slice="${pair##*:}"
    mkdir -p "$OUT/$sdk"
    rm -rf "$OUT/$sdk/opencv2.framework"; cp -R "$xc/$slice/opencv2.framework" "$OUT/$sdk/opencv2.framework"
    cp "$xc/$slice/opencv2.framework/opencv2" "$OUT/$sdk/libopencv2.a"
  done
fi
echo "ios-vision ready in $OUT"

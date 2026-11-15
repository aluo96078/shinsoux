# iOS 的漫畫翻譯原生依賴

[ADR-0005](../docs/architecture/0005-manga-translation.md) §4：iOS 透過 cinterop 呼叫 ONNX Runtime 的 C API 與
OpenCV 的 Objective-C API。`scripts/build-ios-vision.sh` 下載固定版本（以 SHA-256 核對）的 ONNX Runtime，並從原始碼
編譯只含 core、imgproc、photo 的 OpenCV，輸出到 Gradle 的 `build/ios-vision/<sdk>/`。由 Gradle 任務
`buildIosVision` 呼叫，cinterop 任務會自動依賴它；第一次編譯 OpenCV 約需十幾分鐘，之後不再重做。

# R5-3 (docs/architecture/0006): R8 rules of the Android release variant (the published APK). The
# library modules add theirs (consumer-rules.pro next to their build scripts: JNA and libsodium in
# :core, the plugin sandbox in :feature-plugin, translation, ONNX Runtime and OpenCV in
# :feature-translation); libraries with their own rules (kotlinx.serialization, coroutines, OkHttp,
# AndroidX, Compose, Coil, AVIF) bring them in their AARs/JARs. This file is not the desktop one
# (proguard-rules.pro).

# Readable stack traces with the build's mapping.txt (a release asset, see release.yml).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

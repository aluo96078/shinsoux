# R5-3 (docs/architecture/0006): R8 rules for translation in the app's release build.

# JNI entry points of libshinsou_translate.so (translation-core/src/stc_jni.cpp):
# Java_dev_shinsou_kmp_translation_NativeTranslationCore_{create,destroy,unload,translate}, called on
# the object's instance.
-keep class dev.shinsou.kmp.translation.NativeTranslationCore {
    native <methods>;
}

# ADR-0005 (manga translation): ONNX Runtime's JNI library constructs OnnxTensor, TensorInfo,
# OnnxValue, OrtException and friends from native code and reads their fields by name. The AAR's own
# rules only keep its telemetry classes.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# OpenCV's libopencv_java4.so binds native methods and looks up org.opencv classes (Mat, CvException,
# the core types) by name. The AAR ships no rules.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

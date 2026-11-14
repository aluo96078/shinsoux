// Android binding of the translation core (ADR-0004 §4.5): dev.shinsou.kmp.translation.NativeTranslationCore.
// Text crosses as UTF-8 byte arrays: JNI's own string functions use modified UTF-8, which mangles
// characters outside the Basic Multilingual Plane.
#include <jni.h>

#include <string>
#include <vector>

#include "stc.h"

namespace {

std::string bytes(JNIEnv *env, jbyteArray array) {
    if (!array) return std::string();
    jsize length = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

void fail(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_shinsou_kmp_translation_NativeTranslationCore_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(stc_create());
}

JNIEXPORT void JNICALL Java_dev_shinsou_kmp_translation_NativeTranslationCore_destroy(JNIEnv *, jobject, jlong handle) {
    stc_destroy(reinterpret_cast<stc_engine *>(handle));
}

JNIEXPORT void JNICALL Java_dev_shinsou_kmp_translation_NativeTranslationCore_unload(JNIEnv *, jobject, jlong handle) {
    stc_unload(reinterpret_cast<stc_engine *>(handle));
}

JNIEXPORT jobjectArray JNICALL Java_dev_shinsou_kmp_translation_NativeTranslationCore_translate(
    JNIEnv *env, jobject, jlong handle, jbyteArray model, jbyteArray sourceVocab, jbyteArray targetVocab,
    jbyteArray shortlist, jobjectArray lines) {
    std::string modelPath = bytes(env, model), source = bytes(env, sourceVocab), target = bytes(env, targetVocab),
                lex = bytes(env, shortlist);
    jsize count = lines ? env->GetArrayLength(lines) : 0;
    std::vector<std::string> inputs;
    inputs.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        jbyteArray line = static_cast<jbyteArray>(env->GetObjectArrayElement(lines, i));
        inputs.push_back(bytes(env, line));
        env->DeleteLocalRef(line);
    }
    std::vector<const char *> pointers;
    pointers.reserve(inputs.size());
    for (const auto &input : inputs) pointers.push_back(input.c_str());

    stc_model paths = {modelPath.c_str(), source.c_str(), target.c_str(), lex.c_str()};
    char **outputs = nullptr;
    char *error = nullptr;
    if (stc_translate(reinterpret_cast<stc_engine *>(handle), &paths, pointers.data(), pointers.size(), &outputs, &error) != 0) {
        fail(env, error ? error : "translation failed");
        stc_free(error);
        return nullptr;
    }
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray result = byteArrayClass ? env->NewObjectArray(count, byteArrayClass, nullptr) : nullptr;
    for (jsize i = 0; result && i < count; ++i) {
        jsize length = static_cast<jsize>(std::char_traits<char>::length(outputs[i]));
        jbyteArray line = env->NewByteArray(length);
        if (!line) break;  // OutOfMemoryError is pending
        env->SetByteArrayRegion(line, 0, length, reinterpret_cast<const jbyte *>(outputs[i]));
        env->SetObjectArrayElement(result, i, line);
        env->DeleteLocalRef(line);
    }
    stc_free_lines(outputs, pointers.size());
    return result;
}

}  // extern "C"

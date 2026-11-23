#include <jni.h>

#include <string>

namespace {

constexpr char kBackendStatus[] =
    "JNI bridge ready. GGUF inference backend is not linked yet.";

constexpr char kBuildInfo[] =
    "GpTMax native foundation | C++17 | Android NDK";

jstring ToJavaString(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_backendStatus(JNIEnv* env, jobject /* thiz */) {
    return ToJavaString(env, kBackendStatus);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_nativeBuildInfo(JNIEnv* env, jobject /* thiz */) {
    return ToJavaString(env, kBuildInfo);
}

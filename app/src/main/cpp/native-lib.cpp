#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <mutex>
#include <string>

namespace {
constexpr char kLogTag[] = "GpTMaxNative";
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
bool g_backend_initialized = false;

void release_model_locked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

jstring to_java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_backendStatus(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_context != nullptr) return to_java_string(env, "GGUF model loaded; inference context ready.");
    return to_java_string(env, "llama.cpp linked; no model loaded.");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_nativeBuildInfo(JNIEnv * env, jobject) {
    return to_java_string(env, std::string("GpTMax | llama.cpp ") + llama_version() + " | Android NDK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_nativeLoadModel(JNIEnv * env, jobject, jstring model_path) {
    if (model_path == nullptr) return to_java_string(env, "Model path is empty.");

    const char * path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (path_chars == nullptr) return to_java_string(env, "Could not read model path.");
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(model_path, path_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    auto model_params = llama_model_default_params();
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        return to_java_string(env, "llama.cpp could not load this GGUF model.");
    }

    auto context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        release_model_locked();
        return to_java_string(env, "Model loaded but inference context creation failed.");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Loaded GGUF model: %s", path.c_str());
    return to_java_string(env, "MODEL_READY");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}

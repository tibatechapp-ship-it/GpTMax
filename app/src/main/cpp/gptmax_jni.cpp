// =============================================================================
// GpTMax — JNI bridge over llama.cpp (Android, arm64-v8a)
// -----------------------------------------------------------------------------
// Written against the pinned llama.cpp surface (see VENDOR.md for the pin).
// Responsibilities:
//   * load / free a GGUF model into a llama.cpp context
//   * run chat completions with incremental token streaming to Kotlin
//   * cooperative cancellation (nativeStop) driven from the UI thread
//
// Kotlin entry points live in com.tibatech.gptmax.bridge.LlamaBridge.
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "GpTMaxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int32_t n_ctx = 4096;
    int32_t n_threads = 4;
    bool loaded = false;
    std::string lastError;
};

Engine g_engine;
std::mutex g_engineMutex;   // one generation at a time — phone hardware
std::atomic<bool> g_cancelled{false};
JavaVM* g_vm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// Incremental UTF-8 assembler: llama.cpp emits partial codepoints; we only
// forward complete, valid UTF-8 sequences to the JVM callback.
// ---------------------------------------------------------------------------
class Utf8Assembler {
public:
    void feed(const std::string& piece, std::vector<std::string>& out) {
        buf_ += piece;
        size_t i = 0;
        while (i < buf_.size()) {
            const unsigned char c = static_cast<unsigned char>(buf_[i]);
            size_t need = 1;
            if ((c & 0x80) == 0) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            else { // invalid lead byte: drop it
                buf_.erase(i, 1);
                continue;
            }
            if (i + need > buf_.size()) break; // wait for more bytes
            bool valid = true;
            for (size_t k = 1; k < need; ++k) {
                const unsigned char cc = static_cast<unsigned char>(buf_[i + k]);
                if ((cc & 0xC0) != 0x80) { valid = false; break; }
            }
            if (!valid) { buf_.erase(i, need); continue; }
            out.emplace_back(buf_.substr(i, need));
            i += need;
        }
        buf_.erase(0, i);
    }

private:
    std::string buf_;
};

void emitTokens(JNIEnv* env, jobject listener, jmethodID onToken,
                const std::vector<std::string>& pieces) {
    if (listener == nullptr || onToken == nullptr) return;
    for (const auto& p : pieces) {
        jstring js = env->NewStringUTF(p.c_str());
        env->CallVoidMethod(listener, onToken, js);
        env->DeleteLocalRef(js);
    }
}

std::string jniToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* raw = env->GetStringUTFChars(s, nullptr);
    std::string out(raw != nullptr ? raw : "");
    if (raw != nullptr) env->ReleaseStringUTFChars(s, raw);
    return out;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// Load model. Returns true on success. Error details via nativeLastError().
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeInit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelPath, jint nCtx, jint nThreads, jint nGpuLayers) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine.loaded) {
        LOGI("nativeInit: model already loaded");
        return JNI_TRUE;
    }

    const std::string modelPath = jniToStd(env, jModelPath);
    if (modelPath.empty()) {
        g_engine.lastError = "model path is empty";
        return JNI_FALSE;
    }

    llama_model_params mparams = llama_model_default_params();
    // CPU-only runtime today; nGpuLayers reserved for a Vulkan backend build.
    mparams.n_gpu_layers = nGpuLayers > 0 ? nGpuLayers : 0;

    g_engine.model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (g_engine.model == nullptr) {
        g_engine.lastError = "failed to load model: " + modelPath;
        LOGE("%s", g_engine.lastError.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    g_engine.n_ctx = nCtx > 0 ? static_cast<int32_t>(nCtx) : 4096;
    g_engine.n_threads = nThreads > 0 ? static_cast<int32_t>(nThreads) : 4;
    cparams.n_ctx = static_cast<uint32_t>(g_engine.n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = g_engine.n_threads;
    cparams.n_threads_batch = g_engine.n_threads;

    g_engine.ctx = llama_init_from_model(g_engine.model, cparams);
    if (g_engine.ctx == nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
        g_engine.lastError = "failed to create llama_context";
        return JNI_FALSE;
    }

    g_engine.vocab = llama_model_get_vocab(g_engine.model);
    g_engine.loaded = true;
    g_cancelled.store(false);
    g_engine.lastError.clear();
    LOGI("model loaded: %s (ctx=%d threads=%d gpuLayers=%d)",
         modelPath.c_str(), g_engine.n_ctx, g_engine.n_threads, nGpuLayers);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// Streaming generation. Blocking; call from a background dispatcher only.
// `listener.onToken(piece)` fires for every complete UTF-8 sequence.
// Returns the full completion text.
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPrompt, jint maxTokens, jfloat temperature, jfloat topP,
        jint topK, jobject listener) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (!g_engine.loaded || g_engine.ctx == nullptr || g_engine.vocab == nullptr) {
        return env->NewStringUTF("");
    }

    jobject listenerRef = listener != nullptr ? env->NewGlobalRef(listener) : nullptr;
    jmethodID onToken = nullptr;
    if (listenerRef != nullptr) {
        jclass cls = env->GetObjectClass(listenerRef);
        onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }

    g_cancelled.store(false);
    const std::string prompt = jniToStd(env, jPrompt);

    // --- tokenize -------------------------------------------------------------
    const int32_t maxPromptTokens = g_engine.n_ctx - 8 - (maxTokens > 0 ? maxTokens : 512);
    std::vector<llama_token> tokens(std::max(64, maxPromptTokens));
    const int32_t nPrompt = llama_tokenize(
        g_engine.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (nPrompt <= 0) {
        if (listenerRef != nullptr) env->DeleteGlobalRef(listenerRef);
        return env->NewStringUTF("");
    }
    tokens.resize(static_cast<size_t>(nPrompt));

    // --- prefill ---------------------------------------------------------------
    llama_batch batch = llama_batch_get_one(tokens.data(), nPrompt);
    if (llama_decode(g_engine.ctx, batch) != 0) {
        LOGE("prefill decode failed");
        if (listenerRef != nullptr) env->DeleteGlobalRef(listenerRef);
        return env->NewStringUTF("");
    }

    // --- sampler chain ---------------------------------------------------------
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0.0f ? topP : 0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        // llama_sampler_seed() does not exist in the pin; LLAMA_DEFAULT_SEED
        // (0xFFFFFFFF) makes the dist sampler draw a random seed per chain.
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // --- decode loop -----------------------------------------------------------
    std::string full;
    Utf8Assembler assembler;
    const int32_t limit = maxTokens > 0 ? maxTokens : 512;
    std::vector<char> pieceBuf(256);

    for (int32_t i = 0; i < limit && !g_cancelled.load(); ++i) {
        // Non-const: llama_batch_get_one() takes llama_token* (not const) in the pin.
        llama_token tok = llama_sampler_sample(smpl, g_engine.ctx, -1);
        if (llama_vocab_is_eog(g_engine.vocab, tok)) break;

        // Pin API name: llama_token_to_piece (llama_vocab_token_to_piece does not exist).
        const int32_t n = llama_token_to_piece(
            g_engine.vocab, tok, pieceBuf.data(),
            static_cast<int32_t>(pieceBuf.size()), 0, true);
        if (n > 0) {
            const std::string piece(pieceBuf.data(), static_cast<size_t>(n));
            full += piece;
            std::vector<std::string> ready;
            assembler.feed(piece, ready);
            emitTokens(env, listenerRef, onToken, ready);
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_engine.ctx, next) != 0) break;
    }

    llama_sampler_free(smpl);
    if (listenerRef != nullptr) env->DeleteGlobalRef(listenerRef);
    return env->NewStringUTF(full.c_str());
}

JNIEXPORT void JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancelled.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeIsLoaded(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine.loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeLastError(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_engine.lastError.c_str());
}

// ---------------------------------------------------------------------------
// Free model + context. Safe to call when nothing is loaded.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_tibatech_gptmax_bridge_LlamaBridge_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine.ctx != nullptr) {
        llama_free(g_engine.ctx);
        g_engine.ctx = nullptr;
    }
    if (g_engine.model != nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
    }
    g_engine.vocab = nullptr;
    g_engine.loaded = false;
    LOGI("model freed");
}

} // extern "C"

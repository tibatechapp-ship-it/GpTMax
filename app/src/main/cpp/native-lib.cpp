#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include "unicode-conversion.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr char kLogTag[] = "GpTMaxNative";
std::mutex g_mutex;
std::atomic<bool> g_cancel_requested{false};
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
bool g_backend_initialized = false;

void release_model_locked() {
    if (g_context != nullptr) {
        llama_set_abort_callback(g_context, nullptr, nullptr);
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

bool abort_requested(void *) {
    return g_cancel_requested.load(std::memory_order_relaxed);
}

class ScopedJavaChars {
public:
    ScopedJavaChars(JNIEnv * env, jstring input)
        : env_(env), input_(input), chars_(env->GetStringChars(input, nullptr)) {}
    ~ScopedJavaChars() {
        if (chars_ != nullptr) env_->ReleaseStringChars(input_, chars_);
    }
    ScopedJavaChars(const ScopedJavaChars &) = delete;
    ScopedJavaChars & operator=(const ScopedJavaChars &) = delete;
    const jchar * get() const { return chars_; }
private:
    JNIEnv * env_;
    jstring input_;
    const jchar * chars_;
};

bool from_java_string(JNIEnv * env, jstring input, std::string & output) {
    if (input == nullptr) return false;
    ScopedJavaChars chars(env, input);
    if (chars.get() == nullptr) return false;
    return gptmax::unicode::utf16_to_utf8(
            reinterpret_cast<const char16_t *>(chars.get()),
            static_cast<size_t>(env->GetStringLength(input)), output);
}

jstring to_java_string(JNIEnv * env, const std::string & value) {
    std::u16string utf16;
    if (!gptmax::unicode::utf8_to_utf16(value, utf16)) return nullptr;
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size()));
}

size_t complete_utf8_prefix(const std::string & value) {
    size_t i = 0;
    size_t complete = 0;
    while (i < value.size()) {
        const auto c = static_cast<uint8_t>(value[i]);
        size_t width = c < 0x80 ? 1 : ((c & 0xe0) == 0xc0 ? 2 :
                      ((c & 0xf0) == 0xe0 ? 3 : ((c & 0xf8) == 0xf0 ? 4 : 1)));
        if (i + width > value.size()) break;
        bool valid = true;
        for (size_t j = 1; j < width; ++j) {
            if ((static_cast<uint8_t>(value[i + j]) & 0xc0) != 0x80) valid = false;
        }
        if (!valid) width = 1;
        i += width;
        complete = i;
    }
    return complete;
}

bool emit_text(JNIEnv * env, jobject callback, jmethodID method,
               const std::string & output, size_t begin, size_t end) {
    if (end <= begin) return true;
    const std::string chunk = output.substr(begin, end - begin);
    jstring text = to_java_string(env, chunk);
    if (text == nullptr) return false;
    env->CallVoidMethod(callback, method, text);
    env->DeleteLocalRef(text);
    return !env->ExceptionCheck();
}

bool decode_token(llama_context * context, llama_batch & batch,
                  llama_token token, llama_pos position) {
    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    return llama_decode(context, batch) == 0;
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

    std::string path;
    if (!from_java_string(env, model_path, path)) {
        return to_java_string(env, "Could not decode model path.");
    }

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

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gptmax_app_NativeBridge_nativeGenerate(
        JNIEnv * env, jobject, jstring prompt_text, jint requested_tokens,
        jfloat requested_temperature, jobject callback) {
    if (prompt_text == nullptr || callback == nullptr) {
        return to_java_string(env, "Invalid generation arguments.");
    }

    std::string prompt;
    if (!from_java_string(env, prompt_text, prompt)) {
        return to_java_string(env, "Could not decode prompt text.");
    }
    if (prompt.empty()) return to_java_string(env, "Prompt is empty.");
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        return to_java_string(env, "Prompt is too large.");
    }

    const jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) return to_java_string(env, "Invalid token callback.");
    const jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(callback_class);
    if (on_token == nullptr) return to_java_string(env, "Token callback method not found.");

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        return to_java_string(env, "No model is loaded.");
    }

    llama_set_abort_callback(g_context, abort_requested, nullptr);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t prompt_size = static_cast<int32_t>(prompt.size());
    int32_t token_capacity = llama_tokenize(vocab, prompt.c_str(), prompt_size,
                                             nullptr, 0, true, false);
    if (token_capacity == std::numeric_limits<int32_t>::min()) {
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Prompt tokenization overflow.");
    }
    if (token_capacity < 0) token_capacity = -token_capacity;
    if (token_capacity <= 0) {
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Prompt produced no tokens.");
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_capacity));
    const int32_t actual_tokens = llama_tokenize(vocab, prompt.c_str(), prompt_size,
            prompt_tokens.data(), token_capacity, true, false);
    if (actual_tokens <= 0 || actual_tokens > token_capacity) {
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Prompt tokenization failed.");
    }
    prompt_tokens.resize(static_cast<size_t>(actual_tokens));

    const int32_t max_tokens = std::clamp(static_cast<int32_t>(requested_tokens), 1, 1024);
    const uint32_t context_size = llama_n_ctx(g_context);
    if (prompt_tokens.size() >= context_size ||
        static_cast<uint64_t>(prompt_tokens.size()) + static_cast<uint32_t>(max_tokens) > context_size) {
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Prompt and output exceed the configured context window.");
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    const uint32_t configured_batch = llama_n_batch(g_context);
    const int32_t batch_capacity = static_cast<int32_t>(
            std::min<uint32_t>(static_cast<uint32_t>(actual_tokens),
                    std::max<uint32_t>(configured_batch, 1)));
    llama_batch batch = llama_batch_init(batch_capacity, 0, 1);
    if (batch.token == nullptr || batch.pos == nullptr || batch.n_seq_id == nullptr ||
        batch.seq_id == nullptr || batch.logits == nullptr) {
        llama_batch_free(batch);
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Could not allocate inference batch.");
    }

    int32_t prompt_decode = 0;
    for (int32_t offset = 0; offset < actual_tokens &&
            !g_cancel_requested.load(std::memory_order_relaxed); offset += batch_capacity) {
        const int32_t chunk_size = std::min(batch_capacity, actual_tokens - offset);
        batch.n_tokens = chunk_size;
        for (int32_t i = 0; i < chunk_size; ++i) {
            const int32_t position = offset + i;
            batch.token[i] = prompt_tokens[static_cast<size_t>(position)];
            batch.pos[i] = position;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (position == actual_tokens - 1) ? 1 : 0;
        }
        prompt_decode = llama_decode(g_context, batch);
        if (prompt_decode != 0) break;
    }
    if (g_cancel_requested.load(std::memory_order_relaxed) || prompt_decode != 0) {
        llama_batch_free(batch);
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, g_cancel_requested.load() ? "CANCELLED" :
                "Prompt evaluation failed (llama_decode=" + std::to_string(prompt_decode) + ").");
    }

    const float temperature = std::isfinite(requested_temperature)
            ? std::clamp(static_cast<float>(requested_temperature), 0.0f, 2.0f) : 0.7f;
    auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) {
        llama_batch_free(batch);
        llama_set_abort_callback(g_context, nullptr, nullptr);
        return to_java_string(env, "Could not initialize sampler.");
    }
    if (temperature <= 0.001f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    }

    std::string output;
    size_t emitted_bytes = 0;
    int32_t generated = 0;
    bool failed = false;
    bool ended = false;

    while (generated < max_tokens && !g_cancel_requested.load(std::memory_order_relaxed)) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        // llama_sampler_sample() accepts the sampled token internally at this revision.
        if (token < 0) {
            failed = true;
            break;
        }
        if (llama_vocab_is_eog(vocab, token)) {
            ended = true;
            break;
        }

        std::vector<char> piece(256);
        int32_t piece_size = llama_token_to_piece(vocab, token, piece.data(),
                static_cast<int32_t>(piece.size()), 0, false);
        if (piece_size < 0) {
            piece.resize(static_cast<size_t>(-piece_size));
            piece_size = llama_token_to_piece(vocab, token, piece.data(),
                    static_cast<int32_t>(piece.size()), 0, false);
        }
        if (piece_size < 0) {
            failed = true;
            break;
        }
        output.append(piece.data(), static_cast<size_t>(piece_size));
        ++generated;

        const size_t safe_end = complete_utf8_prefix(output);
        if (!emit_text(env, callback, on_token, output, emitted_bytes, safe_end)) {
            failed = true;
            break;
        }
        emitted_bytes = safe_end;

        if (generated < max_tokens && !g_cancel_requested.load(std::memory_order_relaxed)) {
            if (!decode_token(g_context, batch, token,
                    static_cast<llama_pos>(prompt_tokens.size() + generated - 1))) {
                if (!g_cancel_requested.load(std::memory_order_relaxed)) failed = true;
                break;
            }
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    llama_set_abort_callback(g_context, nullptr, nullptr);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return to_java_string(env, "Token callback failed.");
    }
    if (failed) return to_java_string(env, "Generation failed while decoding or streaming tokens.");
    if (g_cancel_requested.load(std::memory_order_relaxed)) {
        return to_java_string(env, "CANCELLED");
    }
    const size_t final_end = output.size();
    if (!emit_text(env, callback, on_token, output, emitted_bytes, final_end)) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_java_string(env, "Token callback failed.");
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "Generation finished: prompt_tokens=%d generated_tokens=%d ended=%d",
            actual_tokens, generated, ended ? 1 : 0);
    return to_java_string(env, "GENERATION_COMPLETE:" + std::to_string(generated));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeBeginGeneration(JNIEnv *, jobject) {
    g_cancel_requested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeCancelGeneration(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeUnloadModel(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gptmax_app_NativeBridge_nativeShutdown(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
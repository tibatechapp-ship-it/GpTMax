package com.tibatech.gptmax.core.router

import com.tibatech.gptmax.bridge.LlamaBridge
import com.tibatech.gptmax.bridge.OnTokenListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Local-first engine: wraps the JNI llama.cpp bridge behind [LlmClient].
 *
 * Prompt formatting uses ChatML, which matches Qwen2.5-Coder / Qwen3 chat
 * templates — the default local coder models for GpTMax.
 */
class LocalEngine(
    private val defaultMaxTokens: Int = 1024,
) : LlmClient {

    override val source: Source = Source.LOCAL

    private val generationMutex = Mutex()

    val isLoaded: Boolean
        get() = runCatching { LlamaBridge.nativeIsLoaded() }.getOrDefault(false)

    /**
     * Review #3: true while a generation holds the engine mutex. Model
     * management (load/unload/delete) must check this so a model file is
     * never removed underneath a running generation — chat streaming counts
     * too, not only agent runs.
     */
    val busy: Boolean
        get() = generationMutex.isLocked

    val lastError: String
        get() = runCatching { LlamaBridge.nativeLastError() }.getOrDefault("")

    suspend fun load(modelPath: String, nCtx: Int = 4096, nThreads: Int = 4): Boolean =
        withContext(Dispatchers.Default) {
            LlamaBridge.nativeInit(modelPath, nCtx, nThreads, /* nGpuLayers */ 0)
        }

    fun unload() {
        runCatching { LlamaBridge.nativeFree() }
    }

    fun cancel() {
        runCatching { LlamaBridge.nativeStop() }
    }

    override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult =
        withContext(Dispatchers.Default) {
            if (!isLoaded) {
                return@withContext LlmResult(
                    text = "[local engine not loaded — download a GGUF model in Models]",
                    source = Source.LOCAL,
                )
            }
            val prompt = ChatML.render(request.messages)
            val maxTokens = request.maxTokens ?: defaultMaxTokens
            val temperature = request.temperature ?: 0.2f

            val text = generationMutex.withLock {
                withTimeout(GENERATION_TIMEOUT_MS) {
                    LlamaBridge.nativeGenerate(
                        prompt = prompt,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = 0.9f,
                        topK = 40,
                        listener = object : OnTokenListener {
                            override fun onToken(piece: String) {
                                onDelta(piece)
                            }
                        },
                    )
                }
            }
            LlmResult(text = text, source = Source.LOCAL)
        }

    private companion object {
        // Local 3B-class models on phones: generous, but bounded.
        const val GENERATION_TIMEOUT_MS = 10L * 60 * 1000
    }
}

/** ChatML formatting used by Qwen-family chat models. */
object ChatML {
    fun render(messages: List<ChatMessage>): String = buildString {
        for (m in messages) {
            append("<|im_start|>").append(m.role).append('\n')
                .append(m.content)
                .append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}

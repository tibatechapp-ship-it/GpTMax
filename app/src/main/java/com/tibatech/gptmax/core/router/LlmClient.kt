package com.tibatech.gptmax.core.router

/**
 * Common LLM contract shared by the local GGUF engine and the BYOK cloud
 * bridge. The InferenceRouter picks the right client per task kind.
 */
enum class Source { LOCAL, CLOUD }

/** Task kinds map to the agent loop phases from the development plan. */
enum class TaskKind { CHAT, PLAN, ACT, VALIDATE }

data class ChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
)

data class LlmRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
)

data class LlmResult(
    val text: String,
    val source: Source,
    val stoppedByUser: Boolean = false,
)

interface LlmClient {
    val source: Source

    /**
     * Streams a completion, invoking [onDelta] on the caller's dispatcher as
     * text arrives. Returns the full result.
     */
    suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult

    /** Non-streaming convenience wrapper. */
    suspend fun complete(request: LlmRequest): LlmResult {
        val sb = StringBuilder()
        val r = stream(request) { sb.append(it) }
        return r.copy(text = sb.toString())
    }
}

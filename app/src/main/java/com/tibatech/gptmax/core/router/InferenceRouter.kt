package com.tibatech.gptmax.core.router

import com.tibatech.gptmax.core.guardrails.InferenceSafety

/**
 * Routes every request according to the strategy matrix from the report:
 *
 *  - CHAT / light edits ......... local GGUF when loaded (free, private)
 *  - PLAN / VALIDATE ............ cloud-first when enabled & configured,
 *                                 else local
 *  - any failure or missing key . graceful fallback to the other source
 *
 * Phase 2 (external review, inference safety): "graceful fallback" existed
 * only on paper before — [forTask] picked ONE client, and if it returned a
 * typed failure ("[cloud error 401] …", "local engine not loaded") the agent
 * happily parsed the error string as if it were model output. That is fixed
 * with [completeWithFallback]: the primary source is tried first, a typed
 * failure triggers one retry on the other source, and only a genuine answer
 * is ever handed back. Construction takes a settings SNAPSHOT provider
 * instead of the Android DataStore object, so the whole router is testable
 * on the JVM.
 */
class InferenceRouter(
    private val settingsSnapshot: suspend () -> com.tibatech.gptmax.data.settings.GpTMaxSettings,
    private val local: LocalEngine,
    private val cloud: LlmClient,
) {

    /** Best client for a task kind under current settings. */
    suspend fun forTask(kind: TaskKind): LlmClient {
        val s = settingsSnapshot()
        val cloudReady = s.cloudApiKey.isNotBlank() || s.provider.allowMissingKey
        return when (kind) {
            TaskKind.CHAT ->
                if (local.isLoaded) local else if (cloudReady) cloud else local
            TaskKind.PLAN, TaskKind.VALIDATE ->
                when {
                    s.cloudFirst && cloudReady -> cloud
                    local.isLoaded -> local
                    cloudReady -> cloud
                    else -> local
                }
            TaskKind.ACT ->
                if (local.isLoaded && !s.cloudFirstAct) local
                else if (cloudReady) cloud else local
        }
    }

    /**
     * Phase 2: non-streaming completion with a REAL fallback. The primary
     * client for [kind] is tried; a typed failure ([InferenceSafety.isFailureText])
     * triggers one attempt on the other source; when both fail, the primary
     * failure is returned so callers can surface a clear error.
     */
    suspend fun completeWithFallback(request: LlmRequest, kind: TaskKind): LlmResult {
        val primary = forTask(kind)
        val first = runCatching { primary.complete(request) }
            .getOrElse {
                return LlmResult(
                    text = InferenceSafety.failure("${primary.source.name.lowercase()} call failed: ${it.message}"),
                    source = primary.source,
                )
            }
        if (!InferenceSafety.isFailureText(first.text)) return first

        val second = runCatching {
            val other = if (primary.source == Source.LOCAL) cloud else local
            other.complete(request)
        }.getOrElse { return first }
        return if (!InferenceSafety.isFailureText(second.text)) second else first
    }

    /** Manual override used by the chat screen model badge. */
    fun clientFor(source: Source): LlmClient = when (source) {
        Source.LOCAL -> local
        Source.CLOUD -> cloud
    }
}

package com.tibatech.gptmax.core.router

import com.tibatech.gptmax.data.settings.SettingsStore

/**
 * Routes every request according to the strategy matrix from the report:
 *
 *  - CHAT / light edits ......... local GGUF when loaded (free, private)
 *  - PLAN / VALIDATE ............ cloud-first when enabled & configured,
 *                                 else local
 *  - any failure or missing key . graceful fallback to the other source
 */
class InferenceRouter(
    private val settings: SettingsStore,
    private val local: LocalEngine,
    private val cloud: CloudBridge,
) {

    /** Best client for a task kind under current settings. */
    suspend fun forTask(kind: TaskKind): LlmClient {
        val s = settings.snapshot()
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

    /** Manual override used by the chat screen model badge. */
    fun clientFor(source: Source): LlmClient = when (source) {
        Source.LOCAL -> local
        Source.CLOUD -> cloud
    }
}

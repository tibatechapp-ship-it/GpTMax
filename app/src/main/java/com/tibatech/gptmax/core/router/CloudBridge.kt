package com.tibatech.gptmax.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.tibatech.gptmax.core.guardrails.InferenceSafety
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * BYOK cloud bridge ("Bring Your Own Key") — speaks the OpenAI-compatible
 * `/chat/completions` streaming protocol, which covers:
 *   - GLM Coding Plan (open.bigmodel.cn/api/paas/v4)
 *   - DeepSeek (api.deepseek.com/v1)
 *   - OpenRouter, Ollama-on-PC, LM Studio, vLLM, …
 *
 * The key never leaves the device: it lives in DataStore and is attached
 * per-request.
 *
 * Phase 2 (external review, inference safety): the SSE loop now has a hard
 * output cap and a total-stream deadline — a provider that streams forever
 * or sends gigabytes could previously OOM the app. Endpoints that would
 * carry an API key must be https://. Temperatures / max_tokens are clamped
 * and every failure returns the typed [InferenceSafety.FAILURE_PREFIX]
 * marker instead of an error string that looks like a model answer.
 */
class CloudBridge(
    private val configProvider: suspend () -> ProviderConfig,
) : LlmClient {

    override val source: Source = Source.CLOUD

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            if (config.apiKey.isBlank() && !config.allowMissingKey) {
                return@withContext LlmResult(
                    text = InferenceSafety.failure(
                        "cloud bridge not configured — add an API key in Settings",
                    ),
                    source = Source.CLOUD,
                )
            }
            val endpoint = InferenceSafety.validateEndpoint(config.baseUrl, config.allowMissingKey)
            if (endpoint != null) {
                return@withContext LlmResult(
                    text = InferenceSafety.failure(endpoint),
                    source = Source.CLOUD,
                )
            }

            val body = buildJsonObject {
                put("model", config.model)
                put("stream", true)
                put(
                    "temperature",
                    InferenceSafety.clampTemperature(request.temperature ?: config.temperature)
                        ?.toDouble() ?: 0.2,
                )
                put(
                    "max_tokens",
                    InferenceSafety.clampMaxTokens(request.maxTokens ?: config.maxTokens) ?: 8192,
                )
                put("messages", json.encodeToJsonElement(request.messages.map {
                    buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    }
                }))
            }

            val httpRequest = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val sb = StringBuilder()
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = InferenceSafety.sanitize(
                        response.body?.string().orEmpty(),
                        MAX_ERROR_CHARS,
                    )
                    return@withContext LlmResult(
                        text = InferenceSafety.failure("cloud HTTP ${response.code}: $err"),
                        source = Source.CLOUD,
                    )
                }
                val reader: BufferedReader = response.body?.charStream()?.buffered()
                    ?: return@withContext LlmResult(text = sb.toString(), source = Source.CLOUD)

                // Phase 2: bounded drain — a total-stream deadline plus a hard
                // character cap. readTimeout bounds a single stalled read; the
                // deadline below bounds the WHOLE stream so a provider that
                // dribbles keepalive lines forever cannot hold the app hostage.
                val deadline = System.nanoTime() + STREAM_DEADLINE_NANOS
                var timedOut = false
                var capped = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (System.nanoTime() > deadline) {
                        timedOut = true
                        break
                    }
                    val payload = line.removePrefix("data: ").trim()
                    when {
                        line.startsWith("data:") && payload == "[DONE]" -> break
                        line.startsWith("data:") -> {
                            val delta = extractDelta(payload)
                            if (delta.isNotEmpty() && sb.length < InferenceSafety.MAX_OUTPUT_CHARS) {
                                sb.append(delta)
                                onDelta(delta)
                            }
                            if (sb.length >= InferenceSafety.MAX_OUTPUT_CHARS && !capped) {
                                capped = true
                                val marker = "[output truncated at " +
                                    InferenceSafety.MAX_OUTPUT_CHARS + " chars]"
                                sb.append('\n').append(marker)
                                onDelta("\n$marker")
                                break
                            }
                        }
                    }
                }
                if (timedOut) {
                    return@withContext LlmResult(
                        text = InferenceSafety.failure(
                            "cloud stream exceeded the total deadline " +
                                "(${STREAM_DEADLINE_MINUTES} min) — provider stalled",
                        ),
                        source = Source.CLOUD,
                    )
                }
            }
            LlmResult(text = sb.toString(), source = Source.CLOUD)
        }

    /** choices[0].delta.content from an SSE chunk; empty when absent. */
    private fun extractDelta(payload: String): String = runCatching {
        val obj: JsonObject = json.parseToJsonElement(payload).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return ""
        val first = choices.firstOrNull()?.jsonObject ?: return ""
        first["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
    }.getOrDefault("")

    companion object {
        /** Curated BYOK presets from the strategy section of the report. */
        val PRESETS = listOf(
            ProviderConfig(
                name = "GLM Coding Plan",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                model = "glm-4.6",
            ),
            ProviderConfig(
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
            ),
            ProviderConfig(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                model = "anthropic/claude-sonnet-4",
            ),
            ProviderConfig(
                name = "Ollama on PC (LAN)",
                baseUrl = "http://192.168.1.10:11434/v1",
                model = "qwen2.5-coder:7b",
                allowMissingKey = true,
            ),
        )

        /** Phase 2: caps and deadlines for the SSE drain. */
        const val STREAM_DEADLINE_MINUTES = 6L
        const val STREAM_DEADLINE_NANOS = STREAM_DEADLINE_MINUTES * 60 * 1_000_000_000L
        const val MAX_ERROR_CHARS = 400
    }
}

@kotlinx.serialization.Serializable
data class ProviderConfig(
    val name: String = "GLM Coding Plan",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-4.6",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 8192,
    /** Ollama / LM Studio endpoints need no key. */
    val allowMissingKey: Boolean = false,
)

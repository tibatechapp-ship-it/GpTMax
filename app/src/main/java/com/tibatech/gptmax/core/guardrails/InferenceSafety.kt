package com.tibatech.gptmax.core.guardrails

/**
 * Phase 2 (external review, inference safety): one choke point for
 * everything a model or a remote provider can send back into the app.
 *
 * Why this exists: a provider (or a corrupted model) can return gigabytes of
 * text, garbage control characters, or error strings that look like model
 * output. The old code fed all three straight into PlanParser, the validator
 * and the UI. This object:
 *
 * - [sanitize] strips control characters and caps the length of any text
 *   that is about to enter the context, the journal or the UI;
 * - [failure] / [isFailureText] give every client (local engine, cloud
 *   bridge) ONE typed failure marker, so the router can fall back to the
 *   other source instead of treating "[cloud error 401] …" as a model answer;
 * - [validateEndpoint] refuses to send an API key over plain http://;
 * - clamp helpers keep temperature / maxTokens inside sane bounds no matter
 *   what the settings file contains;
 * - [promptTooLarge] refuses prompts that would blow the phone's memory
 *   before they ever reach the native engine.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
object InferenceSafety {

    /**
     * Every client-produced failure starts with this marker. Anything that
     * carries it is NEVER parsed as a plan, NEVER shown as a successful
     * answer, and triggers a fallback to the other inference source.
     */
    const val FAILURE_PREFIX = "[gptmax-failure]"

    /**
     * Failure sentinels shipped before phase 2. They stay recognized so a
     * mixed old/new deployment still routes errors correctly.
     */
    private val LEGACY_FAILURE_MARKERS = listOf(
        "[local engine not loaded",
        "[cloud bridge not configured",
        "[cloud error",
        "[error]",
        "[output truncated",
    )

    /** Builds the canonical failure text for a client-produced error. */
    fun failure(reason: String): String = "$FAILURE_PREFIX $reason"

    /**
     * True when [text] is a typed failure rather than model output. Uses
     * `contains` for the legacy markers because some of them can appear
     * mid-text (e.g. a truncated stream appends its marker at the end).
     */
    fun isFailureText(text: String): Boolean {
        val t = text.trimStart()
        if (t.startsWith(FAILURE_PREFIX)) return true
        return LEGACY_FAILURE_MARKERS.any { t.contains(it) }
    }

    /**
     * Strips control characters (everything below U+0020 except \n, \r, \t)
     * and hard-caps the result at [maxChars]. Applied to anything that
     * enters the context window, the UI or the persisted log lines.
     */
    fun sanitize(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
        if (text.length <= maxChars && !hasControlChars(text)) return text
        val sb = StringBuilder(minOf(text.length, maxChars + 16))
        for (c in text) {
            if (sb.length >= maxChars) break
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(c)
                c < ' ' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return if (text.length > maxChars) {
            sb.append("…(truncated at ").append(maxChars).append(" chars)").toString()
        } else {
            sb.toString()
        }
    }

    private fun hasControlChars(text: String): Boolean =
        text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }

    /** Temperature clamped to the valid sampling range [0, 2]. */
    fun clampTemperature(value: Float?): Float? = value?.coerceIn(0f, 2f)

    /** maxTokens clamped to [1, upper] so a broken setting cannot OOM. */
    fun clampMaxTokens(value: Int?, upper: Int = MAX_TOKENS_CEILING): Int? =
        value?.coerceIn(1, upper)

    /**
     * null = endpoint acceptable; non-null = refusal reason. An endpoint
     * that will carry an API key must be https:// — plain http:// is only
     * allowed for key-less LAN endpoints (Ollama / LM Studio on the home
     * network), where no secret is ever attached to the request.
     */
    fun validateEndpoint(baseUrl: String, allowMissingKey: Boolean): String? {
        val url = baseUrl.trim().lowercase()
        if (url.isBlank()) return "endpoint is empty — set a provider URL in Settings"
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return "endpoint must start with https:// (or http:// for a key-less LAN endpoint)"
        }
        if (url.startsWith("http://") && !allowMissingKey) {
            return "insecure http:// endpoint refused while an API key is set — " +
                "your key would travel unencrypted; use https:// or a key-less LAN provider"
        }
        return null
    }

    /** True when a rendered prompt exceeds what the phone engine should hold. */
    fun promptTooLarge(prompt: String, maxChars: Int = MAX_PROMPT_CHARS): Boolean =
        prompt.length > maxChars

    /** Hard cap for any completion text held in memory (chars). */
    const val MAX_OUTPUT_CHARS = 200_000

    /** Hard cap for a rendered prompt reaching the native engine (chars). */
    const val MAX_PROMPT_CHARS = 96_000

    /** Ceiling for maxTokens regardless of settings. */
    const val MAX_TOKENS_CEILING = 8_192
}

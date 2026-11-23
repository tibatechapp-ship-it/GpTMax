package dev.gptmax.app

import java.util.Locale

/**
 * Parses the native runtime status conservatively.
 *
 * A negative status such as "no model loaded" must never match the
 * positive phrase "model loaded".
 */
internal fun isNativeModelReady(status: String): Boolean {
    val normalized = status.trim().lowercase(Locale.ROOT)
    if (normalized.contains("no model loaded") ||
        normalized.contains("model not loaded") ||
        normalized.contains("runtime unavailable") ||
        normalized.contains("backend unavailable")
    ) {
        return false
    }
    return normalized.contains("gguf model loaded") &&
        normalized.contains("inference context ready")
}

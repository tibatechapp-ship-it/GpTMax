package dev.gptmax.app.agent

/** Deterministic prompt reduction policy: discard observations before shortening the task. */
internal data class PromptFitLimits(
    val requestChars: Int,
    val observationChars: Int,
)

internal object PromptContextFit {
    fun next(current: PromptFitLimits): PromptFitLimits? = when {
        current.observationChars > OBSERVATION_STEP ->
            current.copy(observationChars = current.observationChars / 2)
        current.observationChars > 0 ->
            current.copy(observationChars = 0)
        current.requestChars > MIN_REQUEST_CHARS ->
            current.copy(requestChars = (current.requestChars / 2).coerceAtLeast(MIN_REQUEST_CHARS))
        else -> null
    }

    private const val OBSERVATION_STEP = 512
    private const val MIN_REQUEST_CHARS = 400
}

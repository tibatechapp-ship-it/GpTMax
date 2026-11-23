package dev.gptmax.app.inference

/**
 * Lifecycle contract for the native inference session.
 *
 * The native engine will implement these transitions in M1. Keeping lifecycle
 * policy in a pure Kotlin model makes cancellation/error paths testable without
 * loading native code in JVM unit tests.
 */
enum class InferenceLifecycle {
    UNINITIALIZED,
    INITIALIZING,
    BACKEND_READY,
    MODEL_LOADING,
    MODEL_READY,
    GENERATING,
    ERROR,
    CLOSED;

    fun canTransitionTo(next: InferenceLifecycle): Boolean {
        if (this == next || this == CLOSED) return false
        if (next == ERROR && this != UNINITIALIZED) return true

        return next in allowedTransitions[this].orEmpty()
    }

    companion object {
        private val allowedTransitions: Map<InferenceLifecycle, Set<InferenceLifecycle>> =
            mapOf(
                UNINITIALIZED to setOf(INITIALIZING),
                INITIALIZING to setOf(BACKEND_READY),
                BACKEND_READY to setOf(MODEL_LOADING, CLOSED),
                MODEL_LOADING to setOf(MODEL_READY, BACKEND_READY),
                MODEL_READY to setOf(GENERATING, MODEL_LOADING, CLOSED),
                GENERATING to setOf(MODEL_READY),
                ERROR to setOf(INITIALIZING, CLOSED),
            )
    }
}

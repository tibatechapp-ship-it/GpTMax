package dev.gptmax.app.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceLifecycleTest {
    @Test
    fun validLoadGenerateUnloadPathIsAllowed() {
        val path = listOf(
            InferenceLifecycle.UNINITIALIZED,
            InferenceLifecycle.INITIALIZING,
            InferenceLifecycle.BACKEND_READY,
            InferenceLifecycle.MODEL_LOADING,
            InferenceLifecycle.MODEL_READY,
            InferenceLifecycle.GENERATING,
            InferenceLifecycle.MODEL_READY,
            InferenceLifecycle.CLOSED,
        )

        path.zipWithNext().forEach { (current, next) ->
            assertTrue("$current should transition to $next", current.canTransitionTo(next))
        }
    }

    @Test
    fun loadingCanBeCancelledBackToBackendReady() {
        assertTrue(
            InferenceLifecycle.MODEL_LOADING.canTransitionTo(InferenceLifecycle.BACKEND_READY),
        )
    }

    @Test
    fun invalidTransitionsAndClosedSessionAreRejected() {
        assertFalse(
            InferenceLifecycle.UNINITIALIZED.canTransitionTo(InferenceLifecycle.MODEL_READY),
        )
        assertFalse(
            InferenceLifecycle.GENERATING.canTransitionTo(InferenceLifecycle.CLOSED),
        )
        assertFalse(
            InferenceLifecycle.CLOSED.canTransitionTo(InferenceLifecycle.INITIALIZING),
        )
    }

    @Test
    fun recoverableErrorCanReinitialize() {
        assertTrue(InferenceLifecycle.ERROR.canTransitionTo(InferenceLifecycle.INITIALIZING))
    }
}

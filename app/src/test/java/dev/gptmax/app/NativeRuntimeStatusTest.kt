package dev.gptmax.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeStatusTest {
    @Test
    fun noModelLoadedIsNotReady() {
        assertFalse(isNativeModelReady("llama.cpp linked; no model loaded."))
    }

    @Test
    fun loadedModelWithReadyContextIsReady() {
        assertTrue(isNativeModelReady("GGUF model loaded; inference context ready."))
    }

    @Test
    fun partialOrUnrelatedStatusIsNotReady() {
        assertFalse(isNativeModelReady("llama.cpp linked"))
        assertFalse(isNativeModelReady("Model loading…"))
        assertFalse(isNativeModelReady("Native backend unavailable"))
    }

    @Test
    fun statusParsingIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(isNativeModelReady("  gguf MODEL loaded; INFERENCE context READY.  "))
    }
}

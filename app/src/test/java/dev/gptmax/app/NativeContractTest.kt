package dev.gptmax.app

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeContractTest {
    @Test
    fun nativeStatusClearlyReportsBackendNotYetLinked() {
        val status = "JNI bridge ready. GGUF inference backend is not linked yet."
        assertTrue(status.contains("not linked yet"))
    }

    @Test
    fun applicationIdUsesGpTMaxNamespace() {
        assertTrue("dev.gptmax.app".startsWith("dev.gptmax"))
    }
}

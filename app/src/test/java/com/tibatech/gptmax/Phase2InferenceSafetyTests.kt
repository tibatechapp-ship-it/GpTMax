package com.tibatech.gptmax

import com.tibatech.gptmax.core.guardrails.InferenceSafety
import com.tibatech.gptmax.core.router.ChatMessage
import com.tibatech.gptmax.core.router.CloudBridge
import com.tibatech.gptmax.core.router.InferenceRouter
import com.tibatech.gptmax.core.router.LlmClient
import com.tibatech.gptmax.core.router.LlmRequest
import com.tibatech.gptmax.core.router.LlmResult
import com.tibatech.gptmax.core.router.LocalEngine
import com.tibatech.gptmax.core.router.ProviderConfig
import com.tibatech.gptmax.core.router.Source
import com.tibatech.gptmax.core.router.TaskKind
import com.tibatech.gptmax.data.settings.GpTMaxSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (external review, inference safety) acceptance criteria:
 * - control characters and runaway output are sanitized/capped before they
 *   enter the context, the journal or the UI;
 * - every client failure carries ONE typed marker the router can act on;
 * - sampling parameters and endpoints are validated (no key over http://);
 * - a typed failure on the primary source triggers a REAL fallback to the
 *   other source instead of being parsed as a model answer.
 */
class Phase2InferenceSafetyTests {

    // ------------------------------------------------------------- sanitize

    @Test
    fun `sanitize strips control characters`() {
        val dirty = "a\u0000b\u0007c" // NUL + BEL embedded
        assertEquals("a b c", InferenceSafety.sanitize(dirty))
    }

    @Test
    fun `sanitize keeps newlines tabs and normal text`() {
        val text = "hello world\nsecond line\tindented"
        assertEquals(text, InferenceSafety.sanitize(text))
        assertEquals(text, InferenceSafety.sanitize(text, maxChars = 10_000))
    }

    @Test
    fun `sanitize caps length with a visible marker`() {
        val huge = "x".repeat(50_000)
        val out = InferenceSafety.sanitize(huge, maxChars = 1_000)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out.contains("truncated"))
        assertTrue(out.length < 1_100)
    }

    // ------------------------------------------------------- failure markers

    @Test
    fun `typed failure prefix is recognized`() {
        assertTrue(InferenceSafety.isFailureText(InferenceSafety.failure("anything broken")))
        assertFalse(InferenceSafety.isFailureText("a plan mentioning ${InferenceSafety.FAILURE_PREFIX} inline is fine"))
    }

    @Test
    fun `legacy failure sentinels are still recognized`() {
        assertTrue(InferenceSafety.isFailureText("[local engine not loaded — download a GGUF model]"))
        assertTrue(InferenceSafety.isFailureText("[cloud bridge not configured — add a key]"))
        assertTrue(InferenceSafety.isFailureText("[cloud error 401] bad key"))
        assertTrue(
            InferenceSafety.isFailureText(
                "partial answer\n[output truncated at 200000 chars]",
            ),
        )
    }

    @Test
    fun `plain model output is not a failure`() {
        assertFalse(InferenceSafety.isFailureText("APPROVED: the change looks safe"))
        assertFalse(InferenceSafety.isFailureText("""[{"tool":"read_file","args":{}}]"""))
        assertFalse(InferenceSafety.isFailureText(""))
    }

    // ---------------------------------------------------------------- clamps

    @Test
    fun `temperature clamps into 0 to 2`() {
        assertEquals(2f, InferenceSafety.clampTemperature(5f)!!)
        assertEquals(0f, InferenceSafety.clampTemperature(-1f)!!)
        assertEquals(0.7f, InferenceSafety.clampTemperature(0.7f)!!)
        assertNull(InferenceSafety.clampTemperature(null))
    }

    @Test
    fun `maxTokens clamps into 1 to ceiling`() {
        assertEquals(1, InferenceSafety.clampMaxTokens(0)!!)
        assertEquals(InferenceSafety.MAX_TOKENS_CEILING, InferenceSafety.clampMaxTokens(999_999)!!)
        assertEquals(1024, InferenceSafety.clampMaxTokens(1024)!!)
        assertNull(InferenceSafety.clampMaxTokens(null))
    }

    // --------------------------------------------------- endpoint validation

    @Test
    fun `https endpoints are accepted`() {
        assertNull(InferenceSafety.validateEndpoint("https://api.deepseek.com/v1", false))
        assertNull(InferenceSafety.validateEndpoint(" https://openrouter.ai/api/v1 ", false))
    }

    @Test
    fun `plain http with an api key is refused`() {
        assertNotNull(InferenceSafety.validateEndpoint("http://api.example.com/v1", false))
    }

    @Test
    fun `plain http without a key is allowed for LAN endpoints`() {
        assertNull(InferenceSafety.validateEndpoint("http://192.168.1.10:11434/v1", true))
    }

    @Test
    fun `non-http schemes and empty endpoints are refused`() {
        assertNotNull(InferenceSafety.validateEndpoint("ftp://files.example.com", false))
        assertNotNull(InferenceSafety.validateEndpoint("file:///etc/passwd", false))
        assertNotNull(InferenceSafety.validateEndpoint("", false))
    }

    // -------------------------------------------------- cloud failure texts

    @Test
    fun `unconfigured cloud bridge returns a typed failure`() = runTest {
        val bridge = CloudBridge { ProviderConfig(apiKey = "") }
        val result = bridge.complete(LlmRequest(messages = listOf(ChatMessage("user", "hi"))))
        assertTrue(InferenceSafety.isFailureText(result.text))
        assertTrue(result.text.contains("not configured"))
    }

    @Test
    fun `insecure endpoint with a key is refused before any network call`() = runTest {
        val bridge = CloudBridge {
            ProviderConfig(apiKey = "secret", baseUrl = "http://api.example.com/v1")
        }
        val result = bridge.complete(LlmRequest(messages = listOf(ChatMessage("user", "hi"))))
        assertTrue(InferenceSafety.isFailureText(result.text))
        assertTrue(result.text.contains("https"))
    }

    // ------------------------------------------------------- router fallback

    @Test
    fun `primary failure falls back to the other source`() = runTest {
        val cloud = FakeLlmClient("""[{"tool":"finish","args":{"summary":"ok"}}]""")
        val router = InferenceRouter(
            { settings(cloudFirst = true, cloudReady = false) },
            LocalEngine(),
            cloud,
        )
        val result = router.completeWithFallback(
            LlmRequest(messages = listOf(ChatMessage("user", "goal"))),
            TaskKind.PLAN,
        )
        assertEquals("""[{"tool":"finish","args":{"summary":"ok"}}]""", result.text)
        assertEquals(Source.CLOUD, result.source)
    }

    @Test
    fun `both sources failing returns the primary failure`() = runTest {
        val cloud = FakeLlmClient(InferenceSafety.failure("cloud down"))
        val router = InferenceRouter(
            { settings(cloudFirst = true, cloudReady = false) },
            LocalEngine(),
            cloud,
        )
        val result = router.completeWithFallback(
            LlmRequest(messages = listOf(ChatMessage("user", "goal"))),
            TaskKind.PLAN,
        )
        assertTrue(InferenceSafety.isFailureText(result.text))
        // The PRIMARY (local) failure is surfaced, not the fallback's.
        assertTrue(result.text.contains("local engine not loaded"))
    }

    @Test
    fun `a healthy primary is returned untouched`() = runTest {
        val cloud = FakeLlmClient("should not be reached")
        val router = InferenceRouter(
            { settings(cloudFirst = true, cloudReady = true) },
            LocalEngine(),
            cloud,
        )
        val result = router.completeWithFallback(
            LlmRequest(messages = listOf(ChatMessage("user", "goal"))),
            TaskKind.PLAN,
        )
        assertEquals("should not be reached", result.text)
    }

    // ------------------------------------------------------------------ utils

    private fun settings(cloudFirst: Boolean, cloudReady: Boolean) = GpTMaxSettings(
        provider = ProviderConfig(apiKey = if (cloudReady) "test-key" else ""),
        cloudFirst = cloudFirst,
        cloudFirstAct = false,
        autoApproveWrites = false,
        localModelPath = "",
        nCtx = 4096,
        nThreads = 2,
    )

    /** Scripted cloud client: always answers with [response]. */
    private class FakeLlmClient(private val response: String) : LlmClient {
        override val source = Source.CLOUD

        override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult {
            onDelta(response)
            return LlmResult(response, source)
        }
    }
}

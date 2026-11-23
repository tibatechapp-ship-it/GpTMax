package dev.gptmax.app.agent

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskLedgerTest {
    @Test
    fun storesMetadataAndDigestButNotRawToolOutput() {
        val directory = Files.createTempDirectory("agent-ledger").toFile()
        val file = directory.resolve("ledger.jsonl")
        try {
            AgentTaskLedger(file).record(
                sessionId = "session-1",
                event = "tool_result",
                toolName = "file.read",
                status = "SUCCESS",
                durationMs = 12,
                output = "PRIVATE_CONTENT_DO_NOT_STORE",
            )
            val text = file.readText()
            assertTrue(text.contains("session-1"))
            assertTrue(text.contains("output_sha256"))
            assertFalse(text.contains("PRIVATE_CONTENT_DO_NOT_STORE"))
        } finally {
            directory.deleteRecursively()
        }
    }
}

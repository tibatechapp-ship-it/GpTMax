package com.tibatech.gptmax

import com.tibatech.gptmax.core.agent.WriteJournal
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import com.tibatech.gptmax.data.repo.WorkspaceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the six review fixes: sensitive paths (#1), the write
 * journal with its three failure scenarios (#2), path containment (#6) and
 * the shared digest budget (#5).
 */
class AgentSafetyTests {

    // ------------------------------------------------- #1 sensitive paths

    @Test
    fun `aws ssh env and keystore paths are blocked`() {
        assertTrue(SensitivePaths.isSensitive(".aws/credentials"))
        assertTrue(SensitivePaths.isSensitive(".ssh/id_rsa"))
        assertTrue(SensitivePaths.isSensitive("config/.env"))
        assertTrue(SensitivePaths.isSensitive("config/.env.local"))
        assertTrue(SensitivePaths.isSensitive("config/.env.production"))
        assertTrue(SensitivePaths.isSensitive("app/release.keystore"))
        assertTrue(SensitivePaths.isSensitive("keys/server.pem"))
        assertTrue(SensitivePaths.isSensitive("deploy/private_key/ed25519.txt"))
        assertTrue(SensitivePaths.isSensitive("app/secrets/api_key.txt"))
    }

    @Test
    fun `normal source files stay readable`() {
        assertFalse(SensitivePaths.isSensitive("src/main/kotlin/App.kt"))
        assertFalse(SensitivePaths.isSensitive("README.md"))
        assertFalse(SensitivePaths.isSensitive("ui/CredentialsScreen.kt"))
        assertFalse(SensitivePaths.isSensitive("nlp/tokenize/Tokenizer.kt"))
        assertFalse(SensitivePaths.isSensitive("build.gradle.kts"))
    }

    @Test
    fun `read_file refuses credentials`() = runTest {
        val ws = FakeWorkspace()
        ws.files[".aws/credentials"] = "[default]\naws_secret_access_key = SECRET"
        val tools = ToolRegistry(ws)
        val result = tools.execute(step("read_file", "{\"path\": \".aws/credentials\"}"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("blocked"))
    }

    @Test
    fun `list_dir hides sensitive entries`() = runTest {
        val ws = FakeWorkspace()
        ws.nodes += WorkspaceRepository.FileNode(".env", ".env", false)
        ws.nodes += WorkspaceRepository.FileNode("App.kt", "App.kt", false)
        val tools = ToolRegistry(ws)
        val listing = tools.execute(step("list_dir", "{}"))
        assertTrue(listing.output.contains("App.kt"))
        assertFalse(listing.output.contains(".env"))
    }

    // ------------------------------------------------------- #6 path guard

    @Test
    fun `path guard rejects traversal and absolute paths`() {
        assertTrue(PathGuard.isAllowed("src/App.kt"))
        assertTrue(PathGuard.isAllowed("")) // workspace root listing
        assertFalse(PathGuard.isAllowed("../outside.kt"))
        assertFalse(PathGuard.isAllowed("src/../../escape.kt"))
        assertFalse(PathGuard.isAllowed("/etc/passwd"))
        assertFalse(PathGuard.isAllowed("C:\\Users\\x\\secrets"))
    }

    @Test
    fun `write_file cannot escape the workspace`() = runTest {
        val tools = ToolRegistry(FakeWorkspace())
        val result = tools.execute(
            step("write_file", "{\"path\": \"../evil.kt\", \"content\": \"hi\"}"),
        )
        assertFalse(result.ok)
        assertTrue(result.output.contains("blocked"))
    }

    // ------------------------- #2 write journal — three failure scenarios

    @Test
    fun `scenario 1 external modification after write is detected`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "old content"
        // Simulate another process rewriting the file right after the agent writes it.
        ws.mutateAfterWrite = { ws.files["App.kt"] = "tampered by external editor" }
        val tools = ToolRegistry(ws)
        val result = tools.execute(
            step("write_file", "{\"path\": \"App.kt\", \"content\": \"new content\"}"),
        )
        assertFalse(result.ok)
        assertTrue(result.output.contains("diverged"))
        // The journal still holds the pre-change state for rollback.
        val entry = tools.journal.lastFor("App.kt")
        assertNotNull(entry)
        assertEquals(WriteJournal.Kind.WRITE, entry!!.kind)
        assertEquals("old content", entry.previousContent)
    }

    @Test
    fun `scenario 2 failed write rolls previous content back`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "old content"
        ws.failWrites = true
        val tools = ToolRegistry(ws)
        val result = tools.execute(
            step("write_file", "{\"path\": \"App.kt\", \"content\": \"new content\"}"),
        )
        assertFalse(result.ok)
        // Nothing was lost: previous content still on disk and journaled.
        assertEquals("old content", ws.files["App.kt"])
        assertEquals("old content", tools.journal.lastFor("App.kt")!!.previousContent)
    }

    @Test
    fun `scenario 3 provider disconnect leaves workspace and journal intact`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "old content"
        ws.throwWrites = true
        val tools = ToolRegistry(ws)
        val result = tools.execute(
            step("write_file", "{\"path\": \"App.kt\", \"content\": \"new content\"}"),
        )
        assertFalse(result.ok)
        assertTrue(result.output.contains("tool error"))
        assertEquals("old content", ws.files["App.kt"])
        val entry = tools.journal.lastFor("App.kt")
        assertNotNull(entry)
        assertEquals("old content", entry!!.previousContent)
    }

    @Test
    fun `successful overwrite reports the fingerprint of the replaced content`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "old content"
        val tools = ToolRegistry(ws)
        val result = tools.execute(
            step("write_file", "{\"path\": \"App.kt\", \"content\": \"new content\"}"),
        )
        assertTrue(result.ok)
        assertTrue(result.output.contains("overwrote"))
        assertEquals("new content", ws.files["App.kt"])
    }

    @Test
    fun `delete failure restores the previous content`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "old content"
        ws.failDeletes = true
        val tools = ToolRegistry(ws)
        val result = tools.execute(step("delete_file", "{\"path\": \"App.kt\"}"))
        assertFalse(result.ok)
        assertEquals("old content", ws.files["App.kt"])
    }

    // --------------------------------------------- #5 shared digest budget

    @Test
    fun `digest respects the total budget and blocks sensitive paths`() = runTest {
        val ws = FakeWorkspace()
        ws.files["a.kt"] = "A".repeat(9_000)
        ws.files["b.kt"] = "B".repeat(9_000)
        ws.files[".env"] = "TOKEN=secret"
        val context = ContextBuilder(ws)
        val digest = context.digest(listOf("a.kt", ".env", "b.kt"))
        assertTrue(digest.length < ContextBuilder.DEFAULT_TOTAL_BUDGET + 2_000)
        assertTrue(digest.contains("a.kt"))
        assertTrue(digest.contains("(blocked: sensitive path)"))
        assertFalse(digest.contains("TOKEN=secret"))
    }

    @Test
    fun `repo map marks sensitive entries excluded without descending`() = runTest {
        val ws = FakeWorkspace()
        ws.nodes += WorkspaceRepository.FileNode(".ssh", ".ssh", true)
        ws.nodes += WorkspaceRepository.FileNode("src", "src", true)
        val context = ContextBuilder(ws)
        val map = context.repoMap()
        assertTrue(map.contains("src"))
        assertTrue(map.contains("(excluded)"))
    }

    // ------------------------------------------------------------------ utils

    private fun step(tool: String, args: String) = AgentStep(
        index = 0,
        tool = tool,
        argsJson = args,
        description = tool,
        isWrite = tool == "write_file" || tool == "delete_file",
    )
}

/** In-memory [WorkspaceOps] used to exercise the guard/journal layer on JVM. */
private class FakeWorkspace : WorkspaceOps {
    val files = mutableMapOf<String, String>()
    val nodes = mutableListOf<WorkspaceRepository.FileNode>()
    var failWrites = false
    var throwWrites = false
    var failDeletes = false
    var mutateAfterWrite: ((String) -> Unit)? = null

    override suspend fun read(path: String): String? = files[path]

    override suspend fun write(path: String, content: String): Boolean {
        if (throwWrites) throw java.io.IOException("provider disconnected")
        if (failWrites) return false
        files[path] = content
        mutateAfterWrite?.invoke(path)
        return true
    }

    override suspend fun delete(path: String): Boolean {
        if (failDeletes) return false
        return files.remove(path) != null
    }

    override suspend fun list(path: String): List<WorkspaceRepository.FileNode> = nodes.toList()

    override suspend fun grep(pattern: String): List<WorkspaceRepository.GrepHit> = emptyList()
}

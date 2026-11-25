package com.tibatech.gptmax

import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.SaveTransaction
import com.tibatech.gptmax.core.agent.SessionPinner
import com.tibatech.gptmax.core.agent.ToolRegistry
import com.tibatech.gptmax.core.agent.WorkspaceOps
import com.tibatech.gptmax.core.guardrails.ApprovalGate
import com.tibatech.gptmax.core.guardrails.CommandIsolation
import com.tibatech.gptmax.data.repo.WorkspaceRepository
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (data protection, external review) acceptance criteria:
 * - out-of-scope operations are refused: workspace-root deletion, directory
 *   deletion, command path arguments, broad command execution itself;
 * - a failed save keeps the edits UNSAVED (no silent success);
 * - a cancel ends an in-flight approval wait instead of leaving it hanging;
 * - a session is pinned to one workspace.
 */
class Phase1DataSafetyTests {

    // ---------------------------------------- out-of-scope: delete guards

    @Test
    fun `delete_file refuses the workspace root`() = runTest {
        val tools = ToolRegistry(Phase1FakeWorkspace())
        for (root in listOf("", ".", "/")) {
            val result = tools.execute(step("delete_file", "{\"path\": \"$root\"}"))
            assertFalse("root form '$root' must be refused", result.ok)
            assertTrue(result.output.contains("workspace root"))
        }
    }

    @Test
    fun `delete_file refuses directories`() = runTest {
        val ws = Phase1FakeWorkspace()
        ws.directories += "src"
        ws.files["src/App.kt"] = "class App"
        val tools = ToolRegistry(ws)
        val result = tools.execute(step("delete_file", "{\"path\": \"src\"}"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("directory deletion is disabled"))
        // Nothing was deleted underneath the guard.
        assertTrue(ws.files.containsKey("src/App.kt"))
    }

    @Test
    fun `delete_file still deletes plain files`() = runTest {
        val ws = Phase1FakeWorkspace()
        ws.files["notes.txt"] = "hello"
        val tools = ToolRegistry(ws)
        val result = tools.execute(step("delete_file", "{\"path\": \"notes.txt\"}"))
        assertTrue(result.ok)
        assertFalse(ws.files.containsKey("notes.txt"))
    }

    // ------------------------------- out-of-scope: command execution lockdown

    @Test
    fun `run_command is disabled by default`() = runTest {
        val tools = ToolRegistry(Phase1FakeWorkspace())
        val result = tools.execute(step("run_command", "{\"command\": \"ls\"}"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("temporarily disabled"))
    }

    @Test
    fun `run_command without an Android context is refused even when enabled`() = runTest {
        val tools = ToolRegistry(Phase1FakeWorkspace(), commandsEnabled = true)
        val result = tools.execute(step("run_command", "{\"command\": \"ls\"}"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("sandbox unavailable"))
    }

    @Test
    fun `command path arguments cannot escape the sandbox`() {
        for (bad in listOf(
            "cat /data/local/tmp/secret.txt",
            "cat ../../data/App.kt",
            "grep -r pattern --exclude=/etc/passwd .",
            "cat C:\\Users\\x\\file",
            "head -c 100 ./sub/file.txt",
        )) {
            val verdict = CommandIsolation.validate(bad)
            assertTrue("$bad must be denied", verdict is CommandIsolation.Verdict.Denied)
        }
    }

    @Test
    fun `bare-name arguments inside the sandbox stay allowed`() {
        val verdict = CommandIsolation.validate("grep -rn pattern .")
        assertTrue(verdict is CommandIsolation.Verdict.Allowed)
    }

    @Test
    fun `readBounded stops at the byte cap`() {
        val data = "x".repeat(200_000).toByteArray()
        val out = CommandIsolation.readBounded(ByteArrayInputStream(data), 1_024)
        assertEquals(1_024, out.length)
        val short = CommandIsolation.readBounded(ByteArrayInputStream("hi".toByteArray()), 1_024)
        assertEquals("hi", short)
    }

    // ------------------------------------------ failed save keeps edits unsaved

    @Test
    fun `failed save is not confirmed`() = runTest {
        val ws = Phase1FakeWorkspace()
        ws.files["App.kt"] = "old"
        ws.failWrites = true
        val outcome = SaveTransaction(ws).save("App.kt", "new content")
        assertFalse(outcome.confirmed)
        assertTrue(outcome.error.contains("NOT saved"))
        assertEquals("old", ws.files["App.kt"]) // nothing clobbered
    }

    @Test
    fun `diverged read-back is not confirmed as saved`() = runTest {
        val ws = Phase1FakeWorkspace()
        ws.mutateAfterWrite = { ws.files["App.kt"] = "tampered by an external editor" }
        val outcome = SaveTransaction(ws).save("App.kt", "my content")
        assertFalse(outcome.confirmed)
        assertTrue(outcome.error.contains("NOT saved"))
    }

    @Test
    fun `confirmed save verifies by read-back`() = runTest {
        val ws = Phase1FakeWorkspace()
        val outcome = SaveTransaction(ws).save("App.kt", "new content")
        assertTrue(outcome.confirmed)
        assertEquals("new content", ws.files["App.kt"])
    }

    @Test
    fun `save refuses sensitive and escaping paths`() = runTest {
        val ws = Phase1FakeWorkspace()
        val tx = SaveTransaction(ws)
        assertFalse(tx.save(".aws/credentials", "x").confirmed)
        assertFalse(tx.save("../evil.kt", "x").confirmed)
    }

    // --------------------------------- cancel ends an in-flight approval wait

    @Test
    fun `cancelPending ends the approval wait with a rejection`() = runTest {
        val gate = ApprovalGate(autoApprove = { false })
        val answer = async {
            gate.request(AgentStep(0, "write_file", "{}", "write", true), "diff")
        }
        // Give the request a moment to register its pending state.
        yield()
        assertTrue(gate.pending.value != null)
        gate.cancelPending()
        assertFalse(answer.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun `cancelPending without a pending request is a no-op`() = runTest {
        val gate = ApprovalGate(autoApprove = { false })
        gate.cancelPending()
        assertNull(gate.pending.value)
    }

    // ------------------------------------------------ session pinned to workspace

    @Test
    fun `same workspace keeps the same session`() {
        val pinner = SessionPinner()
        val first = pinner.bind("content://tree/primary%3AprojectA")
        assertEquals(first, pinner.bind("content://tree/primary%3AprojectA"))
    }

    @Test
    fun `switching workspace mints a fresh session`() {
        val pinner = SessionPinner()
        val a = pinner.bind("content://tree/primary%3AprojectA")
        val b = pinner.bind("content://tree/primary%3AprojectB")
        assertNotEquals(a, b)
        assertEquals(b, pinner.currentSessionId)
        assertTrue(pinner.pinnedWorkspace!!.contains("projectB"))
    }

    @Test
    fun `no workspace falls back to the shared local session`() {
        val pinner = SessionPinner()
        assertEquals(SessionPinner.NO_WORKSPACE_SESSION, pinner.bind(null))
        assertEquals(SessionPinner.NO_WORKSPACE_SESSION, pinner.bind(""))
    }

    // ------------------------------------------------------------------ utils

    private fun step(tool: String, args: String) = AgentStep(
        index = 0,
        tool = tool,
        argsJson = args,
        description = tool,
        isWrite = tool == "write_file" || tool == "delete_file" || tool == "run_command",
    )
}

/** In-memory [WorkspaceOps] for the phase-1 tests, extended with directory shape. */
private class Phase1FakeWorkspace : WorkspaceOps {
    val files = mutableMapOf<String, String>()
    val nodes = mutableListOf<WorkspaceRepository.FileNode>()
    val directories = mutableSetOf<String>()
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

    override suspend fun isDirectory(path: String): Boolean = path.trim('/') in directories

    override suspend fun list(path: String): List<WorkspaceRepository.FileNode> = nodes.toList()

    override suspend fun grep(pattern: String, maxFiles: Int): List<WorkspaceRepository.GrepHit> = emptyList()
}

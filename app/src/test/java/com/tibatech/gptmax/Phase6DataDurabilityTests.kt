package com.tibatech.gptmax

import com.tibatech.gptmax.core.agent.AgentCore
import com.tibatech.gptmax.core.agent.AgentEvent
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.AgentTask
import com.tibatech.gptmax.core.agent.CompletionStatus
import com.tibatech.gptmax.core.agent.RunResumeStore
import com.tibatech.gptmax.core.agent.ToolRegistry
import com.tibatech.gptmax.core.agent.WorkspaceOps
import com.tibatech.gptmax.core.agent.WriteJournal
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.ApprovalGate
import com.tibatech.gptmax.core.router.InferenceRouter
import com.tibatech.gptmax.core.router.LlmClient
import com.tibatech.gptmax.core.router.LlmRequest
import com.tibatech.gptmax.core.router.LlmResult
import com.tibatech.gptmax.core.router.LocalEngine
import com.tibatech.gptmax.core.router.ProviderConfig
import com.tibatech.gptmax.core.router.Source
import com.tibatech.gptmax.data.repo.WorkspaceRepository
import com.tibatech.gptmax.data.settings.GpTMaxSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (external review, data-safety priority) acceptance criteria:
 *
 * 1. The rollback journal is DURABLE and keeps FULL byte copies — a rollback
 *    after process death restores the original bytes exactly, never a
 *    64k-truncated version (the v2 data-loss bug).
 * 2. Resume-snapshot arg capping produces VALID JSON (never a broken
 *    mid-string cut) and a capped write is REFUSED by the tool layer.
 * 3. A resumed run verifies file state against the durable journal BEFORE
 *    re-running pending writes: an already-applied write is never duplicated,
 *    a drifted file is surfaced, not silently overwritten.
 * 4. Every mutation re-checks the file right before writing — an external
 *    edit during the approval window is detected, not clobbered (TOCTOU).
 * 5. Auto-approve never covers run_command.
 */
class Phase6DataDurabilityTests {

    // ------------------------------------------------------------ journal

    @Test
    fun `journal survives process restart with byte-exact full copies`() {
        val dir = newDir()
        // Well beyond the old 64k truncation cap.
        val big = "A".repeat(300_000) + "TAIL"
        val newBig = "new-" + big
        val first = WriteJournal(dir)
        first.record("big.txt", WriteJournal.Kind.WRITE, big, newContent = newBig, stepIndex = 3)

        // "New process": a fresh instance rebuilds its index from disk.
        val second = WriteJournal(dir)
        val entry = second.lastFor("big.txt")
        assertNotNull(entry)
        assertEquals(big, entry!!.previousContent)
        assertEquals(newBig, entry.newContent)
        assertEquals(3, entry.stepIndex)
        assertEquals(second.fingerprintOf(big), entry.fingerprintBefore)
        assertEquals(second.fingerprintOf(newBig), entry.fingerprintAfter)
    }

    @Test
    fun `memory journal keeps full copies — old 64k truncation bug fixed`() {
        val big = "x".repeat(70_000)
        val journal = WriteJournal.inMemory()
        journal.record("f.txt", WriteJournal.Kind.WRITE, big)
        assertEquals(big, journal.lastFor("f.txt")!!.previousContent)
    }

    @Test
    fun `journal prunes beyond maxEntries and rebuilds a bounded index`() {
        val dir = newDir()
        val journal = WriteJournal(dir, maxEntries = 3)
        repeat(6) { i -> journal.record("f$i.txt", WriteJournal.Kind.WRITE, "c$i") }
        assertEquals(3, journal.size)
        // Fresh process sees the same bounded, newest-first state.
        val rebooted = WriteJournal(dir, maxEntries = 3)
        assertEquals(3, rebooted.size)
        assertEquals("c5", rebooted.lastFor("f5.txt")!!.previousContent)
        assertNull(rebooted.lastFor("f0.txt"))
    }

    // ------------------------------------------------- resume arg capping

    @Test
    fun `oversized args are capped to VALID JSON with the sentinel`() {
        val store = RunResumeStore(newDir(), maxStepArgsChars = 100)
        val big = "{\"path\": \"out.txt\", \"content\": \"" + "x".repeat(10_000) + "\"}"
        store.save(RunResumeStore.from(listOf(step(0, "write_file", big)), "t", "g", null, "ACTING", 0))
        val loaded = store.load()!!
        // MUST parse — the old blind truncation produced invalid JSON here.
        val obj = Json.parseToJsonElement(loaded.steps[0].argsJson).jsonObject
        val content = obj["content"]!!.jsonPrimitive.content
        assertTrue(content.endsWith(RunResumeStore.TRUNCATED_MARK))
        assertTrue(loaded.steps[0].result.contains("capped for resume"))
    }

    @Test
    fun `tool layer refuses a resume-capped write (fail-closed)`() = runTest {
        val ws = FakeWorkspace()
        val registry = ToolRegistry(ws)
        val args = "{\"path\": \"f.txt\", \"content\": \"abc${RunResumeStore.TRUNCATED_MARK}\"}"
        val result = registry.execute(step(0, "write_file", args))
        assertFalse(result.ok)
        assertTrue(result.output.contains("refusing"))
        assertNull(ws.files["f.txt"]) // nothing was written
    }

    @Test
    fun `tool layer refuses a resume-capped patch (fail-closed)`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "fun main() {\n    println(1)\n}\n"
        val registry = ToolRegistry(ws)
        val args = "{\"path\": \"App.kt\", \"diff\": \"--- a\\n${RunResumeStore.TRUNCATED_MARK}\"}"
        val result = registry.execute(step(0, "patch_file", args))
        assertFalse(result.ok)
        assertTrue(result.output.contains("refusing"))
        assertEquals("fun main() {\n    println(1)\n}\n", ws.files["App.kt"]) // untouched
    }

    // ------------------------------------------------------- TOCTOU guard

    @Test
    fun `write refused when the file changed between journaling and execution`() = runTest {
        val ws = DriftingWorkspace()
        val registry = ToolRegistry(ws)
        val result = registry.execute(
            step(0, "write_file", "{\"path\": \"f.txt\", \"content\": \"agent text\"}"),
        )
        assertFalse(result.ok)
        assertTrue(result.output.contains("changed between"))
        assertNull(ws.written) // the mutation never happened
    }

    @Test
    fun `delete refused when the file changed between journaling and execution`() = runTest {
        val ws = DriftingWorkspace()
        val registry = ToolRegistry(ws)
        val result = registry.execute(
            step(0, "delete_file", "{\"path\": \"f.txt\"}"),
        )
        assertFalse(result.ok)
        assertTrue(result.output.contains("changed between"))
        assertFalse(ws.deleted)
    }

    // ------------------------------------------------- resume verification

    @Test
    fun `resumed run skips a write the journal proves already applied`() = runTest {
        val journalDir = newDir()
        val storeDir = newDir()
        val ws = FakeWorkspace()
        // The patch ALREADY landed before the (simulated) process death.
        ws.files["App.kt"] = APP_KT_PATCHED

        val journal = WriteJournal(journalDir)
        val store = RunResumeStore(storeDir)
        journal.record(
            "App.kt",
            WriteJournal.Kind.WRITE,
            APP_KT,
            newContent = APP_KT_PATCHED,
            stepIndex = 1,
        )
        store.save(
            RunResumeStore.from(
                steps = listOf(
                    step(0, "read_file", "{\"path\": \"App.kt\"}", "DONE", "read ok"),
                    step(1, "patch_file", patchArgs(), "PENDING"),
                    step(2, "finish", "{\"summary\": \"patched\"}"),
                ),
                taskId = "t6",
                goal = "patch App.kt",
                workspaceUri = "ws://A",
                phase = "ACTING",
                startedAt = 1,
            ),
        )

        val core = makeCore(ws, store, journal, review = "VERDICT: APPROVED")
        core.run(task(), store.load()!!)
        val events = core.events.replayCache.toList()

        assertTrue(events.filterIsInstance<AgentEvent.Log>()
            .any { it.message.contains("already applied — skipped") })
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(CompletionStatus.DONE, completed.status)
        assertNull(store.load()) // a DONE run clears its snapshot
    }

    @Test
    fun `resumed run surfaces a drifted file instead of silently rewriting`() = runTest {
        val journalDir = newDir()
        val storeDir = newDir()
        val ws = FakeWorkspace()
        // The file changed AFTER the interruption — matches neither the
        // journal's before- nor after-state.
        ws.files["App.kt"] = "externally edited"
        val args = patchArgs()

        val journal = WriteJournal(journalDir)
        journal.record(
            "App.kt",
            WriteJournal.Kind.WRITE,
            APP_KT,
            newContent = APP_KT_PATCHED,
            stepIndex = 1,
        )
        val store = RunResumeStore(storeDir)
        store.save(
            RunResumeStore.from(
                steps = listOf(
                    step(1, "patch_file", args, "PENDING"),
                    step(2, "finish", "{\"summary\": \"patched\"}"),
                ),
                taskId = "t6b",
                goal = "patch App.kt",
                workspaceUri = "ws://A",
                phase = "ACTING",
                startedAt = 1,
            ),
        )

        val core = makeCore(ws, store, journal, review = "VERDICT: APPROVED")
        core.run(task("t6b"), store.load()!!)
        val events = core.events.replayCache.toList()

        assertTrue(events.filterIsInstance<AgentEvent.Log>()
            .any { it.message.contains("differs from both") })
        // The stale patch must NOT have clobbered the external edit.
        assertEquals("externally edited", ws.files["App.kt"])
    }

    // ------------------------------------------------------ auto-approve

    @Test
    fun `auto-approve never covers run_command`() = runTest {
        val gate = ApprovalGate { true }
        // Writes ARE auto-approved.
        assertTrue(withTimeout(2_000) { gate.request(step(0, "write_file", "{}"), "p") })

        // Commands are NOT — the request suspends until a human responds.
        val answered = CompletableDeferred<Boolean>()
        val job = launch { answered.complete(gate.request(step(0, "run_command", "{}"), "p")) }
        yield()
        yield()
        assertTrue(gate.pending.value != null) // a real human card appeared
        gate.respond(false)
        job.join()
        assertFalse(answered.await())
    }

    @Test
    fun `write with blank path is refused`() = runTest {
        val registry = ToolRegistry(FakeWorkspace())
        val result = registry.execute(step(0, "write_file", "{\"path\": \"\", \"content\": \"x\"}"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("empty path"))
    }

    // ------------------------------------------------------------ harness

    private class FakeWorkspace : WorkspaceOps {
        val files = mutableMapOf<String, String>()

        override suspend fun read(path: String): String? = files[path]

        override suspend fun write(path: String, content: String): Boolean {
            files[path] = content
            return true
        }

        override suspend fun delete(path: String): Boolean = files.remove(path) != null

        override suspend fun list(path: String): List<WorkspaceRepository.FileNode> = emptyList()

        override suspend fun grep(pattern: String, maxFiles: Int): List<WorkspaceRepository.GrepHit> =
            emptyList()
    }

    /**
     * First read returns the journaled content; every later read returns a
     * DIFFERENT value — simulates an external edit landing between the
     * journal read and the pre-write re-check (the TOCTOU window).
     */
    private class DriftingWorkspace : WorkspaceOps {
        val files = mutableMapOf("f.txt" to "original")
        private var reads = 0
        var written: String? = null
        var deleted = false

        override suspend fun read(path: String): String? {
            val v = if (reads == 0) files[path] else "DRIFTED-EXTERNAL-EDIT"
            reads += 1
            return v
        }

        override suspend fun write(path: String, content: String): Boolean {
            written = content
            return true
        }

        override suspend fun delete(path: String): Boolean {
            deleted = true
            return true
        }

        override suspend fun list(path: String): List<WorkspaceRepository.FileNode> = emptyList()

        override suspend fun grep(pattern: String, maxFiles: Int): List<WorkspaceRepository.GrepHit> =
            emptyList()
    }

    private class ScriptedClient(
        private val plan: String = "[]",
        private val review: String,
    ) : LlmClient {
        override val source = Source.CLOUD

        override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult {
            val isPlan = request.messages.firstOrNull()?.content?.contains("planning brain") == true
            val text = if (isPlan) plan else review
            onDelta(text)
            return LlmResult(text, source)
        }
    }

    private fun makeCore(
        ws: FakeWorkspace,
        store: RunResumeStore,
        journal: WriteJournal,
        review: String,
    ): AgentCore = AgentCore(
        router = InferenceRouter(
            { settings() },
            LocalEngine(),
            ScriptedClient(review = review),
        ),
        tools = ToolRegistry(ws, journal = journal),
        context = ContextBuilder(ws),
        approval = ApprovalGate { true },
        resumeStore = store,
    )

    private fun task(id: String = "t6") = AgentTask(id = id, goal = "patch App.kt", workspaceUri = "ws://A")

    private fun settings() = GpTMaxSettings(
        provider = ProviderConfig(apiKey = "test-key"),
        cloudFirst = true,
        cloudFirstAct = false,
        autoApproveWrites = false,
        localModelPath = "",
        nCtx = 4096,
        nThreads = 2,
    )

    private fun patchArgs(): String {
        val diff = """
            --- a/App.kt
            +++ b/App.kt
            @@ -1,3 +1,3 @@
             fun main() {
            -    println(1)
            +    println(2)
             }
        """.trimIndent()
        return """{"path": "App.kt", "diff": "${diff.replace("\n", "\\n")}"}"""
    }

    private fun step(
        index: Int,
        tool: String,
        args: String,
        status: String = "PENDING",
        result: String = "",
    ) = AgentStep(
        index,
        tool,
        args,
        tool,
        tool == "write_file" || tool == "patch_file" || tool == "delete_file" ||
            tool == "run_command",
    ).apply {
        runCatching { AgentStep.Status.valueOf(status) }.getOrDefault(AgentStep.Status.PENDING)
            .let { this.status = it }
        this.result = result
    }

    private fun newDir(): File = Files.createTempDirectory("gptmax-p6").toFile()

    private companion object {
        const val APP_KT = "fun main() {\n    println(1)\n}\n"
        const val APP_KT_PATCHED = "fun main() {\n    println(2)\n}\n"
    }
}

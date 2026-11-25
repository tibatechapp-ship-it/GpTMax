package com.tibatech.gptmax

import com.tibatech.gptmax.core.agent.AgentCore
import com.tibatech.gptmax.core.agent.AgentEvent
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.AgentTask
import com.tibatech.gptmax.core.agent.RunResumeStore
import com.tibatech.gptmax.core.agent.ToolRegistry
import com.tibatech.gptmax.core.agent.WorkspaceOps
import com.tibatech.gptmax.core.agent.toAgentSteps
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 3 (external review, resumability) acceptance criteria:
 * - the plan and per-step statuses persist across a (simulated) process
 *   death and RESTORE correctly;
 * - a resumed run NEVER re-executes a step already marked DONE;
 * - every pending write re-passes the human approval gate;
 * - a COMPLETED run clears the snapshot; a CANCELLED run stays resumable;
 * - corrupted or oversized snapshots degrade safely instead of crashing.
 */
class Phase3ResumabilityTests {

    // ------------------------------------------------------------- snapshots

    @Test
    fun `snapshot roundtrips through disk`() {
        val store = RunResumeStore(newDir())
        store.save(
            RunResumeStore.from(
                steps = listOf(
                    step(0, "write_file", "{\"path\": \"a.txt\", \"content\": \"x\"}", "DONE", "ok"),
                    step(1, "read_file", "{\"path\": \"a.txt\"}", "PENDING", ""),
                ),
                taskId = "t-1",
                goal = "write a file",
                workspaceUri = "content://tree/primary%3AprojectA",
                phase = "ACTING",
                startedAt = 1,
            ),
        )
        val loaded = store.load()!!
        assertEquals("t-1", loaded.taskId)
        assertEquals("content://tree/primary%3AprojectA", loaded.workspaceUri)
        assertEquals(2, loaded.steps.size)
        assertEquals("write_file", loaded.steps[0].tool)
        assertEquals("DONE", loaded.steps[0].status)
        assertTrue(loaded.steps[0].isWrite)
        assertEquals("PENDING", loaded.steps[1].status)
        assertFalse(loaded.steps[1].isWrite)
    }

    @Test
    fun `corrupted snapshot loads as null and self-heals`() {
        val dir = newDir()
        val store = RunResumeStore(dir)
        store.save(RunResumeStore.from(emptyList(), "t", "g", null, "ACTING", 0))
        assertTrue(store.exists)
        File(dir, RunResumeStore.FILE_NAME).writeText("{ definitely not json !!!", Charsets.UTF_8)
        assertNull(store.load())
        assertFalse("corrupted file must be removed", store.exists)
    }

    @Test
    fun `missing snapshot loads as null`() {
        assertNull(RunResumeStore(newDir()).load())
    }

    @Test
    fun `oversized args are capped to valid JSON with the sentinel`() {
        val store = RunResumeStore(newDir(), maxStepArgsChars = 100)
        val big = "{\"content\": \"" + "x".repeat(10_000) + "\"}"
        store.save(RunResumeStore.from(listOf(step(0, "write_file", big)), "t", "g", null, "ACTING", 0))
        val loaded = store.load()!!
        // Phase 6: the capped args MUST still parse — the old blind string
        // truncation produced invalid JSON that failed to load on resume.
        val content = Json.parseToJsonElement(loaded.steps[0].argsJson)
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.endsWith(RunResumeStore.TRUNCATED_MARK))
        assertTrue(loaded.steps[0].result.contains("capped for resume"))
    }

    @Test
    fun `unknown statuses degrade to PENDING on restore`() {
        val store = RunResumeStore(newDir())
        store.save(RunResumeStore.from(listOf(step(0, "read_file", "{}", "BOGUS", "")), "t", "g", null, "ACTING", 0))
        val restored = store.load()!!.toAgentSteps()
        assertEquals(AgentStep.Status.PENDING, restored[0].status)
    }

    // -------------------------------------------------------- resume behavior

    @Test
    fun `resume skips completed steps and finishes the run`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "class App"
        val store = RunResumeStore(newDir())
        // Simulate a process death AFTER step 0 (read) completed.
        store.save(
            RunResumeStore.from(
                steps = listOf(
                    step(0, "read_file", "{\"path\": \"App.kt\"}", "DONE", "read ok"),
                    step(1, "write_file", "{\"path\": \"out.txt\", \"content\": \"hello\"}"),
                    step(2, "finish", "{\"summary\": \"done\"}"),
                ),
                taskId = "t-resume",
                goal = "g",
                workspaceUri = "ws://A",
                phase = "ACTING",
                startedAt = 1,
            ),
        )
        val core = makeCore(ws, store)
        val snapshot = store.load()!!
        core.run(AgentTask("t-resume", "g", workspaceUri = "ws://A"), resume = snapshot)

        // Step 0 was NEVER re-executed: no StepStarted for it.
        val started = core.events.replayCache.filterIsInstance<AgentEvent.StepStarted>()
        assertTrue("step 0 must be skipped", started.none { it.step.index == 0 })
        assertTrue("step 1 must run", started.any { it.step.index == 1 })

        // The Resumed event reports 1 of 3 steps already done.
        assertTrue(
            core.events.replayCache.any {
                it is AgentEvent.Resumed && it.fromStep == 1 && it.totalSteps == 3
            },
        )

        // The pending write executed through the (auto-approved) gate.
        assertEquals("hello", ws.files["out.txt"])

        // A completed run clears the snapshot.
        assertFalse(store.exists)
    }

    @Test
    fun `cancelled run keeps its snapshot resumable`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = "class App"
        val store = RunResumeStore(newDir())
        val cloud = ScriptedClient()
        val router = InferenceRouter({ settings() }, LocalEngine(), cloud)
        // Human-in-the-loop: the write waits for approval; nobody answers.
        val gate = ApprovalGate { false }
        val core = AgentCore(router, ToolRegistry(ws), ContextBuilder(ws), gate, resumeStore = store)

        val job = launch { core.run(AgentTask("t-cancel", "g", workspaceUri = "ws://A")) }
        withTimeout(10_000) { while (gate.pending.value == null) yield() }
        core.cancel()
        gate.cancelPending()
        job.join()

        assertTrue("cancelled run must stay resumable", store.exists)
        val snapshot = store.load()!!
        assertEquals("t-cancel", snapshot.taskId)
        // The interrupted write step is not DONE — it will re-run on resume.
        val write = snapshot.steps.first { it.tool == "write_file" }
        assertTrue(write.status != "DONE")
        store.clear()
    }

    // ------------------------------------------------------------------ utils

    private fun newDir(): File = Files.createTempDirectory("gptmax-resume").toFile()

    private fun step(
        index: Int,
        tool: String,
        args: String,
        status: String = "PENDING",
        result: String = "",
    ) = AgentStep(index, tool, args, tool, tool == "write_file" || tool == "delete_file" ||
        tool == "run_command").apply {
        runCatching { AgentStep.Status.valueOf(status) }.getOrDefault(AgentStep.Status.PENDING)
            .let { this.status = it }
        this.result = result
    }

    private fun settings() = GpTMaxSettings(
        provider = ProviderConfig(apiKey = "test-key"),
        cloudFirst = true,
        cloudFirstAct = false,
        autoApproveWrites = false,
        localModelPath = "",
        nCtx = 4096,
        nThreads = 2,
    )

    private fun makeCore(ws: FakeWorkspace, store: RunResumeStore): AgentCore = AgentCore(
        router = InferenceRouter({ settings() }, LocalEngine(), ScriptedClient()),
        tools = ToolRegistry(ws),
        context = ContextBuilder(ws),
        approval = ApprovalGate { true },
        resumeStore = store,
    )

    /** Scripted cloud client: plans with three steps, approves everything. */
    private class ScriptedClient : LlmClient {
        override val source = Source.CLOUD

        override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult {
            val isPlan = request.messages.firstOrNull()?.content?.contains("planning brain") == true
            val text = if (isPlan) DEFAULT_PLAN else "APPROVED"
            onDelta(text)
            return LlmResult(text, source)
        }

        companion object {
            val DEFAULT_PLAN = """
                [
                  {"tool": "read_file", "args": {"path": "App.kt"}, "why": "read the app file"},
                  {"tool": "write_file", "args": {"path": "out.txt", "content": "hello"}, "why": "write output"},
                  {"tool": "finish", "args": {"summary": "done"}, "why": "end"}
                ]
            """.trimIndent()
        }
    }

    /** In-memory [WorkspaceOps] for JVM tests (same shape as phase 1 fakes). */
    private class FakeWorkspace : WorkspaceOps {
        val files = mutableMapOf<String, String>()
        val nodes = mutableListOf<WorkspaceRepository.FileNode>()

        override suspend fun read(path: String): String? = files[path]

        override suspend fun write(path: String, content: String): Boolean {
            files[path] = content
            return true
        }

        override suspend fun delete(path: String): Boolean = files.remove(path) != null

        override suspend fun list(path: String): List<WorkspaceRepository.FileNode> = nodes.toList()

        override suspend fun grep(pattern: String, maxFiles: Int): List<WorkspaceRepository.GrepHit> =
            emptyList()
    }
}

package com.tibatech.gptmax

import com.tibatech.gptmax.core.agent.AgentCore
import com.tibatech.gptmax.core.agent.AgentEvent
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.AgentTask
import com.tibatech.gptmax.core.agent.CompletionStatus
import com.tibatech.gptmax.core.agent.ReviewClassifier
import com.tibatech.gptmax.core.agent.RunResumeStore
import com.tibatech.gptmax.core.agent.ToolRegistry
import com.tibatech.gptmax.core.agent.UnifiedDiff
import com.tibatech.gptmax.core.agent.WorkspaceOps
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.ApprovalGate
import com.tibatech.gptmax.core.guardrails.SensitivePaths
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (external-review roadmap M5) acceptance criteria:
 *
 * - UnifiedDiff: a stale hunk is REJECTED with the file untouched (patch
 *   validation + conflict detection); drifted line numbers still land;
 *   application is atomic — all hunks or none.
 * - patch_file: journal + read-back verified like write_file; refuses
 *   missing files and sensitive paths; the tool result carries the unified
 *   diff of what it changed.
 * - Honest outcomes: a failed step can never report DONE (PARTIAL), a
 *   rejected write is BLOCKED, a reviewer demanding changes is
 *   VERIFY_FAILED, an inconclusive review stays unverified — and only DONE
 *   clears the resumable snapshot.
 * - Review evidence: the validator request contains the REAL unified diff
 *   of applied changes, not just file names.
 * - ReviewClassifier: VERDICT protocol, legacy APPROVED, negative language,
 *   explicit uncertainty.
 */
class Phase5AdaptiveAgentTests {

    // ------------------------------------------------------- UnifiedDiff.apply

    @Test
    fun `single hunk applies at its stated position`() {
        val original = "line1\nline2\nline3\nline4\nline5\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -2,3 +2,3 @@
             line2
            -line3
            +line3 CHANGED
             line4
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("line1\nline2\nline3 CHANGED\nline4\nline5\n", r.content)
        assertEquals(1, r.hunksApplied)
    }

    @Test
    fun `drifted hunk is found within the window`() {
        val original = (1..20).joinToString("\n") { "L$it" } + "\n"
        // Stated old start is 4 but the block actually lives at lines 9-11.
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -4,3 +4,3 @@
             L9
            -L10
            +L10 patched
             L11
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertTrue(r.content!!.contains("L10 patched"))
        assertEquals("still 20 lines", 20, r.content!!.trimEnd('\n').lines().size)
    }

    @Test
    fun `stale context rejects the whole patch`() {
        val original = "a\nb\nc\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             a
            -WRONG
            +fixed
             c
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertFalse(r.ok)
        assertNotNull(r.error)
        assertTrue("actionable error", r.error!!.contains("regenerate"))
        assertNull("nothing is produced on failure", r.content)
    }

    @Test
    fun `multi hunk patch applies in order`() {
        val original = "a\nb\nc\nd\ne\nf\ng\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
            @@ -6,2 +6,2 @@
             f
            -g
            +G
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("a\nB\nc\nd\ne\nf\nG\n", r.content)
        assertEquals(2, r.hunksApplied)
    }

    @Test
    fun `second hunk mismatch rejects everything atomically`() {
        val original = "a\nb\nc\nd\ne\nf\ng\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
            @@ -6,2 +6,2 @@
             f
            -STALE
            +G
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertFalse("atomic: no partial application", r.ok)
        assertNull(r.content)
    }

    @Test
    fun `crlf file still matches lf generated patches`() {
        val original = "a\r\nb\r\nc\r\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -2,2 +2,2 @@
             b
            -c
            +C
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        // Context line b keeps its CRLF bytes; replaced lines come from the patch (LF).
        assertEquals("a\r\nb\nC\n", r.content)
    }

    @Test
    fun `append at eof gains a trailing newline`() {
        val original = "x\ny"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,4 @@
             x
             y
            +z
            +w
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("x\ny\nz\nw\n", r.content)
    }

    @Test
    fun `no newline markers are honored on both sides`() {
        val original = "x\ny"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             x
            -y
            \ No newline at end of file
            +Y
            \ No newline at end of file
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("x\nY", r.content)
    }

    @Test
    fun `old eof without newline plus replaced eof line gains newline`() {
        // GNU semantics: marker after '-y' only means the OLD file lacked the
        // newline; the '+' replacement line (no marker) ends WITH one.
        val original = "x\ny"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             x
            -y
            \ No newline at end of file
            +Y
        """.trimIndent()
        val r = UnifiedDiff.apply(original, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("x\nY\n", r.content)
    }

    @Test
    fun `insert only hunk works on an empty file`() {
        val diff = """
            --- a/new.txt
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +alpha
            +beta
        """.trimIndent()
        val r = UnifiedDiff.apply("", diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals("alpha\nbeta\n", r.content)
    }

    @Test
    fun `render then apply roundtrips exactly`() {
        val before = (1..30).joinToString("\n") { "line $it" } + "\n"
        val after = before
            .replace("line 10\n", "line 10 edited\n")
            .replace("line 25\n", "line 25 edited\n")
        val diff = UnifiedDiff.render("f.kt", before, after)
        assertTrue("render produced a hunk", diff.contains("@@"))
        val r = UnifiedDiff.apply(before, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals(after, r.content)
    }

    @Test
    fun `render keeps no trailing newline state through roundtrip`() {
        val before = "x\ny"
        val after = "x\nY"
        val diff = UnifiedDiff.render("f.txt", before, after)
        val r = UnifiedDiff.apply(before, diff)
        assertTrue(r.error ?: "ok", r.ok)
        assertEquals(after, r.content)
    }

    @Test
    fun `malformed and structural inputs are rejected`() {
        assertFalse(UnifiedDiff.apply("a\n", "").ok)
        assertFalse(UnifiedDiff.apply("a\n", "prose only, no hunks").ok)
        val multiFile = """
            --- a/one.txt
            +++ b/one.txt
            @@ -1 +1 @@
            -x
            +y
            --- a/two.txt
            +++ b/two.txt
            @@ -1 +1 @@
            -p
            +q
        """.trimIndent()
        val r = UnifiedDiff.apply("x\n", multiFile)
        assertFalse("one file per patch", r.ok)
        assertTrue(r.error!!.contains("multi-file"))
    }

    // ------------------------------------------------------ ReviewClassifier

    @Test
    fun `verdict protocol decides outright`() {
        assertEquals(
            ReviewClassifier.Verdict.APPROVED,
            ReviewClassifier.classify("VERDICT: APPROVED\nThe change is safe."),
        )
        assertEquals(
            ReviewClassifier.Verdict.CHANGES_NEEDED,
            ReviewClassifier.classify("VERDICT: CHANGES_NEEDED\nMissing null check in patch."),
        )
    }

    @Test
    fun `legacy one word approval is still recognized`() {
        assertEquals(
            ReviewClassifier.Verdict.APPROVED,
            ReviewClassifier.classify("APPROVED"),
        )
    }

    @Test
    fun `negative language maps to changes needed`() {
        assertEquals(
            ReviewClassifier.Verdict.CHANGES_NEEDED,
            ReviewClassifier.classify("The change is rejected: imports are broken."),
        )
        assertEquals(
            ReviewClassifier.Verdict.CHANGES_NEEDED,
            ReviewClassifier.classify("This fails on empty input and must be fixed."),
        )
    }

    @Test
    fun `inconclusive reviews never pass`() {
        assertEquals(
            ReviewClassifier.Verdict.INCONCLUSIVE,
            ReviewClassifier.classify("Looks fine overall I guess, nice formatting."),
        )
        assertEquals(
            ReviewClassifier.Verdict.INCONCLUSIVE,
            ReviewClassifier.classify("not approved yet"),
        )
        assertEquals(
            ReviewClassifier.Verdict.INCONCLUSIVE,
            ReviewClassifier.classify(""),
        )
    }

    // --------------------------------------------------- patch_file via tools

    @Test
    fun `patch_file applies journals and reports the diff`() = runTest {
        val ws = FakeWorkspace()
        ws.files["f.txt"] = "a\nb\nc\n"
        val registry = ToolRegistry(ws)
        val result = registry.execute(step("patch_file", patchArgs("f.txt", AB_PATCH)))
        assertTrue(result.output, result.ok)
        assertEquals("a\nB\nc\n", ws.files["f.txt"])
        assertNotNull("tool result carries review evidence", result.diff)
        assertTrue(result.diff!!.contains("-b"))
        assertTrue(result.diff!!.contains("+B"))
        // Rollback journal recorded the previous content.
        assertEquals(1, registry.journal.size)
        assertEquals("a\nb\nc\n", registry.journal.lastFor("f.txt")!!.previousContent)
    }

    @Test
    fun `stale patch leaves the file untouched with no journal entry`() = runTest {
        val ws = FakeWorkspace()
        ws.files["f.txt"] = "a\nb\nc\n"
        val registry = ToolRegistry(ws)
        val stale = AB_PATCH.replace("-b", "-STALE")
        val result = registry.execute(step("patch_file", patchArgs("f.txt", stale)))
        assertFalse(result.ok)
        assertTrue(result.output.contains("patch failed"))
        assertEquals("unchanged", "a\nb\nc\n", ws.files["f.txt"])
        assertEquals("fail-closed: no journal entry", 0, registry.journal.size)
    }

    @Test
    fun `patch_file refuses missing and sensitive targets`() = runTest {
        val ws = FakeWorkspace()
        val registry = ToolRegistry(ws)
        val missing = registry.execute(step("patch_file", patchArgs("ghost.txt", AB_PATCH)))
        assertFalse(missing.ok)
        assertTrue(missing.output.contains("file not found"))

        ws.files[".env"] = "SECRET=1\n"
        val sensitive = registry.execute(step("patch_file", patchArgs(".env", AB_PATCH)))
        assertFalse("sensitive paths are unwritable", sensitive.ok)
        assertEquals("SECRET=1\n", ws.files[".env"])
        assertTrue(SensitivePaths.isSensitive(".env"))
    }

    @Test
    fun `patch_file is write class and goes through the gate`() {
        val parsed = com.tibatech.gptmax.core.agent.PlanParser.parse(
            """[{"tool": "patch_file", "args": {"path": "f", "diff": "d"}, "why": "w"}]""",
        )
        assertTrue(parsed.single().isWrite)
    }

    // ---------------------------------------------------- agent honest status

    @Test
    fun `clean run with explicit approval reports DONE and clears the snapshot`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val core = makeCore(ws, store, review = "VERDICT: APPROVED")
        core.run(AgentTask("t-done", "patch the app file", workspaceUri = "ws://A"))

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(CompletionStatus.DONE, completed.status)
        // The patch actually landed.
        assertEquals(APP_KT_PATCHED, ws.files["App.kt"])
        // Only DONE clears the snapshot.
        assertFalse(store.exists)
    }

    @Test
    fun `failed step makes the run PARTIAL and keeps it resumable`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val plan = """
            [
              {"tool": "read_file", "args": {"path": "Nope.kt"}, "why": "read missing file"},
              {"tool": "finish", "args": {"summary": "done"}, "why": "end"}
            ]
        """.trimIndent()
        val core = makeCore(ws, store, review = "VERDICT: APPROVED", plan = plan)
        core.run(AgentTask("t-partial", "g", workspaceUri = "ws://A"))

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("a failed step is never DONE", CompletionStatus.PARTIAL, completed.status)
        assertTrue(completed.summary.contains("1 step(s) failed"))
        assertTrue("partial runs stay resumable", store.exists)
    }

    @Test
    fun `rejected write blocks the run`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val gate = ApprovalGate { false } // human-in-the-loop: nobody auto-approves
        val cloud = ScriptedClient(review = "VERDICT: APPROVED")
        val core = AgentCore(
            InferenceRouter({ settings() }, LocalEngine(), cloud),
            ToolRegistry(ws),
            ContextBuilder(ws),
            gate,
            resumeStore = store,
        )
        val job = launch { core.run(AgentTask("t-blocked", "g", workspaceUri = "ws://A")) }
        withTimeout(10_000) { while (gate.pending.value == null) yield() }
        gate.respond(false) // the user REJECTS the write
        job.join()

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(CompletionStatus.BLOCKED, completed.status)
        assertEquals("write never happened", APP_KT, ws.files["App.kt"])
        assertTrue("blocked runs stay resumable", store.exists)
    }

    @Test
    fun `reviewer demanding changes yields VERIFY_FAILED`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val core = makeCore(ws, store, review = "VERDICT: CHANGES_NEEDED missing test")
        core.run(AgentTask("t-verify", "g", workspaceUri = "ws://A"))

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(CompletionStatus.VERIFY_FAILED, completed.status)
        assertTrue(completed.summary.contains("reviewer requested changes"))
        assertTrue(store.exists)
    }

    @Test
    fun `inconclusive review keeps the run unverified`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val core = makeCore(ws, store, review = "looks fine overall I guess")
        core.run(AgentTask("t-unverified", "g", workspaceUri = "ws://A"))

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("unverified is not DONE", CompletionStatus.PARTIAL, completed.status)
        assertTrue(completed.summary.contains("unverified"))
        assertTrue(store.exists)
    }

    @Test
    fun `truncated plan cannot be DONE`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val store = RunResumeStore(newDir())
        val cloud = ScriptedClient(plan = PATCH_PLAN, review = "VERDICT: APPROVED")
        val core = AgentCore(
            InferenceRouter({ settings() }, LocalEngine(), cloud),
            ToolRegistry(ws),
            ContextBuilder(ws),
            ApprovalGate { true },
            resumeStore = store,
            maxSteps = 2, // cuts the plan before its finish step
        )
        core.run(AgentTask("t-trunc", "g", workspaceUri = "ws://A"))

        val completed = core.events.replayCache.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(CompletionStatus.PARTIAL, completed.status)
        assertTrue(completed.summary.contains("truncated"))
        assertTrue(store.exists)
    }

    @Test
    fun `reviewer receives the real unified diff as evidence`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val cloud = ScriptedClient(review = "VERDICT: APPROVED")
        val core = AgentCore(
            InferenceRouter({ settings() }, LocalEngine(), cloud),
            ToolRegistry(ws),
            ContextBuilder(ws),
            ApprovalGate { true },
        )
        core.run(AgentTask("t-evidence", "g", workspaceUri = "ws://A"))

        val validateRequest = cloud.requests.last()
        val input = validateRequest.messages.single().content
        assertTrue("evidence section present", input.contains("Applied changes (unified diff)"))
        assertTrue("the removed line is in the evidence", input.contains("-    println(1)"))
        assertTrue("the added line is in the evidence", input.contains("+    println(2)"))
    }

    @Test
    fun `patch preview shows the diff before approval`() = runTest {
        val ws = FakeWorkspace()
        ws.files["App.kt"] = APP_KT
        val core = makeCore(ws, RunResumeStore(newDir()), review = "VERDICT: APPROVED")
        core.run(AgentTask("t-preview", "g", workspaceUri = "ws://A"))

        val approval = core.events.replayCache
            .filterIsInstance<AgentEvent.ApprovalRequired>()
            .single()
        assertTrue(approval.diffPreview.startsWith("PATCH App.kt"))
        assertTrue(approval.diffPreview.contains("-    println(1)"))
    }

    // ------------------------------------------------------------------ utils

    private fun newDir(): File = Files.createTempDirectory("gptmax-phase5").toFile()

    private fun step(tool: String, args: String) = AgentStep(0, tool, args, tool, true)

    private fun patchArgs(path: String, diff: String): String =
        """{"path": "$path", "diff": "${diff.replace("\n", "\\n")}"}"""

    private fun settings() = GpTMaxSettings(
        provider = ProviderConfig(apiKey = "test-key"),
        cloudFirst = true,
        cloudFirstAct = false,
        autoApproveWrites = false,
        localModelPath = "",
        nCtx = 4096,
        nThreads = 2,
    )

    private fun makeCore(
        ws: FakeWorkspace,
        store: RunResumeStore,
        review: String,
        plan: String = PATCH_PLAN,
    ): AgentCore = AgentCore(
        router = InferenceRouter({ settings() }, LocalEngine(), ScriptedClient(plan, review)),
        tools = ToolRegistry(ws),
        context = ContextBuilder(ws),
        approval = ApprovalGate { true },
        resumeStore = store,
    )

    private class ScriptedClient(
        private val plan: String = PATCH_PLAN,
        private val review: String = "VERDICT: APPROVED",
    ) : LlmClient {
        override val source = Source.CLOUD
        val requests = mutableListOf<LlmRequest>()

        override suspend fun stream(request: LlmRequest, onDelta: (String) -> Unit): LlmResult {
            requests += request
            val isPlan = request.messages.firstOrNull()?.content?.contains("planning brain") == true
            val text = if (isPlan) plan else review
            onDelta(text)
            return LlmResult(text, source)
        }
    }

    /** In-memory [WorkspaceOps] (same shape as the phase 1/3 fakes). */
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

    companion object {
        const val APP_KT = "fun main() {\n    println(1)\n}\n"
        const val APP_KT_PATCHED = "fun main() {\n    println(2)\n}\n"

        val AB_PATCH = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
        """.trimIndent()

        /** Plan that patches App.kt (println 1 -> 2) through the approval gate. */
        val PATCH_PLAN: String = run {
            val diff = """
                --- a/App.kt
                +++ b/App.kt
                @@ -1,3 +1,3 @@
                 fun main() {
                -    println(1)
                +    println(2)
                 }
            """.trimIndent()
            val args = """{"path": "App.kt", "diff": "${diff.replace("\n", "\\n")}"}"""
            """
                [
                  {"tool": "read_file", "args": {"path": "App.kt"}, "why": "read the app file"},
                  {"tool": "patch_file", "args": $args, "why": "bump the println"},
                  {"tool": "finish", "args": {"summary": "patched"}, "why": "end"}
                ]
            """.trimIndent()
        }
    }
}

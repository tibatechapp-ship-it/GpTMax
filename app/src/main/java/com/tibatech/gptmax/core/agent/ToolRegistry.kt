package com.tibatech.gptmax.core.agent

import android.content.Context
import com.tibatech.gptmax.core.guardrails.CommandIsolation
import com.tibatech.gptmax.core.guardrails.CommandSandbox
import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool surface exposed to the model. Read tools execute immediately inside
 * the loop; write tools are routed through the ApprovalGate first.
 *
 * Review #1: reads, listings and grep hits all funnel through SensitivePaths
 * so .aws, .ssh, dot-env files and keystore content never reach the model context.
 * Review #2: every write/delete is journaled with a pre-change fingerprint
 * and a rollback copy, then verified by read-back.
 * Review #6: PathGuard confines every path argument to the granted workspace,
 * PerformanceMonitor times every execution, and the run_command tool only
 * runs allowlisted, read-only binaries inside an isolated sandbox after
 * explicit user approval.
 *
 * Phase 1 (external review, data-protection lockdown):
 * - runSandboxed drains the output stream on a daemon thread with a hard
 *   byte cap, concurrent with waitFor(timeout) — the old readBytes()-first
 *   flow could block forever (or exhaust memory) and never applied its cap.
 * - delete_file refuses the workspace root and directories outright.
 *
 * Phase 4 (external-review roadmap, command sandbox enablement): the
 * spawn/drain/kill mechanics moved into [CommandSandbox] so they are
 * provable on their own — [Phase4CommandSandboxTests] on the JVM and
 * CommandSandboxDeviceTest in the CI emulator gate both execute REAL
 * child processes and assert the cwd pin, bare env, output cap, timeout
 * kill and scratch cleanup. That device gate is the precondition Phase 1
 * set for re-enabling command execution, so [commandsEnabled] now
 * defaults to TRUE; the kill switch is retained for emergencies (set it
 * to false to hard-disable run_command without touching the gate).
 *
 * Phase 5 (external-review roadmap M5): `patch_file` applies a unified
 * diff through [UnifiedDiff.apply] — the FULL patched content is computed
 * BEFORE any mutation, so a stale hunk aborts with the file untouched
 * (patch validation + conflict detection). Both write tools share
 * [writeVerified]: journal -> write -> read-back fingerprint check with
 * rollback on divergence.
 *
 * Phase 6 (external review, data durability):
 * - the journal is DURABLE (ServiceLocator wires a filesDir-backed
 *   [WriteJournal]) with FULL byte copies — rollback can no longer restore
 *   a truncated file, and resume can verify what already landed;
 * - every write re-reads the file right before mutating it and REFUSES if
 *   the content changed since it was journaled (approval-window TOCTOU:
 *   an external edit between preview and write is detected, not lost);
 * - a write whose args carry the resume-capped sentinel
 *   ([RunResumeStore.TRUNCATED_MARK]) is refused — a partial write is
 *   never executed silently.
 */
class ToolRegistry(
    private val workspace: WorkspaceOps,
    val journal: WriteJournal = WriteJournal.inMemory(),
    private val monitor: PerformanceMonitor = PerformanceMonitor(),
    private val commandContext: Context? = null,
    /**
     * Phase 4: ENABLED by default — the CommandSandbox device gate
     * (CommandSandboxDeviceTest in the CI emulator job) proves the sandbox
     * end-to-end on every push. Set to false as a kill switch; the
     * disabled path then reports the same refusal message as phase 1.
     */
    private val commandsEnabled: Boolean = true,
) {

    data class Tool(
        val name: String,
        val isWrite: Boolean,
        val description: String,
    )

    val tools = listOf(
        Tool("list_dir", false, "list files under a workspace path"),
        Tool("read_file", false, "read a file's content"),
        Tool("grep", false, "search a pattern across the workspace"),
        Tool("write_file", true, "create or overwrite a file"),
        Tool(
            "patch_file",
            true,
            "apply a unified diff to an existing file (context must match)",
        ),
        Tool("delete_file", true, "delete a file"),
        Tool(
            "run_command",
            true,
            "run an allowlisted, read-only command inside an isolated sandbox",
        ),
        Tool("finish", false, "end the task with a summary"),
    )

    data class ToolResult(
        val ok: Boolean,
        val output: String,
        val durationMs: Long = 0,
        /**
         * Phase 5 (M5 review evidence): the unified diff of what THIS
         * execution actually changed (write_file / patch_file on success).
         * AgentCore forwards it to the reviewer — per-step, no journal
         * lookups, no stale-entry risk.
         */
        val diff: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Review #6: every execution is timed and recorded for performance stats. */
    suspend fun execute(step: AgentStep): ToolResult {
        val startedAt = System.currentTimeMillis()
        val result = executeInner(step)
        val durationMs = System.currentTimeMillis() - startedAt
        monitor.record(step.tool, durationMs)
        return result.copy(durationMs = durationMs)
    }

    /** Nearest-rank p95 snapshot per tool, for verification/UI surfaces. */
    fun snapshot(): List<PerformanceMonitor.ToolStat> = monitor.snapshot()

    /**
     * Phase 6 (resume verification): read-only access for [AgentCore] to
     * check the CURRENT on-disk state of a file against the durable journal
     * before re-running a pending write step. Read tools already funnel
     * through SensitivePaths; this helper is for fingerprinting only.
     */
    suspend fun peek(path: String): String? = workspace.read(path)

    private suspend fun executeInner(step: AgentStep): ToolResult = runCatching {
        val args: JsonObject = json.parseToJsonElement(step.argsJson).let {
            it as JsonObject
        }
        fun arg(key: String): String =
            args[key]?.jsonPrimitive?.content ?: ""

        when (step.tool) {
            "list_dir" -> {
                val path = arg("path").ifBlank { "" }
                if (!PathGuard.isAllowed(path)) return@runCatching ToolResult(false, PathGuard.describe())
                val entries = workspace.list(path).filter { !SensitivePaths.isSensitive(it.path) }
                ToolResult(
                    ok = true,
                    output = entries.joinToString("\n") { if (it.isDir) "${it.path}/" else it.path },
                )
            }
            "read_file" -> {
                val path = arg("path")
                if (!PathGuard.isAllowed(path)) return@runCatching ToolResult(false, PathGuard.describe())
                if (SensitivePaths.isSensitive(path)) {
                    return@runCatching ToolResult(false, SensitivePaths.describe(path))
                }
                val content = workspace.read(path)
                if (content == null) ToolResult(false, "file not found")
                else ToolResult(true, content.take(MAX_FILE_CHARS))
            }
            "grep" -> {
                val hits = workspace.grep(arg("pattern"))
                    .filter { !SensitivePaths.isSensitive(it.path) }
                ToolResult(
                    ok = true,
                    output = hits.take(50).joinToString("\n") { "${it.path}:${it.line}: ${it.text.trim()}" }
                        .ifEmpty { "no matches" },
                )
            }
            "write_file" -> writeWithJournal(arg("path"), arg("content"), step.index)
            "patch_file" -> patchWithJournal(arg("path"), arg("diff"), step.index)
            "delete_file" -> deleteWithJournal(arg("path"), step.index)
            "run_command" -> runCommand(arg("command"))
            "finish" -> ToolResult(true, arg("summary").ifBlank { "done" })
            else -> ToolResult(false, "unknown tool: ${step.tool}")
        }
    }.getOrElse { ToolResult(false, "tool error: ${it.message}") }

    // ------------------------------------------------------------ run_command

    /**
     * Phase 4: command execution is ENABLED behind the full stack —
     * ApprovalGate (the tool is isWrite, so the user always confirms),
     * CommandIsolation.validate (allowlist + path-argument isolation +
     * injection guard) and [CommandSandbox] (per-run scratch dir, bare
     * env, capped drain, timeout kill). [commandsEnabled] remains as a
     * kill switch: false restores the phase-1 refusal verbatim.
     */
    private fun runCommand(raw: String): ToolResult {
        if (!commandsEnabled) {
            return ToolResult(
                false,
                "run_command is temporarily disabled (phase 1 data-safety lockdown) — " +
                    "no external process runs until the sandbox passes its device gate",
            )
        }
        return when (val verdict = CommandIsolation.validate(raw)) {
            is CommandIsolation.Verdict.Denied -> ToolResult(false, "denied: ${verdict.reason}")
            is CommandIsolation.Verdict.Allowed -> runSandboxed(verdict.argv)
        }
    }

    /**
     * Phase 4: mechanics delegated to [CommandSandbox]; this method only
     * supplies the app-private scratch root and maps the result envelope.
     * The sandbox creates a UNIQUE scratch directory per execution and
     * deletes it in a finally — state never leaks between commands.
     */
    private fun runSandboxed(argv: List<String>): ToolResult {
        val context = commandContext
            ?: return ToolResult(false, "command sandbox unavailable")
        return runCatching {
            val root = File(context.cacheDir, "cmd-sandbox").apply { mkdirs() }
            val r = CommandSandbox.execute(
                argv = argv,
                sandboxRoot = root,
                timeoutMs = CMD_TIMEOUT_MS,
                maxOutputBytes = MAX_OUTPUT_BYTES,
                maxOutputChars = MAX_OUTPUT_CHARS,
            )
            when {
                r.timedOut -> ToolResult(
                    false,
                    "command timed out after ${CMD_TIMEOUT_MS / 1000}s" +
                        (if (r.output.isNotBlank()) "; partial output: ${r.output}" else ""),
                )
                r.drainError != null -> ToolResult(
                    false,
                    "command failed: output drain error (${r.drainError})",
                )
                else -> ToolResult(
                    ok = r.exitCode == 0,
                    output = r.output.ifBlank { "(no output)" },
                )
            }
        }.getOrElse { ToolResult(false, "command failed: ${it.message}") }
    }

    /**
     * Review #2: fingerprint -> journal -> write -> read-back verify. If the
     * write fails or the on-disk content diverges afterwards, the previous
     * content is restored from the journal instead of being lost.
     */
    private suspend fun writeWithJournal(
        path: String,
        content: String,
        stepIndex: Int,
    ): ToolResult {
        if (path.isBlank()) return ToolResult(false, "empty path — refusing to write")
        // Phase 6: a content capped for the resume snapshot is NEVER written.
        if (content.contains(RunResumeStore.TRUNCATED_MARK)) {
            return ToolResult(
                false,
                "refusing to write: step args were capped for the resume " +
                    "snapshot (partial content) — re-run the task to regenerate this step",
            )
        }
        if (!PathGuard.isAllowed(path)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(path)) return ToolResult(false, SensitivePaths.describe(path))
        if (SensitivePaths.isProtectedFromWrite(path)) {
            return ToolResult(false, SensitivePaths.describeWrite(path))
        }

        val before = workspace.read(path)
        val entry = journal.record(path, WriteJournal.Kind.WRITE, before, newContent = content, stepIndex = stepIndex)
        // Phase 6 (TOCTOU closure): the content could have changed between
        // the journal read and the mutation (approval wait, slow SAF).
        // Re-read and refuse instead of clobbering the external edit.
        val recheck = workspace.read(path)
        if (recheck != before) {
            return ToolResult(
                false,
                "write refused: file changed between journaling and execution " +
                    "(external modification) — nothing was written",
            )
        }
        val diffText = UnifiedDiff.render(path, before ?: "", content)
        return writeVerified(path, content, entry).let { r ->
            if (r.ok) r.copy(diff = diffText) else r
        }
    }

    /**
     * Phase 5 (M5 patch validation): unified-diff edits that change a few
     * lines instead of rewriting whole files. Fail-closed ordering — the
     * diff is applied to an in-memory copy FIRST; any context mismatch
     * aborts with the on-disk file untouched and NO journal entry, so a
     * stale patch costs nothing. Only a fully-computed patch proceeds
     * through the same journal -> write -> read-back path as write_file.
     */
    private suspend fun patchWithJournal(
        path: String,
        diff: String,
        stepIndex: Int,
    ): ToolResult {
        if (path.isBlank()) return ToolResult(false, "empty path — refusing to patch")
        if (diff.isBlank()) return ToolResult(false, "empty patch — provide a unified diff")
        if (diff.contains(RunResumeStore.TRUNCATED_MARK)) {
            return ToolResult(
                false,
                "refusing to patch: the diff was capped for the resume snapshot " +
                    "(partial hunks) — re-run the task to regenerate this step",
            )
        }
        if (!PathGuard.isAllowed(path)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(path)) return ToolResult(false, SensitivePaths.describe(path))
        if (SensitivePaths.isProtectedFromWrite(path)) {
            return ToolResult(false, SensitivePaths.describeWrite(path))
        }

        val before = workspace.read(path)
            ?: return ToolResult(
                false,
                "file not found: $path (patch_file edits existing files; use write_file to create one)",
            )
        val applied = UnifiedDiff.apply(before, diff)
        if (!applied.ok || applied.content == null) {
            return ToolResult(false, "patch failed: ${applied.error}")
        }
        if (applied.content == before) {
            return ToolResult(true, "patch applied to $path: no change (diff matches current content)")
        }

        val entry = journal.record(path, WriteJournal.Kind.WRITE, before, newContent = applied.content, stepIndex = stepIndex)
        // Phase 6 (TOCTOU closure): refuse if the file moved under the patch.
        val recheck = workspace.read(path)
        if (recheck != before) {
            return ToolResult(
                false,
                "patch refused: file changed between journaling and execution " +
                    "(external modification) — nothing was written",
            )
        }
        val diffText = UnifiedDiff.render(path, before, applied.content)
        val result = writeVerified(path, applied.content, entry)
        if (result.ok) {
            return ToolResult(
                ok = true,
                output = "patched $path (${applied.hunksApplied} hunk(s); previous content in journal)",
                diff = diffText,
            )
        }
        return result
    }

    /**
     * Shared tail of both write tools (phase 5 refactor): write, read back,
     * verify the fingerprint, roll back on any failure — the journal entry
     * [entry] already holds the previous content.
     */
    private suspend fun writeVerified(
        path: String,
        content: String,
        entry: WriteJournal.Entry,
    ): ToolResult {
        if (!workspace.write(path, content)) {
            val before = entry.previousContent
            return if (before == null) {
                ToolResult(false, "write failed (no previous content to restore)")
            } else {
                val restored = workspace.write(path, before)
                ToolResult(
                    false,
                    if (restored) "write failed; previous content restored from journal"
                    else "write failed AND rollback failed — content of $path is preserved in the journal",
                )
            }
        }

        val after = workspace.read(path)
        return when {
            after == null -> ToolResult(
                false,
                "post-write verification failed (file unreadable); journal entry ${entry.fingerprintBefore.take(8)} kept for rollback",
            )
            journal.fingerprintOf(after) != journal.fingerprintOf(content) -> ToolResult(
                false,
                "post-write verification failed: on-disk content diverged (external modification); journal keeps the previous content for rollback",
            )
            else -> ToolResult(
                true,
                if (entry.hadFileBefore) "wrote $path (overwrote ${entry.fingerprintBefore.take(8)}; previous content in journal)"
                else "wrote $path (new file)",
            )
        }
    }

    /**
     * Delete with journal; restores the previous content if the delete fails.
     *
     * Phase 1 (external review, release blocker): the workspace root and
     * directories are NEVER deletable. PathGuard deliberately allows ""
     * (root is a valid listing/read target), but a SAF delete on the tree
     * root or a folder wipes the whole subtree with no undo copy — so the
     * delete path carries its own, stricter containment, enforced again at
     * the WorkspaceRepository choke point.
     */
    private suspend fun deleteWithJournal(path: String, stepIndex: Int): ToolResult {
        val clean = path.trim()
        if (clean.isBlank() || clean == "." || clean == "/") {
            return ToolResult(false, "deleting the workspace root is not allowed")
        }
        if (!PathGuard.isAllowed(clean)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(clean)) return ToolResult(false, SensitivePaths.describe(clean))
        if (SensitivePaths.isProtectedFromWrite(clean)) {
            return ToolResult(false, SensitivePaths.describeWrite(clean))
        }
        if (workspace.isDirectory(clean)) {
            return ToolResult(
                false,
                "directory deletion is disabled (files only) — remove folders explicitly outside the agent",
            )
        }

        val before = workspace.read(clean)
        val entry = journal.record(clean, WriteJournal.Kind.DELETE, before, stepIndex = stepIndex)
        // Phase 6 (TOCTOU closure): refuse when the file changed during the
        // approval wait — deleting an externally-modified file needs a fresh
        // decision, not a stale approval.
        val recheck = workspace.read(clean)
        if (recheck != before) {
            return ToolResult(
                false,
                "delete refused: file changed between journaling and execution " +
                    "(external modification) — nothing was deleted",
            )
        }
        return if (workspace.delete(clean)) {
            ToolResult(
                true,
                if (entry.hadFileBefore) "deleted $clean (previous content in journal)" else "deleted $clean",
            )
        } else {
            val restored = before?.let { workspace.write(clean, it) } ?: true
            ToolResult(
                false,
                if (restored) "delete failed; workspace unchanged"
                else "delete failed and restore failed — journal keeps the content of $clean",
            )
        }
    }

    private companion object {
        const val MAX_FILE_CHARS = 12_000
        const val CMD_TIMEOUT_MS = 10_000L
        const val MAX_OUTPUT_CHARS = 8_000

        /** Phase 1: hard cap for the concurrent output drain (bytes). */
        const val MAX_OUTPUT_BYTES = 65_536
    }
}

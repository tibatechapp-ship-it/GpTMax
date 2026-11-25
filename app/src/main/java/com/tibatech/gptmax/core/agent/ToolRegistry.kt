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
 */
class ToolRegistry(
    private val workspace: WorkspaceOps,
    val journal: WriteJournal = WriteJournal(),
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
        Tool("delete_file", true, "delete a file"),
        Tool(
            "run_command",
            true,
            "run an allowlisted, read-only command inside an isolated sandbox",
        ),
        Tool("finish", false, "end the task with a summary"),
    )

    data class ToolResult(val ok: Boolean, val output: String, val durationMs: Long = 0)

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
            "write_file" -> writeWithJournal(arg("path"), arg("content"))
            "delete_file" -> deleteWithJournal(arg("path"))
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
    private suspend fun writeWithJournal(path: String, content: String): ToolResult {
        if (!PathGuard.isAllowed(path)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(path)) return ToolResult(false, SensitivePaths.describe(path))
        if (SensitivePaths.isProtectedFromWrite(path)) {
            return ToolResult(false, SensitivePaths.describeWrite(path))
        }

        val before = workspace.read(path)
        val entry = journal.record(path, WriteJournal.Kind.WRITE, before)

        if (!workspace.write(path, content)) {
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
    private suspend fun deleteWithJournal(path: String): ToolResult {
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
        val entry = journal.record(clean, WriteJournal.Kind.DELETE, before)
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

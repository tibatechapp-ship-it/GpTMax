package com.tibatech.gptmax.core.agent

import android.content.Context
import com.tibatech.gptmax.core.guardrails.CommandIsolation
import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import java.io.File
import java.util.concurrent.TimeUnit
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
 */
class ToolRegistry(
    private val workspace: WorkspaceOps,
    val journal: WriteJournal = WriteJournal(),
    private val monitor: PerformanceMonitor = PerformanceMonitor(),
    private val commandContext: Context? = null,
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

    private fun runCommand(raw: String): ToolResult =
        when (val verdict = CommandIsolation.validate(raw)) {
            is CommandIsolation.Verdict.Denied -> ToolResult(false, "denied: ${verdict.reason}")
            is CommandIsolation.Verdict.Allowed -> runSandboxed(verdict.argv)
        }

    /**
     * Review #6 isolation: bare environment, pinned scratch working
     * directory, hard timeout and output cap. The ApprovalGate already
     * asked the user because the tool is registered with isWrite = true.
     */
    private fun runSandboxed(argv: List<String>): ToolResult {
        val context = commandContext
            ?: return ToolResult(false, "command sandbox unavailable")
        return runCatching {
            val sandbox = File(context.cacheDir, "cmd-sandbox").apply { mkdirs() }
            val builder = ProcessBuilder(argv)
                .directory(sandbox)
                .redirectErrorStream(true)
            // Strip the inherited environment entirely, then expose the bare
            // minimum a toybox binary needs to answer.
            builder.environment().clear()
            builder.environment()["PATH"] = "/system/bin:/system/xbin"
            builder.environment()["HOME"] = sandbox.absolutePath
            val process = builder.start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val finished = process.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                ToolResult(false, "command timed out after ${CMD_TIMEOUT_MS / 1000}s")
            } else {
                ToolResult(
                    ok = process.exitValue() == 0,
                    output = output.take(MAX_OUTPUT_CHARS).ifBlank { "(no output)" },
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

    /** Delete with journal; restores the previous content if the delete fails. */
    private suspend fun deleteWithJournal(path: String): ToolResult {
        if (!PathGuard.isAllowed(path)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(path)) return ToolResult(false, SensitivePaths.describe(path))
        if (SensitivePaths.isProtectedFromWrite(path)) {
            return ToolResult(false, SensitivePaths.describeWrite(path))
        }

        val before = workspace.read(path)
        val entry = journal.record(path, WriteJournal.Kind.DELETE, before)
        return if (workspace.delete(path)) {
            ToolResult(
                true,
                if (entry.hadFileBefore) "deleted $path (previous content in journal)" else "deleted $path",
            )
        } else {
            val restored = before?.let { workspace.write(path, it) } ?: true
            ToolResult(
                false,
                if (restored) "delete failed; workspace unchanged"
                else "delete failed and restore failed — journal keeps the content of $path",
            )
        }
    }

    private companion object {
        const val MAX_FILE_CHARS = 12_000
        const val CMD_TIMEOUT_MS = 10_000L
        const val MAX_OUTPUT_CHARS = 8_000
    }
}

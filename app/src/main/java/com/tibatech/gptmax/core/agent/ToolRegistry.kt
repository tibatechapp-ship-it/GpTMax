package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool surface exposed to the model. Read tools execute immediately inside
 * the loop; write tools are routed through the ApprovalGate first.
 *
 * Review #1: reads, listings and grep hits all funnel through SensitivePaths
 * so .aws/.ssh/.env*/keystore content never reaches the model context.
 * Review #2: every write/delete is journaled with a pre-change fingerprint
 * and a rollback copy, then verified by read-back.
 * Review #6: PathGuard confines every path argument to the granted workspace.
 */
class ToolRegistry(
    private val workspace: WorkspaceOps,
    private val journal: WriteJournal = WriteJournal(),
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
        Tool("finish", false, "end the task with a summary"),
    )

    data class ToolResult(val ok: Boolean, val output: String)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(step: AgentStep): ToolResult = runCatching {
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
            "finish" -> ToolResult(true, arg("summary").ifBlank { "done" })
            else -> ToolResult(false, "unknown tool: ${step.tool}")
        }
    }.getOrElse { ToolResult(false, "tool error: ${it.message}") }

    /**
     * Review #2: fingerprint -> journal -> write -> read-back verify. If the
     * write fails or the on-disk content diverges afterwards, the previous
     * content is restored from the journal instead of being lost.
     */
    private suspend fun writeWithJournal(path: String, content: String): ToolResult {
        if (!PathGuard.isAllowed(path)) return ToolResult(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(path)) return ToolResult(false, SensitivePaths.describe(path))

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
    }
}

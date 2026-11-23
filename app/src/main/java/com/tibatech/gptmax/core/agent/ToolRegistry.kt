package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.data.repo.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool surface exposed to the model. Read tools execute immediately inside
 * the loop; write tools are routed through the ApprovalGate first.
 *
 * Safety rails live in the workspace repository itself (SensitivePathPolicy +
 * FileWriteGuard), so a write that is rejected by policy or staleness check
 * surfaces here as a failed tool result the model can react to.
 */
class ToolRegistry(private val workspace: WorkspaceRepository) {

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
                val entries = workspace.list(path)
                ToolResult(
                    ok = true,
                    output = entries.joinToString("\n") { if (it.isDir) "${it.path}/" else it.path },
                )
            }
            "read_file" -> {
                val content = workspace.read(arg("path"))
                if (content == null) ToolResult(false, "file not found or blocked by policy")
                else ToolResult(true, content.take(MAX_FILE_CHARS))
            }
            "grep" -> {
                val hits = workspace.grep(arg("pattern"))
                ToolResult(
                    ok = true,
                    output = hits.take(50).joinToString("\n") { "${it.path}:${it.line}: ${it.text.trim()}" }
                        .ifEmpty { "no matches" },
                )
            }
            "write_file" -> {
                // Content comes fully formed from the model after approval.
                val path = arg("path")
                when (val result = workspace.write(path, arg("content"))) {
                    is WorkspaceRepository.WriteResult.Written -> ToolResult(true, "wrote $path")
                    is WorkspaceRepository.WriteResult.Blocked ->
                        ToolResult(false, "write blocked: ${result.reason}")
                }
            }
            "delete_file" -> {
                val path = arg("path")
                if (workspace.delete(path)) {
                    ToolResult(true, "deleted $path")
                } else {
                    ToolResult(
                        false,
                        "delete failed: path not found, is a directory, or is protected by policy",
                    )
                }
            }
            "finish" -> ToolResult(true, arg("summary").ifBlank { "done" })
            else -> ToolResult(false, "unknown tool: ${step.tool}")
        }
    }.getOrElse { ToolResult(false, "tool error: ${it.message}") }

    private companion object {
        const val MAX_FILE_CHARS = 12_000
    }
}

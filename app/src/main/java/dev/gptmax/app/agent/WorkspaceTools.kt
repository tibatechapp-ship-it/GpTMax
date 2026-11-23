package dev.gptmax.app.agent

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private val blockedNames = setOf(
    ".env", ".env.local", ".env.production", "id_rsa", "id_ed25519",
    "credentials.json", "secrets.json", "keystore.jks",
)

private fun isSensitiveFile(file: File): Boolean =
    file.name.lowercase() in blockedNames ||
        file.name.endsWith(".pem", ignoreCase = true) ||
        file.name.endsWith(".p12", ignoreCase = true) ||
        file.name.endsWith(".key", ignoreCase = true)

internal fun safeWorkspaceFile(root: File, relativePath: String): File {
    require(relativePath.isNotBlank()) { "Path is required." }
    require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
        "Absolute paths are not allowed."
    }
    require(relativePath.split('/', '\\').none { it == ".." || it.isBlank() }) {
        "Path traversal or empty path segment is not allowed."
    }
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    require(candidate.path.startsWith(canonicalRoot.path + File.separator)) {
        "Path escapes the workspace."
    }
    return candidate
}

class WorkspaceListTool(private val root: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "workspace.list",
        description = "List immediate entries in a workspace directory. Provide path; use empty string for root.",
        requiredArguments = setOf("path"),
        allowedArguments = setOf("path"),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        val directory = if (call.arguments.getValue("path").isBlank()) root.canonicalFile
            else safeWorkspaceFile(root, call.arguments.getValue("path"))
        require(directory.isDirectory) { "Directory not found." }
        val entries = directory.listFiles()?.sortedBy { it.name } ?: emptyList()
        val output = entries.take(200).joinToString("\n") { file ->
            val suffix = if (file.isDirectory) "/" else ""
            file.name + suffix
        }
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            output.ifBlank { "(empty directory)" }, elapsed(start))
    }
}

class WorkspaceReadTool(private val root: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "file.read",
        description = "Read a UTF-8 text file in the workspace. Provide relative path.",
        requiredArguments = setOf("path"),
        allowedArguments = setOf("path"),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        val file = safeWorkspaceFile(root, call.arguments.getValue("path"))
        require(file.isFile) { "File not found." }
        require(!isSensitiveFile(file)) { "Reading credential or secret files is blocked." }
        require(file.length() <= 256 * 1024) { "File exceeds 256 KiB read limit." }
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains('\u0000')) { "Binary files are not supported by file.read." }
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS, text, elapsed(start))
    }
}

class WorkspaceSearchTool(private val root: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "file.search",
        description = "Search literal text in workspace files. Provide query and optional path.",
        requiredArguments = setOf("query"),
        allowedArguments = setOf("query", "path"),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        val query = call.arguments.getValue("query")
        require(query.isNotBlank() && query.length <= 500) { "Search query must be 1-500 characters." }
        val base = call.arguments["path"].orEmpty().let {
            if (it.isBlank()) root.canonicalFile else safeWorkspaceFile(root, it)
        }
        require(base.isDirectory) { "Search directory not found." }
        val matches = mutableListOf<String>()
        var scanned = 0
        base.walkTopDown().onEnter { directory ->
            !cancellation.get() && directory.name !in setOf(".git", ".gradle", "build", ".idea", "node_modules")
        }.forEach { file ->
            if (cancellation.get() || scanned >= 300 || matches.size >= 100) return@forEach
            if (!file.isFile || isSensitiveFile(file) || file.length() > 128 * 1024) return@forEach
            if (!runCatching { file.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)) return@forEach
            scanned++
            val content = runCatching { file.readText(Charset.forName("UTF-8")) }.getOrNull() ?: return@forEach
            if (content.contains('\u0000')) return@forEach
            content.lineSequence().forEachIndexed { line, value ->
                if (matches.size < 100 && value.contains(query, ignoreCase = true)) {
                    val relative = file.relativeTo(root.canonicalFile).path
                    matches += "$relative:${line + 1}: ${value.take(500)}"
                }
            }
        }
        val status = if (cancellation.get()) ToolExecutionStatus.CANCELLED else ToolExecutionStatus.SUCCESS
        return AgentToolResult(spec.name, call.id, status,
            matches.joinToString("\n").ifBlank { "No matches. Files scanned: $scanned" }, elapsed(start))
    }
}

class WorkspaceWriteTool(private val root: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "file.write",
        description = "Create or replace a UTF-8 text file in the workspace. Provide relative path and complete content.",
        requiredArguments = setOf("path", "content"),
        allowedArguments = setOf("path", "content"),
        risk = ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        if (cancellation.get()) return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
        val file = safeWorkspaceFile(root, call.arguments.getValue("path"))
        require(!isSensitiveFile(file)) { "Writing credential or secret files is blocked." }
        val content = call.arguments.getValue("content")
        require(content.length <= 128 * 1024) { "File content exceeds 128 KiB limit." }
        require(!content.contains('\u0000')) { "Binary content is not supported." }
        require(file.parentFile?.isDirectory == true) { "Parent directory must already exist." }
        val temp = File(file.parentFile, file.name + ".gptmax-tmp-" + System.nanoTime())
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (cancellation.get()) {
                temp.delete()
                return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
            }
            if (file.exists()) require(file.isFile) { "Target is not a regular file." }
            if (!temp.renameTo(file)) throw IllegalStateException("Atomic file replacement failed.")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            "Wrote ${content.length} characters to ${file.relativeTo(root.canonicalFile).path}.", elapsed(start))
    }
}

fun createWorkspaceToolRegistry(root: File): AgentToolRegistry = AgentToolRegistry(
    listOf(
        WorkspaceListTool(root),
        WorkspaceReadTool(root),
        WorkspaceSearchTool(root),
        WorkspaceWriteTool(root),
        ManagedProcessTool(root),
    ),
)

private fun elapsed(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000L

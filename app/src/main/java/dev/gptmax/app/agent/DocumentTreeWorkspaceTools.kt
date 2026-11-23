package dev.gptmax.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

private val sensitiveDocumentNames = setOf(
    ".env", ".env.local", ".env.production", "id_rsa", "id_ed25519",
    "credentials.json", "secrets.json", "keystore.jks",
)

private fun safeDocumentName(name: String): Boolean =
    name.lowercase() !in sensitiveDocumentNames &&
        !name.endsWith(".pem", true) &&
        !name.endsWith(".p12", true) &&
        !name.endsWith(".key", true)

private fun documentSegments(path: String): List<String> {
    require(path.isNotBlank()) { "Path is required." }
    require(!path.startsWith("/") && !path.startsWith("\\")) { "Absolute paths are not allowed." }
    val parts = path.split('/', '\\')
    require(parts.none { it.isBlank() || it == "." || it == ".." }) {
        "Path traversal or empty path segment is not allowed."
    }
    return parts
}

private fun resolveDocument(root: DocumentFile, path: String): DocumentFile {
    var current = root
    for (segment in documentSegments(path)) {
        current = current.findFile(segment) ?: throw FileNotFoundException("Workspace item not found: $segment")
    }
    return current
}

private fun readDocument(context: Context, file: DocumentFile, limit: Int): String {
    require(file.isFile) { "Not a regular file." }
    require(safeDocumentName(file.name.orEmpty())) { "Credential and secret files are blocked." }
    require(file.length() <= limit) { "File exceeds the read size limit." }
    val stream = context.contentResolver.openInputStream(file.uri)
        ?: throw FileNotFoundException("Could not open document.")
    val bytes = stream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Document exceeds the read size limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val text = bytes.toString(Charsets.UTF_8)
    require(!text.contains('\u0000')) { "Binary files are not supported." }
    return text
}

class DocumentTreeListTool(context: Context, treeUri: Uri) : AgentTool {
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        "workspace.list",
        "List entries in the selected project folder. Provide relative path; empty string means project root.",
        setOf("path"),
        setOf("path"),
        ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val path = call.arguments.getValue("path")
        val directory = if (path.isBlank()) root else resolveDocument(root, path)
        require(directory.isDirectory) { "Directory not found." }
        val output = directory.listFiles().take(200).sortedBy { it.name.orEmpty() }.joinToString("\n") {
            (it.name ?: "(unnamed)") + if (it.isDirectory) "/" else ""
        }
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            output.ifBlank { "(empty directory)" })
    }
}

class DocumentTreeReadTool(context: Context, treeUri: Uri) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        "file.read",
        "Read a UTF-8 text file from the selected project folder. Provide relative path.",
        setOf("path"),
        setOf("path"),
        ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val file = resolveDocument(root, call.arguments.getValue("path"))
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            readDocument(appContext, file, 256 * 1024))
    }
}

class DocumentTreeSearchTool(context: Context, treeUri: Uri) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        "file.search",
        "Search literal text in project files. Provide query and optional relative path.",
        setOf("query"),
        setOf("query", "path"),
        ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val query = call.arguments.getValue("query")
        require(query.isNotBlank() && query.length <= 500) { "Search query must be 1-500 characters." }
        val base = call.arguments["path"].orEmpty().let { if (it.isBlank()) root else resolveDocument(root, it) }
        require(base.isDirectory) { "Search directory not found." }
        val matches = mutableListOf<String>()
        var scanned = 0
        fun visit(directory: DocumentFile, prefix: String, depth: Int) {
            if (depth > 12 || cancellation.get() || scanned >= 300 || matches.size >= 100) return
            directory.listFiles().sortedBy { it.name.orEmpty() }.forEach { item ->
                if (cancellation.get() || scanned >= 300 || matches.size >= 100) return
                val name = item.name ?: return
                if (item.isDirectory) {
                    if (name !in setOf(".git", ".gradle", "build", ".idea", "node_modules")) {
                        visit(item, if (prefix.isEmpty()) name else "$prefix/$name", depth + 1)
                    }
                } else if (item.isFile && safeDocumentName(name) && item.length() <= 128 * 1024) {
                    scanned++
                    val content = runCatching { readDocument(appContext, item, 128 * 1024) }.getOrNull() ?: return@forEach
                    content.lineSequence().forEachIndexed { line, value ->
                        if (matches.size < 100 && value.contains(query, true)) {
                            val path = if (prefix.isEmpty()) name else "$prefix/$name"
                            matches += "$path:${line + 1}: ${value.take(500)}"
                        }
                    }
                }
            }
        }
        visit(base, call.arguments["path"].orEmpty().trim('/'), 0)
        return AgentToolResult(
            spec.name,
            call.id,
            if (cancellation.get()) ToolExecutionStatus.CANCELLED else ToolExecutionStatus.SUCCESS,
            matches.joinToString("\n").ifBlank { "No matches. Files scanned: $scanned" },
        )
    }
}

class DocumentTreeWriteTool(context: Context, treeUri: Uri) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        "file.write",
        "Create or replace a UTF-8 text file in the selected project. Provide relative path and complete content.",
        setOf("path", "content"),
        setOf("path", "content"),
        ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        if (cancellation.get()) return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
        val segments = documentSegments(call.arguments.getValue("path"))
        val name = segments.last()
        require(safeDocumentName(name)) { "Writing credential and secret files is blocked." }
        val content = call.arguments.getValue("content")
        require(content.length <= 128 * 1024 && !content.contains('\u0000')) {
            "File content is binary or exceeds 128 KiB."
        }
        var parent = root
        segments.dropLast(1).forEach { segment ->
            parent = parent.findFile(segment) ?: throw FileNotFoundException("Parent directory not found: $segment")
            require(parent.isDirectory) { "Parent path is not a directory." }
        }
        val existing = parent.findFile(name)
        if (existing != null) require(existing.isFile) { "Target is not a file." }
        val temp = parent.createFile("text/plain", "$name.gptmax-tmp")
            ?: throw IllegalStateException("Could not create temporary document.")
        try {
            val output = appContext.contentResolver.openOutputStream(temp.uri, "wt")
                ?: throw IllegalStateException("Could not open temporary document for writing.")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (cancellation.get()) {
                temp.delete()
                return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
            }
            val backup = if (existing != null) {
                require(existing.renameTo("$name.gptmax-backup")) { "Could not preserve existing file for rollback." }
                existing
            } else null
            val moved = temp.renameTo(name)
            if (!moved) {
                backup?.renameTo(name)
                throw IllegalStateException("Document provider could not finalize file replacement.")
            }
            backup?.delete()
        } finally {
            if (temp.exists()) temp.delete()
        }
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS, "Updated $name.")
    }
}

fun createDocumentTreeToolRegistry(context: Context, treeUri: Uri): AgentToolRegistry =
    AgentToolRegistry(
        listOf(
            DocumentTreeListTool(context, treeUri),
            DocumentTreeReadTool(context, treeUri),
            DocumentTreeSearchTool(context, treeUri),
            DocumentTreeWriteTool(context, treeUri),
        ),
    )

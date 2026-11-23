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
        val oldContent = existing?.let { readDocument(appContext, it, 128 * 1024) }
        val tempName = "$name.gptmax-tmp-${System.nanoTime()}"
        val temp = parent.createFile("text/plain", tempName)
            ?: throw IllegalStateException("Could not create temporary document.")
        try {
            val output = appContext.contentResolver.openOutputStream(temp.uri, "wt")
                ?: throw IllegalStateException("Could not open temporary document for writing.")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (cancellation.get()) {
                temp.delete()
                return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
            }
            val backupName = "$name.gptmax-backup-${System.nanoTime()}"
            val backup = if (existing != null) {
                require(existing.renameTo(backupName)) { "Could not preserve existing file for rollback." }
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
        val preview = renderChangePreview(oldContent.orEmpty(), content)
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            "Updated $name.\n$preview")
    }

    private fun renderChangePreview(before: String, after: String): String {
        val oldLines = before.lines()
        val newLines = after.lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        return buildString {
            appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
            removed.take(24).forEach { appendLine("- ${it.take(300)}") }
            added.take(24).forEach { appendLine("+ ${it.take(300)}") }
            if (removed.size > 24 || added.size > 24) appendLine("[diff preview truncated]")
        }.take(6_000)
    }
}

class DocumentTreeReplaceTool(context: Context, treeUri: Uri) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")
    private val writer = DocumentTreeWriteTool(context, treeUri)

    override val spec = AgentToolSpec(
        "file.replace",
        "Replace one exact unique text fragment in a project file. Provide path, old_text, and new_text.",
        setOf("path", "old_text", "new_text"),
        setOf("path", "old_text", "new_text"),
        ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val oldText = call.arguments.getValue("old_text")
        val newText = call.arguments.getValue("new_text")
        require(oldText.isNotEmpty() && oldText.length <= 16_000 && newText.length <= 16_000) {
            "Replacement fragments must be 1-16000 characters."
        }
        val file = resolveDocument(root, call.arguments.getValue("path"))
        val content = readDocument(appContext, file, 128 * 1024)
        val occurrences = content.windowed(oldText.length, 1, partialWindows = false).count { it == oldText }
        require(occurrences == 1) {
            if (occurrences == 0) "Exact old_text was not found; re-read the file before editing."
            else "old_text is ambiguous ($occurrences occurrences); provide a larger unique fragment."
        }
        val updated = content.replaceFirst(oldText, newText)
        return writer.execute(
            AgentToolCall(call.id, "file.write", mapOf("path" to call.arguments.getValue("path"), "content" to updated)),
            cancellation,
        )
    }
}

fun createDocumentTreeToolRegistry(context: Context, treeUri: Uri): AgentToolRegistry =
    AgentToolRegistry(
        listOf(
            DocumentTreeListTool(context, treeUri),
            DocumentTreeReadTool(context, treeUri),
            DocumentTreeSearchTool(context, treeUri),
            DocumentTreeWriteTool(context, treeUri),
            DocumentTreeReplaceTool(context, treeUri),
        ),
    )

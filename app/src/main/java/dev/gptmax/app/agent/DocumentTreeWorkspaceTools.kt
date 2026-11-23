package dev.gptmax.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SAF (document tree) workspace tools.
 *
 * Path and file-name safety decisions are delegated to [SensitivePathPolicy] so the document-tree
 * tools and the file-based tools enforce exactly the same rules, and every traversal surface (direct
 * resolve, search, overview, context index) shares one blocklist. Write operations are guarded by a
 * shared [EditSafetyJournal] and keep a timestamped backup of the previous version instead of
 * deleting it immediately, so no edit can be lost silently.
 */

private fun documentSegments(path: String): List<String> = SensitivePathPolicy.validateRelativePath(path)

private fun safeDocumentName(name: String): Boolean = SensitivePathPolicy.isAllowedFileName(name)

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

/** Keeps at most [keep] timestamped backups per file inside a document directory. Never throws. */
private fun pruneOldDocumentBackups(parent: DocumentFile, name: String, keep: Int = 3) {
    runCatching {
        val prefix = "$name.gptmax-backup-"
        parent.listFiles()
            .filter { it.isFile && (it.name ?: "").startsWith(prefix) }
            .sortedByDescending { it.name.orEmpty() }
            .drop(keep)
            .forEach { it.delete() }
    }
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

class DocumentTreeReadTool(
    context: Context,
    treeUri: Uri,
    private val journal: EditSafetyJournal = EditSafetyJournal(),
) : AgentTool {
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
        val relativePath = call.arguments.getValue("path")
        val file = resolveDocument(root, relativePath)
        val text = readDocument(appContext, file, 256 * 1024)
        journal.recordRead(relativePath, ContentFingerprints.sha256(text))
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS, text)
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
                    if (SensitivePathPolicy.isTraversalAllowedDirectoryName(name)) {
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

class DocumentTreeWriteTool(
    context: Context,
    treeUri: Uri,
    private val journal: EditSafetyJournal = EditSafetyJournal(),
) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        "file.write",
        "Create or replace a UTF-8 text file in the selected project. Provide relative path and complete content. Existing files must be read first; external changes since the last read are rejected.",
        setOf("path", "content"),
        setOf("path", "content"),
        ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        if (cancellation.get()) return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
        val relativePath = call.arguments.getValue("path")
        val segments = documentSegments(relativePath)
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
        val decision = journal.checkWrite(
            relativePath,
            oldContent?.let { ContentFingerprints.sha256(it) },
            existing != null,
        )
        if (decision is EditSafetyJournal.WriteDecision.Blocked) throw IllegalStateException(decision.reason)
        val tempName = "$name.gptmax-tmp-${System.nanoTime()}"
        val temp = parent.createFile("text/plain", tempName)
            ?: throw IllegalStateException("Could not create temporary document.")
        var backup: DocumentFile? = null
        var succeeded = false
        try {
            val output = appContext.contentResolver.openOutputStream(temp.uri, "wt")
                ?: throw IllegalStateException("Could not open temporary document for writing.")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (cancellation.get()) {
                temp.delete()
                return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
            }
            if (existing != null) {
                val backupName = "$name.gptmax-backup-${System.nanoTime()}"
                require(existing.renameTo(backupName)) { "Could not preserve existing file for rollback." }
                backup = existing
            }
            val moved = temp.renameTo(name)
            if (!moved) {
                backup?.renameTo(name)
                throw IllegalStateException("Document provider could not finalize file replacement.")
            }
            journal.recordWrite(relativePath, ContentFingerprints.sha256(content), backup?.name)
            succeeded = true
            pruneOldDocumentBackups(parent, name)
        } catch (failure: Exception) {
            if (!succeeded) journal.recordFailedWrite(relativePath, backup?.name)
            throw failure
        } finally {
            if (temp.exists()) temp.delete()
        }
        val preview = renderChangePreview(oldContent.orEmpty(), content)
        val rollbackHint = backup?.name?.let { " Previous version kept as $it for manual rollback." } ?: ""
        return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
            "Updated $name.\n$preview$rollbackHint")
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

class DocumentTreeReplaceTool(
    context: Context,
    treeUri: Uri,
    private val journal: EditSafetyJournal = EditSafetyJournal(),
    private val writer: DocumentTreeWriteTool = DocumentTreeWriteTool(context, treeUri, journal),
) : AgentTool {
    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

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
        val relativePath = call.arguments.getValue("path")
        val file = resolveDocument(root, relativePath)
        val content = readDocument(appContext, file, 128 * 1024)
        journal.recordRead(relativePath, ContentFingerprints.sha256(content))
        val first = content.indexOf(oldText)
        val occurrences = if (first < 0) 0 else if (content.indexOf(oldText, first + oldText.length) < 0) 1 else 2
        require(occurrences == 1) {
            if (occurrences == 0) "Exact old_text was not found; re-read the file before editing."
            else "old_text is ambiguous ($occurrences occurrences); provide a larger unique fragment."
        }
        val updated = content.replaceFirst(oldText, newText)
        return writer.execute(
            AgentToolCall(call.id, "file.write", mapOf("path" to relativePath, "content" to updated)),
            cancellation,
        )
    }
}

fun createDocumentTreeToolRegistry(context: Context, treeUri: Uri, contextEngine: ProjectContextEngine? = null): AgentToolRegistry {
    val journal = EditSafetyJournal()
    val writer = DocumentTreeWriteTool(context, treeUri, journal)
    return AgentToolRegistry(
        listOfNotNull(
            contextEngine?.let { DocumentTreeContextIndexTool(context, treeUri, it) },
            contextEngine?.let { ProjectContextRecallTool(it) },
            contextEngine?.let { ProjectContextRememberTool(it) },
            contextEngine?.let { ProjectContextCheckpointTool(it) },
            contextEngine?.let { ProjectContextRestoreTool(it) },
            contextEngine?.let { ProjectContextStageTool(it) },
            DocumentTreeOverviewTool(context, treeUri),
            DocumentTreeListTool(context, treeUri),
            DocumentTreeReadTool(context, treeUri, journal),
            DocumentTreeSearchTool(context, treeUri),
            writer,
            DocumentTreeReplaceTool(context, treeUri, journal, writer),
        ),
    )
}

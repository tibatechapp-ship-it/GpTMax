package dev.gptmax.app.agent

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-based workspace tools (app-private workspace mode).
 *
 * All path and file-name safety decisions are delegated to [SensitivePathPolicy] so the file-based
 * tools and the SAF (document tree) tools enforce exactly the same rules. Write operations are
 * guarded by a shared [EditSafetyJournal]: existing files must be read in the current session before
 * they can be overwritten, external modifications are detected through content fingerprints, and the
 * previous version is preserved as a timestamped backup instead of being deleted immediately.
 */

internal fun isSensitiveFile(file: File): Boolean = !SensitivePathPolicy.isAllowedFileName(file.name)

internal fun safeWorkspaceFile(root: File, relativePath: String): File {
    SensitivePathPolicy.validateRelativePath(relativePath)
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    require(candidate.path.startsWith(canonicalRoot.path + File.separator)) {
        "Path escapes the workspace."
    }
    return candidate
}

private const val MAX_BACKUPS_PER_FILE = 3
private const val FINGERPRINT_SIZE_LIMIT_BYTES = 4 * 1024 * 1024

/** Keeps at most [keep] timestamped backups per file, deleting the oldest ones. Never throws. */
internal fun pruneOldBackups(directory: File, fileName: String, keep: Int = MAX_BACKUPS_PER_FILE) {
    runCatching {
        val prefix = "$fileName.gptmax-backup-"
        directory.listFiles { file -> file.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
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

class WorkspaceReadTool(
    private val root: File,
    private val journal: EditSafetyJournal = EditSafetyJournal(),
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "file.read",
        description = "Read a UTF-8 text file in the workspace. Provide relative path.",
        requiredArguments = setOf("path"),
        allowedArguments = setOf("path"),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        val relativePath = call.arguments.getValue("path")
        val file = safeWorkspaceFile(root, relativePath)
        require(file.isFile) { "File not found." }
        require(!isSensitiveFile(file)) { "Reading credential or secret files is blocked." }
        require(file.length() <= 256 * 1024) { "File exceeds 256 KiB read limit." }
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains('\u0000')) { "Binary files are not supported by file.read." }
        journal.recordRead(relativePath, ContentFingerprints.sha256(text))
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
            !cancellation.get() &&
                (directory == root.canonicalFile || SensitivePathPolicy.isTraversalAllowedDirectoryName(directory.name))
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

class WorkspaceWriteTool(
    private val root: File,
    private val journal: EditSafetyJournal = EditSafetyJournal(),
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "file.write",
        description = "Create or replace a UTF-8 text file in the workspace. Provide relative path and complete content. Existing files must be read first; external changes since the last read are rejected.",
        requiredArguments = setOf("path", "content"),
        allowedArguments = setOf("path", "content"),
        risk = ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val start = System.nanoTime()
        if (cancellation.get()) return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
        val relativePath = call.arguments.getValue("path")
        val file = safeWorkspaceFile(root, relativePath)
        require(!isSensitiveFile(file)) { "Writing credential or secret files is blocked." }
        val content = call.arguments.getValue("content")
        require(content.length <= 128 * 1024) { "File content exceeds 128 KiB limit." }
        require(!content.contains('\u0000')) { "Binary content is not supported." }
        val parent = file.parentFile ?: throw IllegalStateException("File has no parent directory.")
        require(parent.isDirectory) { "Parent directory must already exist." }
        val fileExists = file.exists()
        require(!fileExists || file.isFile) { "Target is not a regular file." }
        if (fileExists) {
            require(file.length() <= FINGERPRINT_SIZE_LIMIT_BYTES) {
                "Existing file is too large to fingerprint safely; refusing to overwrite."
            }
        }
        val currentFingerprint = if (fileExists) {
            ContentFingerprints.sha256(file.readText(Charsets.UTF_8))
        } else null
        val decision = journal.checkWrite(relativePath, currentFingerprint, fileExists)
        if (decision is EditSafetyJournal.WriteDecision.Blocked) throw IllegalStateException(decision.reason)

        val temp = File(parent, file.name + ".gptmax-tmp-" + System.nanoTime())
        val backup = if (fileExists) File(parent, file.name + ".gptmax-backup-" + System.nanoTime()) else null
        var succeeded = false
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (cancellation.get()) {
                temp.delete()
                return AgentToolResult(spec.name, call.id, ToolExecutionStatus.CANCELLED, "Cancelled.")
            }
            if (backup != null) {
                require(file.renameTo(backup)) { "Could not preserve the previous version for rollback." }
            }
            if (!temp.renameTo(file)) {
                if (backup != null && backup.exists()) runCatching { backup.renameTo(file) }
                throw IllegalStateException("Atomic file replacement failed; the previous version was restored.")
            }
            journal.recordWrite(relativePath, ContentFingerprints.sha256(content), backup?.name)
            succeeded = true
            pruneOldBackups(parent, file.name)
            val rollbackHint = backup?.let { " Previous version kept as ${it.name} for manual rollback." } ?: ""
            return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS,
                "Wrote ${content.length} characters to ${file.relativeTo(root.canonicalFile).path}.$rollbackHint",
                elapsed(start))
        } catch (failure: Exception) {
            if (!succeeded) journal.recordFailedWrite(relativePath, backup?.name)
            throw failure
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

fun createWorkspaceToolRegistry(root: File): AgentToolRegistry {
    val journal = EditSafetyJournal()
    return AgentToolRegistry(
        listOf(
            WorkspaceOverviewTool(root),
            GitWorkspaceTool(root),
            WorkspaceListTool(root),
            WorkspaceReadTool(root, journal),
            WorkspaceSearchTool(root),
            WorkspaceWriteTool(root, journal),
            ManagedProcessTool(root),
        ),
    )
}

private fun elapsed(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000L

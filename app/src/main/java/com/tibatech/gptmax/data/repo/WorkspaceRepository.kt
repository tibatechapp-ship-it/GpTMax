package com.tibatech.gptmax.data.repo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tibatech.gptmax.core.guardrails.ContentFingerprints
import com.tibatech.gptmax.core.guardrails.FileWriteGuard
import com.tibatech.gptmax.core.guardrails.SensitivePathPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Workspace access over the Storage Access Framework: the user grants one
 * project folder (ACTION_OPEN_DOCUMENT_TREE) and every agent tool operates
 * on relative paths inside it. No broad storage permission is requested.
 *
 * Safety rails (code review #1 + #2):
 *  - every path passes [SensitivePathPolicy] before it reaches the tree, so
 *    credential directories (`.ssh`, `.aws`, ...) and secret files (`.env*`,
 *    keystores, ...) can never be read, listed, crawled, written, or deleted;
 *  - overwrites go through [FileWriteGuard]: read-before-write, SHA-256
 *    staleness detection, fail-closed timestamped backups, and a JSONL
 *    rollback journal instead of destroying the previous content.
 */
class WorkspaceRepository(
    private val context: Context,
    private val writeGuard: FileWriteGuard? = null,
) {

    data class FileNode(val name: String, val path: String, val isDir: Boolean)
    data class GrepHit(val path: String, val line: Int, val text: String)

    /** Outcome of a write; [Blocked] carries the reason shown to the user and the model. */
    sealed class WriteResult {
        object Written : WriteResult()
        data class Blocked(val reason: String) : WriteResult()
    }

    private val _root = MutableStateFlow<DocumentFile?>(null)
    val root: StateFlow<DocumentFile?> = _root.asStateFlow()

    /** Persists tree access so restarts keep the workspace. */
    fun setRoot(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        DocumentFile.fromTreeUri(context, uri)?.also { _root.value = it } != null
    }.getOrDefault(false)

    // ------------------------------------------------------------------ list
    suspend fun list(dirPath: String): List<FileNode> = withContext(Dispatchers.IO) {
        val dir = resolve(dirPath) ?: return@withContext emptyList()
        dir.listFiles()
            .filter { it.name != null }
            // Secret directories/files are never surfaced, not even by name (review #1).
            .filter {
                !SensitivePathPolicy.isForbiddenDirectory(it.name ?: "") &&
                    !SensitivePathPolicy.isForbiddenFileName(it.name ?: "")
            }
            .map { FileNode(it.name ?: "", join(dirPath, it.name ?: ""), it.isDirectory) }
            .sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy { it.name })
    }

    // ------------------------------------------------------------------ read
    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext null
        if (!file.isFile || !file.canRead()) return@withContext null
        val content = context.contentResolver.openInputStream(file.uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        if (content != null) {
            writeGuard?.recordRead(normalize(path), ContentFingerprints.sha256(content))
        }
        content
    }

    // ----------------------------------------------------------------- write
    suspend fun write(path: String, content: String): WriteResult = withContext(Dispatchers.IO) {
        runCatching {
            policyViolation(path)?.let { return@runCatching WriteResult.Blocked(it) }
            val (parent, name) = splitParent(path)
            val parentDir = ensureDir(parent)
                ?: return@runCatching WriteResult.Blocked("workspace folder is not open")
            val existing = parentDir.findFile(name)
            if (existing != null && existing.isDirectory) {
                return@runCatching WriteResult.Blocked("cannot write: path is an existing directory")
            }
            val normalized = normalize(path)
            if (existing != null && writeGuard != null) {
                // Existing file: read-before-write + staleness detection + rollback backup.
                val current = context.contentResolver.openInputStream(existing.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val currentFp = current?.let { ContentFingerprints.sha256(it) }
                when (
                    val check = writeGuard.checkWrite(
                        normalized,
                        fileExists = true,
                        currentFingerprint = currentFp,
                    )
                ) {
                    is FileWriteGuard.WriteCheck.Stale ->
                        return@runCatching WriteResult.Blocked(check.reason)
                    FileWriteGuard.WriteCheck.Allowed -> Unit
                }
                // Fail-closed: stash the previous content BEFORE mutating anything.
                val backup = writeGuard.stashBackup(normalized, current ?: "")
                if (backup is FileWriteGuard.BackupResult.Failed) {
                    return@runCatching WriteResult.Blocked("refusing to overwrite: ${backup.reason}")
                }
                if (!writeThrough(existing, content)) {
                    return@runCatching WriteResult.Blocked(
                        "write failed after backup — restore from the rollback journal if needed",
                    )
                }
                val newFp = ContentFingerprints.sha256(content)
                writeGuard.appendJournal(
                    writeGuard.journalEntry(
                        normalized,
                        (backup as FileWriteGuard.BackupResult.Stashed).fileName,
                        currentFp,
                        newFp,
                    ),
                )
                writeGuard.recordWrite(normalized, newFp)
                WriteResult.Written
            } else {
                // New file (or guard-less fallback): create and write.
                val target = existing
                    ?: parentDir.createFile("*/*", name)
                    ?: return@runCatching WriteResult.Blocked("could not create $path")
                if (!writeThrough(target, content)) {
                    return@runCatching WriteResult.Blocked("write failed")
                }
                if (writeGuard != null) {
                    val newFp = ContentFingerprints.sha256(content)
                    writeGuard.appendJournal(writeGuard.journalEntry(normalized, null, null, newFp))
                    writeGuard.recordWrite(normalized, newFp)
                }
                WriteResult.Written
            }
        }.getOrElse { WriteResult.Blocked("write error: ${it.message}") }
    }

    // ---------------------------------------------------------------- delete
    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (policyViolation(path) != null) return@runCatching false
            val file = resolve(path) ?: return@runCatching false
            if (!file.isFile) return@runCatching false
            val normalized = normalize(path)
            if (writeGuard != null) {
                // Keep rollback-able content before deleting (review #2 spirit).
                val current = context.contentResolver.openInputStream(file.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val backup = writeGuard.stashBackup(normalized, current ?: "")
                if (backup is FileWriteGuard.BackupResult.Failed) return@runCatching false
                writeGuard.appendJournal(
                    writeGuard.journalEntry(
                        normalized,
                        (backup as FileWriteGuard.BackupResult.Stashed).fileName,
                        current?.let { ContentFingerprints.sha256(it) },
                        null,
                    ),
                )
            }
            file.delete()
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ grep
    suspend fun grep(pattern: String, maxFiles: Int = 300): List<GrepHit> =
        withContext(Dispatchers.IO) {
            if (pattern.isBlank()) return@withContext emptyList()
            val regex = runCatching { Regex(pattern) }
                .getOrElse { Regex(Regex.escape(pattern)) }
            val hits = mutableListOf<GrepHit>()
            var scanned = 0

            suspend fun walk(dirPath: String) {
                if (hits.size >= 200 || scanned >= maxFiles) return
                list(dirPath).forEach { node ->
                    when {
                        // Generated and credential directories are never crawled (review #1).
                        node.isDir -> if (!SensitivePathPolicy.isCrawlBlockedDirectory(node.name)) walk(node.path)
                        scanned < maxFiles && isTextFile(node.name) -> {
                            scanned++
                            read(node.path)?.let { content ->
                                content.lines().forEachIndexed { i, line ->
                                    if (hits.size < 200 && regex.containsMatchIn(line)) {
                                        hits += GrepHit(node.path, i + 1, line.take(240))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            walk("")
            hits
        }

    // ----------------------------------------------------------------- utils
    private fun resolve(path: String): DocumentFile? {
        val root = _root.value ?: return null
        if (path.isBlank() || path == "." || path == "/") return root
        val parts = runCatching { SensitivePathPolicy.validateRelativePath(path) }.getOrElse { return null }
        return parts.fold(root) { dir, part -> dir?.findFile(part) ?: return null }
    }

    private fun policyViolation(path: String): String? =
        runCatching { SensitivePathPolicy.validateRelativePath(path); null }
            .getOrElse { it.message?.let { m -> "path blocked: $m" } ?: "path blocked by policy" }

    private suspend fun ensureDir(path: String): DocumentFile? {
        val root = _root.value ?: return null
        return path.trim('/').split('/').filter { it.isNotBlank() }.fold(root) { dir, part ->
            val found = dir.findFile(part)
            when {
                found != null && found.isDirectory -> found
                found != null -> null
                else -> dir.createDirectory(part)
            }
        }
    }

    private fun splitParent(path: String): Pair<String, String> {
        val parts = path.trim('/').split('/')
        val name = parts.last()
        val parent = parts.dropLast(1).joinToString("/")
        return parent to name
    }

    private fun join(base: String, name: String): String =
        if (base.isBlank()) name else "$base/$name"

    private fun isTextFile(name: String): Boolean = TEXT_EXT.any { name.endsWith(it) }

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')

    private fun writeThrough(target: DocumentFile, content: String): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } != null
        }.getOrDefault(false)

    private companion object {
        val TEXT_EXT = listOf(
            ".kt", ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".c", ".cpp", ".h",
            ".hpp", ".go", ".rs", ".md", ".txt", ".xml", ".json", ".yaml", ".yml",
            ".html", ".css", ".gradle", ".kts", ".toml", ".sh",
        )
    }
}

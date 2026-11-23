package com.tibatech.gptmax.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Workspace access over the Storage Access Framework: the user grants one
 * project folder (ACTION_OPEN_DOCUMENT_TREE) and every agent tool operates
 * on relative paths inside it. No broad storage permission is requested.
 */
class WorkspaceRepository(private val context: Context) {

    data class FileNode(val name: String, val path: String, val isDir: Boolean)
    data class GrepHit(val path: String, val line: Int, val text: String)

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
            .map { FileNode(it.name ?: "", join(dirPath, it.name ?: ""), it.isDirectory) }
            .sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy { it.name })
    }

    // ------------------------------------------------------------------ read
    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext null
        if (!file.isFile || !file.canRead()) return@withContext null
        context.contentResolver.openInputStream(file.uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    // ----------------------------------------------------------------- write
    suspend fun write(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (parent, name) = splitParent(path)
            val parentDir = ensureDir(parent) ?: return@runCatching false
            val existing = parentDir.findFile(name)
            val target = existing ?: parentDir.createFile("*/*", name) ?: return@runCatching false
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- delete
    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolve(path)?.delete() }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ grep
    suspend fun grep(pattern: String, maxFiles: Int = 300): List<GrepHit> =
        withContext(Dispatchers.IO) {
            if (pattern.isBlank()) return@withContext emptyList()
            val regex = runCatching { Regex(pattern) }
                .getOrElse { Regex(Regex.escape(pattern)) }
            val hits = mutableListOf<GrepHit>()
            var scanned = 0

            fun walk(dirPath: String) {
                if (hits.size >= 200 || scanned >= maxFiles) return
                list(dirPath).forEach { node ->
                    if (node.isDir) walk(node.path)
                    else if (scanned < maxFiles && isTextFile(node.name)) {
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
            walk("")
            hits
        }

    // ----------------------------------------------------------------- utils
    private fun resolve(path: String): DocumentFile? {
        val root = _root.value ?: return null
        if (path.isBlank() || path == "." || path == "/") return root
        return path.trim('/').split('/')
            .filter { it.isNotBlank() }
            .fold(root) { dir, part ->
                dir?.findFile(part) ?: return null
            }
    }

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

    private companion object {
        val TEXT_EXT = listOf(
            ".kt", ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".c", ".cpp", ".h",
            ".hpp", ".go", ".rs", ".md", ".txt", ".xml", ".json", ".yaml", ".yml",
            ".html", ".css", ".gradle", ".kts", ".toml", ".sh",
        )
    }
}

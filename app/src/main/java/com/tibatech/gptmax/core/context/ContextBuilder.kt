package com.tibatech.gptmax.core.context

import com.tibatech.gptmax.data.repo.WorkspaceRepository

/**
 * Builds the context the model sees: a compact repository map for planning
 * and per-file digests for execution, respecting a token budget.
 */
class ContextBuilder(private val workspace: WorkspaceRepository) {

    /** Flat tree, depth/entry limited, sized for a 4k-token local window. */
    suspend fun repoMap(maxEntries: Int = 150): String {
        val lines = mutableListOf<String>()
        fun walk(path: String, depth: Int) {
            if (lines.size >= maxEntries || depth > MAX_DEPTH) return
            workspace.list(path).sortedBy { it.path }.forEach { node ->
                if (lines.size >= maxEntries) return
                lines += "  ".repeat(depth) + (if (node.isDir) "[d] " else "    ") + node.name
                if (node.isDir) walk(node.path, depth + 1)
            }
        }
        walk("", 0)
        return lines.joinToString("\n").ifBlank { "(empty workspace — open a folder in Settings)" }
    }

    /** Contents of the files a plan touches, each truncated to [maxChars]. */
    suspend fun digest(paths: List<String>, maxChars: Int = 4_000): String =
        paths.take(8).joinToString("\n\n") { path ->
            val content = workspace.read(path)
            if (content == null) "### $path (unreadable)"
            else "### $path\n" + content.take(maxChars) +
                (if (content.length > maxChars) "\n…(truncated)" else "")
        }

    private companion object {
        const val MAX_DEPTH = 6
    }
}

package com.tibatech.gptmax.core.context

import com.tibatech.gptmax.data.repo.WorkspaceRepository

/**
 * Review #5: gathered file context must respect a total budget so old or
 * oversized reads cannot crowd out the rest of the model window. Files are
 * included in plan order until the budget runs out; skipped files are
 * reported so the loop can re-read them individually when needed.
 */
object ObservationBudget {

    data class Included(val path: String, val charLimit: Int)

    data class Allocation(
        val included: List<Included>,
        val skippedCount: Int,
    )

    fun allocate(
        paths: List<String>,
        perFileMax: Int = 4_000,
        totalMax: Int = 24_000,
    ): Allocation {
        val included = mutableListOf<Included>()
        var used = 0
        for (path in paths) {
            if (used >= totalMax) break
            val limit = minOf(perFileMax, totalMax - used)
            if (limit <= 0) break
            included += Included(path, limit)
            used += limit
        }
        return Allocation(included, (paths.size - included.size).coerceAtLeast(0))
    }
}

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

    /**
     * Contents of the files a plan touches, inside a total character budget.
     * Files beyond the budget are skipped and counted so the caller knows the
     * context is partial instead of silently truncated (review #5).
     */
    suspend fun digest(
        paths: List<String>,
        maxChars: Int = 4_000,
        totalMaxChars: Int = DEFAULT_TOTAL_CHARS,
    ): String {
        val allocation = ObservationBudget.allocate(paths, maxChars, totalMaxChars)
        if (allocation.included.isEmpty()) return "(no files)"
        val body = allocation.included.joinToString("\n\n") { inc ->
            val content = workspace.read(inc.path)
            if (content == null) "### ${inc.path} (unreadable)"
            else "### ${inc.path}\n" + content.take(inc.charLimit) +
                (if (content.length > inc.charLimit) "\n…(truncated)" else "")
        }
        val marker = if (allocation.skippedCount > 0) {
            "\n\n(context budget: ${allocation.skippedCount} file(s) skipped — re-read individually if needed)"
        } else ""
        return body + marker
    }

    private companion object {
        const val MAX_DEPTH = 6

        /** ~24k chars ≈ 6k tokens, sized for the smallest supported local window. */
        const val DEFAULT_TOTAL_CHARS = 24_000
    }
}

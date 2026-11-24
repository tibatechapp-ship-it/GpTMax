package com.tibatech.gptmax.core.context

import com.tibatech.gptmax.core.agent.WorkspaceOps
import com.tibatech.gptmax.core.guardrails.SensitivePaths

/**
 * Builds the context the model sees: a compact repository map for planning
 * and per-file digests for execution.
 *
 * Review #1: sensitive paths (.aws, .ssh, .env*, keystores, secrets dirs)
 * are never read into the context — the walk shows them as excluded and the
 * digest refuses to open them.
 * Review #5: digests share one hard total budget so older observations cannot
 * crowd out the files the plan actually touches now.
 */
class ContextBuilder(private val workspace: WorkspaceOps) {

    /** Flat tree, depth/entry limited, sized for a 4k-token local window. */
    suspend fun repoMap(maxEntries: Int = 150): String {
        val lines = mutableListOf<String>()
        suspend fun walk(path: String, depth: Int) {
            if (lines.size >= maxEntries || depth > MAX_DEPTH) return
            workspace.list(path).sortedBy { it.path }.forEach { node ->
                if (lines.size >= maxEntries) return
                if (SensitivePaths.isSensitive(node.path)) {
                    lines += "  ".repeat(depth) + "    " + node.name +
                        if (node.isDir) "/  (excluded)" else "  (excluded)"
                    return@forEach // never descend into sensitive subtrees
                }
                lines += "  ".repeat(depth) + (if (node.isDir) "[d] " else "    ") + node.name
                if (node.isDir) walk(node.path, depth + 1)
            }
        }
        walk("", 0)
        return lines.joinToString("\n").ifBlank { "(empty workspace — open a folder in Settings)" }
    }

    /**
     * Contents of the files a plan touches, each capped at [maxChars] and the
     * whole batch capped at [totalBudget] characters, in plan order.
     */
    suspend fun digest(
        paths: List<String>,
        maxChars: Int = 4_000,
        totalBudget: Int = DEFAULT_TOTAL_BUDGET,
    ): String {
        var remaining = totalBudget
        val out = StringBuilder()
        for (path in paths.take(8).distinct()) {
            if (remaining <= MIN_SLICE) break
            if (SensitivePaths.isSensitive(path)) {
                out.append("### ").append(path).append(" (blocked: sensitive path)\n\n")
                continue
            }
            val content = workspace.read(path)
            if (content == null) {
                out.append("### ").append(path).append(" (unreadable)\n\n")
                continue
            }
            val slice = minOf(maxChars, remaining, content.length)
            out.append("### ").append(path).append('\n')
                .append(content, 0, slice)
            if (content.length > slice) out.append("\n…(truncated)")
            out.append("\n\n")
            remaining -= slice
        }
        return out.toString().trim()
    }

    companion object {
        const val DEFAULT_TOTAL_BUDGET = 20_000
        private const val MIN_SLICE = 400
        private const val MAX_DEPTH = 6
    }
}

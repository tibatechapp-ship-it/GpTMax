package com.tibatech.gptmax.core.context

/**
 * Fix #5 (code review): pure budgeting helpers so the model never sees
 * unbounded context. File digests are divided under a hard character
 * budget, and rolling observations are trimmed newest-first so stale
 * results cannot crowd the local 4k-token window.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
object ContextBudget {

    data class Allocation(val path: String, val chars: Int)

    /**
     * Splits [budgetChars] evenly across [paths], capped at [perFileCap]
     * each. When the budget cannot give every path a positive share the
     * result is empty (callers should reduce the path list).
     */
    fun allocate(
        paths: List<String>,
        budgetChars: Int,
        perFileCap: Int = 4_000,
    ): List<Allocation> {
        if (paths.isEmpty() || budgetChars <= 0) return emptyList()
        val perFile = minOf(perFileCap, budgetChars / paths.size)
        if (perFile <= 0) return emptyList()
        return paths.map { Allocation(it, perFile) }
    }

    /**
     * Keeps the newest entries whose combined size fits [budgetChars];
     * older entries are dropped first. Entries larger than the remaining
     * budget are skipped, never partially included.
     */
    fun <T> trimNewestFirst(
        entries: List<T>,
        budgetChars: Int,
        sizeOf: (T) -> Int,
    ): List<T> {
        if (budgetChars <= 0) return emptyList()
        val kept = mutableListOf<T>()
        var used = 0
        for (entry in entries.asReversed()) {
            val size = sizeOf(entry)
            if (used + size > budgetChars) continue
            kept += entry
            used += size
        }
        kept.reverse()
        return kept
    }

    fun trimNewestFirst(entries: List<String>, budgetChars: Int): List<String> =
        trimNewestFirst(entries, budgetChars) { it.length }
}

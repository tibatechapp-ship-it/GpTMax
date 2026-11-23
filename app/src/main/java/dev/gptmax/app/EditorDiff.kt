package dev.gptmax.app

internal fun buildEditorDiff(before: String, after: String, maxChangedLines: Int = 40): String {
    require(maxChangedLines in 1..200)
    if (before == after) return "No changes."

    val oldLines = before.split('\n')
    val newLines = after.split('\n')
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
        prefix++
    }
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
        oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) {
        suffix++
    }

    val removed = oldLines.subList(prefix, oldLines.size - suffix)
    val added = newLines.subList(prefix, newLines.size - suffix)
    return buildString {
        appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
        removed.take(maxChangedLines).forEach { appendLine("- " + it.take(300)) }
        added.take(maxChangedLines).forEach { appendLine("+ " + it.take(300)) }
        if (removed.size > maxChangedLines || added.size > maxChangedLines) {
            appendLine("[diff truncated]")
        }
    }.take(6_000)
}

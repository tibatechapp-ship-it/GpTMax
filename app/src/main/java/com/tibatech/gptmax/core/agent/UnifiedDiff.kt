package com.tibatech.gptmax.core.agent

/**
 * Phase 5 (external-review roadmap M5 "patch validation and conflict
 * detection"): a self-contained unified-diff engine.
 *
 * [apply] is the fail-closed applier behind the `patch_file` tool: it
 * computes the ENTIRE patched content up-front and refuses the WHOLE patch
 * when any hunk's context does not match the file — partial application is
 * impossible by construction, so a stale or hallucinated diff can never
 * corrupt a workspace file. Each hunk is located by CONTENT: first at its
 * stated position, then in a bounded search window around it, so hunks whose
 * line numbers drifted a few lines still land correctly while genuinely
 * stale context is rejected with an actionable error.
 *
 * [render] produces a unified diff between two snapshots. AgentCore feeds
 * rendered diffs to the reviewer as REAL change evidence (before phase 5
 * the review only saw file names), sourced from the WriteJournal entries
 * that already record before/after content.
 *
 * Line endings: context lines keep their original bytes (a CRLF file keeps
 * CRLF), matching ignores a trailing '\r' so LF-generated patches apply to
 * CRLF files, and newline-at-EOF is tracked explicitly through the
 * "\ No newline at end of file" marker. A diff that patches more than one
 * file is rejected outright — one file per patch_file call.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
object UnifiedDiff {

    data class PatchOutcome(
        val ok: Boolean,
        val content: String? = null,
        val error: String? = null,
        val hunksApplied: Int = 0,
    )

    /** How far a hunk may drift from its stated line and still be found. */
    const val DRIFT_WINDOW = 200

    // ------------------------------------------------------------------ apply

    fun apply(original: String, diff: String): PatchOutcome {
        if (diff.isBlank()) return PatchOutcome(false, error = "empty patch")
        val (originalLines, originalTrailingNl) = splitLines(original)
        val parsed = parse(diff)
        if (parsed.error != null) return PatchOutcome(false, error = parsed.error)
        val hunks = parsed.hunks
        if (hunks.isEmpty()) {
            return PatchOutcome(false, error = "no @@ hunks found in patch")
        }

        val lines = originalLines.toMutableList()
        var delta = 0
        var minPos = 0
        var forcedNoNl = false
        var lastTouchEof = false
        var lastNewBlockExtends = false
        for ((idx, hunk) in hunks.withIndex()) {
            val expected = hunk.oldLines
            val deltaAtEntry = delta
            val pos: Int = if (expected.isEmpty()) {
                val insert = (hunk.newStart - 1 + delta).coerceIn(0, lines.size)
                if (insert < minPos) {
                    return PatchOutcome(
                        false,
                        error = "hunk #${idx + 1}: would overlap an earlier hunk",
                    )
                }
                insert
            } else {
                findMatch(lines, expected, hunk.oldStart - 1 + delta, minPos)
                    ?: return PatchOutcome(
                        false,
                        error = "hunk #${idx + 1}: context does not match the file near " +
                            "line ${hunk.oldStart} (searched ±$DRIFT_WINDOW lines); refusing " +
                            "to patch — regenerate the diff against the current content",
                    )
            }
            val rebuilt = mutableListOf<String>()
            rebuilt += lines.subList(0, pos)
            rebuilt += hunk.newLines
            rebuilt += lines.subList(pos + expected.size, lines.size)
            lines.clear()
            lines += rebuilt
            delta += hunk.newLines.size - expected.size
            minPos = pos + hunk.newLines.size
            forcedNoNl = forcedNoNl || hunk.newNoNl
            // Did the LAST hunk consume the original EOF, and does its new
            // block actually introduce/replace lines there? That decides the
            // trailing-newline state when the original had none (GNU marker
            // semantics: a '+' EOF line without a "\ No newline" marker ends
            // WITH a newline; a pure deletion or no-op keeps the old state).
            lastTouchEof = (pos + expected.size) >= (originalLines.size + deltaAtEntry)
            lastNewBlockExtends =
                hunk.newLines.size >= hunk.oldLines.size && hunk.newLines != hunk.oldLines
        }

        val endsWithNl = when {
            forcedNoNl -> false
            originalTrailingNl -> true
            else -> lastTouchEof && lastNewBlockExtends
        }
        val content = lines.joinToString("\n") + if (endsWithNl) "\n" else ""
        return PatchOutcome(true, content = content, hunksApplied = hunks.size)
    }

    // ----------------------------------------------------------------- render

    /**
     * One-hunk unified diff between [before] and [after]. Not minimal — the
     * hunk spans everything between the first and last changed line — but
     * exact, and `apply(before, render(before, after)) == after` for every
     * case [apply] models, except a newline-only difference at EOF (line
     * comparison cannot see it, documented in PHASE5 doc).
     */
    fun render(
        path: String,
        before: String,
        after: String,
        contextLines: Int = 3,
    ): String {
        val (a, aNl) = splitLines(before)
        val (b, bNl) = splitLines(after)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val aRegion = a.subList(p, a.size - s)
        val bRegion = b.subList(p, b.size - s)
        if (aRegion == bRegion) return "" // line-identical (newline-only EOF diffs are invisible)

        val ctxStart = maxOf(0, p - contextLines)
        val ctxEnd = minOf(a.size, a.size - s + contextLines)
        val contextPre = a.subList(ctxStart, p)
        val contextPost = a.subList(a.size - s, ctxEnd)
        val oldCount = contextPre.size + aRegion.size + contextPost.size
        val newCount = contextPre.size + bRegion.size + contextPost.size

        // EOF-marker bookkeeping (GNU "\ No newline at end of file"):
        // - after a '-' line: only the OLD file lacks the trailing newline;
        // - after a '+' line: only the NEW file lacks it;
        // - after a ' ' (context) line: BOTH files lack it.
        val oldEofInHunk = ctxEnd == a.size
        val oldEofIsMinus = oldEofInHunk && s == 0 && aRegion.isNotEmpty()
        val oldEofIsContext = oldEofInHunk && s > 0
        val newEofIsPlus = (p + bRegion.size == b.size) && bRegion.isNotEmpty()

        val out = StringBuilder()
        out.append("--- a/").append(path).append('\n')
        out.append("+++ b/").append(path).append('\n')
        out.append("@@ -").append(ctxStart + 1).append(',').append(oldCount)
            .append(" +").append(ctxStart + 1).append(',').append(newCount)
            .append(" @@\n")
        for (line in contextPre) out.append(' ').append(line).append('\n')
        for (line in aRegion) out.append('-').append(line).append('\n')
        if (oldEofIsMinus && !aNl) out.append("\\ No newline at end of file\n")
        for (line in bRegion) out.append('+').append(line).append('\n')
        if (newEofIsPlus && !bNl) out.append("\\ No newline at end of file\n")
        for (line in contextPost) out.append(' ').append(line).append('\n')
        // Unchanged context EOF line: marker only when BOTH sides lack the
        // newline (the parser reads it as both). When only one side differs,
        // the newline state is left to the original file (documented limit).
        if (oldEofIsContext && !aNl && !bNl) out.append("\\ No newline at end of file\n")
        return out.toString()
    }

    // ------------------------------------------------------------------ parse

    private class Hunk(
        val oldStart: Int,
        val newStart: Int,
        val oldLines: List<String>,
        val newLines: List<String>,
        val oldNoNl: Boolean,
        val newNoNl: Boolean,
    )

    private class HunkBuilder(val oldStart: Int, val newStart: Int) {
        val oldLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()
        var oldNoNl = false
        var newNoNl = false
        var lastMinus = false
        var lastPlus = false

        fun build() = Hunk(oldStart, newStart, oldLines.toList(), newLines.toList(), oldNoNl, newNoNl)
    }

    private class Parsed(val hunks: List<Hunk>, val error: String?)

    private fun parse(diff: String): Parsed {
        val header = Regex("""^@@ -(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")
        val hunks = mutableListOf<Hunk>()
        var cur: HunkBuilder? = null
        // split('\n') on a trailing-newline diff yields a phantom empty last
        // element — it is the split artifact, NOT a real blank context line.
        // Skipping ONLY that final artifact keeps genuine empty context
        // lines inside hunks working while old hunks no longer gain a ghost
        // "" line that made apply() refuse its own render() output.
        val rawLines = diff.split('\n')
        val artifactIdx = if (diff.endsWith("\n")) rawLines.lastIndex else -1
        for ((li, raw) in rawLines.withIndex()) {
            if (li == artifactIdx) continue
            val line = raw.trimEnd('\r')
            val m = header.find(line)
            if (m != null) {
                cur?.let { hunks += it.build() }
                cur = HunkBuilder(m.groupValues[1].toInt(), m.groupValues[3].toInt())
                continue
            }
            val b = cur
            if (b == null) {
                if (line.startsWith("GIT binary patch") || line.startsWith("Binary files ")) {
                    return Parsed(emptyList(), "binary patches are not supported")
                }
                continue // file headers and prose before the first hunk
            }
            when {
                line.startsWith("\\") -> {
                    when {
                        b.lastMinus && !b.lastPlus -> b.oldNoNl = true
                        b.lastPlus && !b.lastMinus -> b.newNoNl = true
                        else -> { b.oldNoNl = true; b.newNoNl = true }
                    }
                }
                line.startsWith("--- ") || line.startsWith("+++ ") ->
                    return Parsed(
                        emptyList(),
                        "multi-file patch detected — patch_file applies ONE file per call",
                    )
                line.startsWith(" ") -> {
                    val content = line.drop(1)
                    b.oldLines += content
                    b.newLines += content
                    b.lastMinus = false
                    b.lastPlus = false
                }
                line.startsWith("-") -> {
                    b.oldLines += line.drop(1)
                    b.lastMinus = true
                    b.lastPlus = false
                }
                line.startsWith("+") -> {
                    b.newLines += line.drop(1)
                    b.lastMinus = false
                    b.lastPlus = true
                }
                line.isEmpty() -> {
                    b.oldLines += ""
                    b.newLines += ""
                    b.lastMinus = false
                    b.lastPlus = false
                }
                else -> return Parsed(
                    emptyList(),
                    "malformed hunk line: \"${line.take(40)}\" (expected ' ', '-', '+', '\\\\' or '@@')",
                )
            }
        }
        cur?.let { hunks += it.build() }
        return Parsed(hunks, null)
    }

    // ------------------------------------------------------------------ utils

    /** Splits into lines; the pair carries whether the text ends with '\n'. */
    internal fun splitLines(s: String): Pair<List<String>, Boolean> = when {
        s.isEmpty() -> Pair(emptyList(), false)
        s.endsWith("\n") -> Pair(s.dropLast(1).split('\n'), true)
        else -> Pair(s.split('\n'), false)
    }

    private fun sameLine(a: String, b: String): Boolean =
        a.trimEnd('\r') == b.trimEnd('\r')

    private fun matchesAt(lines: List<String>, expected: List<String>, at: Int): Boolean =
        at >= 0 &&
            at + expected.size <= lines.size &&
            (0 until expected.size).all { k -> sameLine(lines[at + k], expected[k]) }

    /** Exact-block match at [hint], else nearest match within ±[DRIFT_WINDOW]. */
    private fun findMatch(lines: List<String>, expected: List<String>, hint: Int, minPos: Int): Int? {
        if (hint >= minPos && matchesAt(lines, expected, hint)) return hint
        for (off in 1..DRIFT_WINDOW) {
            for (candidate in intArrayOf(hint + off, hint - off)) {
                if (candidate < minPos || candidate + expected.size > lines.size) continue
                if (matchesAt(lines, expected, candidate)) return candidate
            }
        }
        return null
    }
}

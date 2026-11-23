package dev.gptmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDiffTest {
    @Test
    fun reportsNoChangesForIdenticalContent() {
        assertEquals("No changes.", buildEditorDiff("same\ntext", "same\ntext"))
    }

    @Test
    fun reportsChangedLinesAndPreservesUnchangedPrefixSuffix() {
        val diff = buildEditorDiff("first\nold line\nlast", "first\nnew line\nlast")
        assertTrue(diff.contains("- old line"))
        assertTrue(diff.contains("+ new line"))
        assertTrue(!diff.contains("- first"))
        assertTrue(!diff.contains("+ last"))
    }

    @Test
    fun truncatesLargeChangedSections() {
        val before = (1..100).joinToString("\n") { "old-" + it }
        val after = (1..100).joinToString("\n") { "new-" + it }
        val diff = buildEditorDiff(before, after, maxChangedLines = 5)
        assertTrue(diff.contains("[diff truncated]"))
        assertTrue(diff.length <= 6_000)
    }
}

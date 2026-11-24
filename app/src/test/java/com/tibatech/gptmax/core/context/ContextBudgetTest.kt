package com.tibatech.gptmax.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix #5 regression tests: context budgeting that keeps digests and
 * rolling observations inside the local model window.
 */
class ContextBudgetTest {

    @Test
    fun `allocate divides the budget and respects the per-file cap`() {
        val allocs = ContextBudget.allocate(
            listOf("a.kt", "b.kt", "c.kt"),
            budgetChars = 9_000,
            perFileCap = 2_000,
        )
        assertEquals(3, allocs.size)
        assertTrue(allocs.all { it.chars == 2_000 })
    }

    @Test
    fun `allocate shrinks per file when the budget is tight`() {
        val allocs = ContextBudget.allocate(listOf("a.kt", "b.kt", "c.kt"), budgetChars = 300)
        assertTrue(allocs.all { it.chars == 100 })
    }

    @Test
    fun `allocate is empty when the budget cannot cover every path`() {
        assertTrue(ContextBudget.allocate(listOf("a.kt", "b.kt"), budgetChars = 1).isEmpty())
        assertTrue(ContextBudget.allocate(emptyList(), budgetChars = 1_000).isEmpty())
    }

    @Test
    fun `trimNewestFirst keeps the newest entries within budget`() {
        val out = ContextBudget.trimNewestFirst(listOf("aaaa", "bb", "c"), budgetChars = 4)
        // newest first: "c" (1) fits, "bb" (2) fits, "aaaa" (4) would exceed — skipped
        assertEquals(listOf("bb", "c"), out)
    }

    @Test
    fun `trimNewestFirst drops everything on a zero budget`() {
        assertTrue(ContextBudget.trimNewestFirst(listOf("a", "b"), budgetChars = 0).isEmpty())
    }

    @Test
    fun `generic trim works on typed entries`() {
        data class Observation(val id: Int, val text: String)
        val out = ContextBudget.trimNewestFirst(
            listOf(Observation(1, "long observation text"), Observation(2, "tiny")),
            budgetChars = 6,
        ) { it.text.length }
        assertEquals(listOf(2), out.map { it.id })
    }
}

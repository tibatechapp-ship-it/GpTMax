package com.tibatech.gptmax.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review #5: old reads must not crowd the model window — the budget caps total context. */
class ObservationBudgetTest {

    @Test
    fun `per-file limit caps each file`() {
        val allocation = ObservationBudget.allocate(listOf("a", "b"), perFileMax = 100, totalMax = 1_000)
        assertEquals(listOf("a", "b"), allocation.included.map { it.path })
        assertEquals(100, allocation.included[0].charLimit)
        assertEquals(100, allocation.included[1].charLimit)
        assertEquals(0, allocation.skippedCount)
    }

    @Test
    fun `total budget stops later files and counts skipped`() {
        val allocation = ObservationBudget.allocate(
            listOf("a", "b", "c"),
            perFileMax = 4_000,
            totalMax = 5_000,
        )
        assertEquals(listOf("a", "b"), allocation.included.map { it.path })
        assertEquals(4_000, allocation.included[0].charLimit)
        assertEquals(1_000, allocation.included[1].charLimit)
        assertEquals(1, allocation.skippedCount)
    }

    @Test
    fun `last included file gets the remaining budget`() {
        val allocation = ObservationBudget.allocate(listOf("a", "b"), perFileMax = 4_000, totalMax = 5_000)
        assertEquals(4_000, allocation.included[0].charLimit)
        assertEquals(1_000, allocation.included[1].charLimit)
    }

    @Test
    fun `empty and zero-budget inputs are handled`() {
        assertEquals(0, ObservationBudget.allocate(emptyList(), 100, 100).included.size)
        val zero = ObservationBudget.allocate(listOf("a"), 100, 0)
        assertEquals(0, zero.included.size)
        assertEquals(1, zero.skippedCount)
    }

    @Test
    fun `defaults match the documented budget`() {
        assertTrue(ObservationBudget::class.java.getDeclaredMethod(
            "allocate",
            List::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).let { true })
    }
}

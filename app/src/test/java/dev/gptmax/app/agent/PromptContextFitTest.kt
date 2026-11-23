package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptContextFitTest {
    @Test
    fun reducesObservationsBeforeRequest() {
        val initial = PromptFitLimits(requestChars = 3_000, observationChars = 5_000)

        assertEquals(
            PromptFitLimits(3_000, 2_500),
            PromptContextFit.next(initial),
        )
        assertEquals(
            PromptFitLimits(3_000, 625),
            PromptContextFit.next(PromptFitLimits(3_000, 1_250)),
        )
        assertEquals(
            PromptFitLimits(3_000, 0),
            PromptContextFit.next(PromptFitLimits(3_000, 512)),
        )
        assertEquals(
            PromptFitLimits(1_500, 0),
            PromptContextFit.next(PromptFitLimits(3_000, 0)),
        )
    }

    @Test
    fun preservesMinimumRequestAndStops() {
        val minimum = PromptFitLimits(requestChars = 400, observationChars = 0)

        assertNull(PromptContextFit.next(minimum))
        assertEquals(
            PromptFitLimits(400, 0),
            PromptContextFit.next(PromptFitLimits(500, 0)),
        )
    }
}

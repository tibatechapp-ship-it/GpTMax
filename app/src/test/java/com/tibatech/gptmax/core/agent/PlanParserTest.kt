package com.tibatech.gptmax.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {

    @Test
    fun `parses fenced json plan`() {
        val raw = """
            ```json
            [{"tool":"list_dir","args":{"path":"src"},"why":"explore"},
             {"tool":"write_file","args":{"path":"a.txt","content":"hi"},"why":"add"}]
            ```
        """.trimIndent()
        val steps = PlanParser.parse(raw)
        assertEquals(2, steps.size)
        assertEquals("list_dir", steps[0].tool)
        assertEquals("src", steps[0].argsJson.let { it.substringAfter("path\":\"").substringBefore("\"") })
        assertFalse(steps[0].isWrite)
        assertTrue(steps[1].isWrite)
    }

    @Test
    fun `parses bare array without fences`() {
        val raw = "[{\"tool\":\"read_file\",\"args\":{\"path\":\"a.md\"}}]"
        val steps = PlanParser.parse(raw)
        assertEquals(1, steps.size)
        assertEquals("read_file", steps[0].tool)
        assertFalse(steps[0].isWrite)
    }

    @Test
    fun `garbage yields no steps`() {
        assertEquals(0, PlanParser.parse("no json here").size)
        assertEquals(0, PlanParser.parse("").size)
    }

    @Test
    fun `write and delete tools are flagged for approval`() {
        val steps = PlanParser.parse(
            "[{\"tool\":\"delete_file\",\"args\":{\"path\":\"x\"}}," +
                "{\"tool\":\"grep\",\"args\":{\"pattern\":\"y\"}}]",
        )
        assertTrue(steps[0].isWrite)
        assertFalse(steps[1].isWrite)
    }

    @Test
    fun `blank tool entries are dropped`() {
        val steps = PlanParser.parse("[{\"tool\":\"\"},{\"tool\":\"finish\"}]")
        assertEquals(1, steps.size)
        assertEquals("finish", steps[0].tool)
    }
}

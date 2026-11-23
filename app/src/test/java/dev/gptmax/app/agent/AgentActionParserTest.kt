package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionParserTest {
    @Test
    fun parsesToolCallWithNestedStringArguments() {
        val action = AgentActionParser.parse(
            """{"type":"tool","id":"t1","name":"file.read","arguments":{"path":"src/Main.kt"}}""",
            4,
        ) as AgentModelAction.CallTool

        assertEquals("file.read", action.call.name)
        assertEquals("src/Main.kt", action.call.arguments["path"])
    }

    @Test
    fun parsesFinalAnswerAndEscapedNewline() {
        val action = AgentActionParser.parse(
            """{"type":"final","text":"Line one\nLine two"}""",
            1,
        ) as AgentModelAction.FinalAnswer

        assertEquals("Line one\nLine two", action.text)
    }

    @Test
    fun rejectsNonJsonAndUnexpectedValues() {
        assertTrue(runCatching { AgentActionParser.parse("I will edit the file now.", 0) }.isFailure)
        assertTrue(
            runCatching {
                AgentActionParser.parse(
                    """{"type":"tool","name":"file.read","arguments":{"limit":3}}""",
                    0,
                )
            }.isFailure,
        )
    }
}

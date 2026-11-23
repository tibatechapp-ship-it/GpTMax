package dev.gptmax.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolGuidanceTest {
    @Test
    fun guidanceNeverRequestsToolsMissingFromSafWorkspaceRegistry() {
        val lines = AgentToolGuidance.lines(
            setOf(
                "context.index",
                "context.recall",
                "context.remember",
                "context.checkpoint",
                "context.restore",
                "context.stage",
                "workspace.list",
                "file.read",
                "file.search",
                "file.write",
                "file.replace",
            ),
        )
        val promptGuidance = lines.joinToString("\n")

        assertFalse(promptGuidance.contains("workspace.overview"))
        assertFalse(promptGuidance.contains("git.workspace"))
        assertTrue(promptGuidance.contains("context.recall"))
        assertTrue(promptGuidance.contains("context.index"))
    }

    @Test
    fun guidanceIncludesOverviewAndGitOnlyWhenRegistered() {
        val lines = AgentToolGuidance.lines(setOf("workspace.overview", "git.workspace"))

        assertTrue(lines.any { it.contains("workspace.overview") })
        assertTrue(lines.any { it.contains("git.workspace") })
        assertFalse(lines.any { it.contains("context.recall") })
    }
}

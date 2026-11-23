package dev.gptmax.app.agent

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContextEngineTest {
    @Test
    fun persistsIndexNotesAndCheckpointAndRedactsSecretLikeLines() {
        val directory = Files.createTempDirectory("gptmax-context").toFile()
        val store = directory.resolve("context.bin")
        try {
            val engine = ProjectContextEngine(store)
            engine.replaceIndex(listOf(engine.createRecord(
                "src/Agent.kt", 120, 10,
                "class Agent {\\n// bounded tool loop\\nAPI_KEY=private-value\\n}",
            )))
            engine.remember("architecture", "Agent uses a bounded tool loop and explicit tool permissions.")
            engine.checkpoint("task-1", "Improve context recall", "Local index persisted",
                "Add semantic ranking and validate Android build")
            val reopened = ProjectContextEngine(store)
            val result = reopened.recall("bounded tool loop architecture")
            assertTrue(result.contains("src/Agent.kt"))
            assertTrue(result.contains("REDACTED"))
            assertTrue(result.contains("explicit tool permissions"))
            assertTrue(result.contains("Add semantic ranking"))
            assertFalse(result.contains("private-value"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsSecretLikeMemoryAndBoundsNoteSize() {
        val directory = Files.createTempDirectory("gptmax-context-security").toFile()
        try {
            val engine = ProjectContextEngine(directory.resolve("context.bin"))
            var rejected = false
            try { engine.remember("credentials", "api_key=do-not-save") }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { directory.deleteRecursively() }
    }
}

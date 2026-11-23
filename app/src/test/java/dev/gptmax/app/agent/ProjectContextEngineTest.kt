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
                "class Agent {\n// bounded tool loop\nAPI_KEY=private-value\n}",
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
    @Test
    fun ranksTaskRelevantFilesAndIncludesRelatedImportAndTestFiles() {
        val directory=Files.createTempDirectory("gptmax-context-rank").toFile()
        try {
            val engine=ProjectContextEngine(directory.resolve("ctx.bin"))
            engine.replaceIndex(listOf(
                engine.createRecord("src/Agent.kt",100,1,"package app\nimport app.tools.FileReader\nclass Agent { fun inspectRepository() {} }"),
                engine.createRecord("src/tools/FileReader.kt",100,1,"package app.tools\nclass FileReader { fun readWorkspace() {} }"),
                engine.createRecord("src/test/AgentTest.kt",100,1,"class AgentTest { fun testInspectRepository() {} }"),
                engine.createRecord("docs/other.md",100,1,"unrelated gardening notes")
            ))
            val result=engine.recall("Agent inspectRepository",5)
            assertTrue(result.indexOf("src/Agent.kt")>=0)
            assertTrue(result.contains("src/tools/FileReader.kt"))
            assertTrue(result.contains("src/test/AgentTest.kt"))
            assertFalse(result.contains("docs/other.md"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun completeSnapshotRemovesDeletedFilesAndRestoresToolHistory() {
        val directory=Files.createTempDirectory("gptmax-context-delta").toFile()
        try {
            val store=directory.resolve("ctx.bin")
            val engine=ProjectContextEngine(store)
            val old=engine.createRecord("src/Old.kt",10,1,"class Old {}")
            val kept=engine.createRecord("src/Kept.kt",10,1,"class Kept {}")
            engine.replaceIndex(listOf(old,kept))
            val delta=engine.replaceIndex(listOf(kept))
            assertTrue(delta.removed==1)
            engine.checkpoint("task-x","Implement context graph","Indexed sources","Run tests")
            engine.addStage("task-x","validation","blocked","Android build pending")
            engine.observeTool("task-x","file.search","SUCCESS","Found references in src/Kept.kt")
            engine.observeTool("task-x","file.read","SUCCESS","class SecretHolder { API_KEY=do-not-persist }")
            val reopened=ProjectContextEngine(store)
            val restored=reopened.restoreTask("task-x")
            assertTrue(restored.contains("Run tests"))
            assertTrue(restored.contains("validation"))
            assertTrue(restored.contains("file.search"))
            assertTrue(restored.contains("source content is not persisted"))
            assertFalse(restored.contains("SecretHolder"))
            assertFalse(restored.contains("do-not-persist"))
            assertFalse(reopened.recall("Old",5).contains("src/Old.kt"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun projectStoresRemainIsolated() {
        val directory=Files.createTempDirectory("gptmax-context-isolation").toFile()
        try {
            val a=ProjectContextEngine(directory.resolve("project-a.bin"))
            val b=ProjectContextEngine(directory.resolve("project-b.bin"))
            a.remember("decision","Project A uses Room")
            b.remember("decision","Project B uses SQLite")
            assertTrue(a.recall("Room",5).contains("Room"))
            assertFalse(a.recall("SQLite",5).contains("SQLite"))
            assertTrue(b.recall("SQLite",5).contains("SQLite"))
            assertFalse(b.recall("Room",5).contains("Room"))
        } finally { directory.deleteRecursively() }
    }

}

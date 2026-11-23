package dev.gptmax.app.agent

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolsTest {
    @Test
    fun readsAndSearchesWorkspaceText() = runBlocking {
        val root = Files.createTempDirectory("gptmax-workspace").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Example.kt").writeText("fun hello() = \"world\"\n")
            val read = WorkspaceReadTool(root).execute(
                AgentToolCall("r1", "file.read", mapOf("path" to "src/Example.kt")),
                AtomicBoolean(false),
            )
            val search = WorkspaceSearchTool(root).execute(
                AgentToolCall("s1", "file.search", mapOf("query" to "hello")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, read.status)
            assertTrue(read.output.contains("hello"))
            assertTrue(search.output.contains("src/Example.kt:1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversal() = runBlocking {
        val root = Files.createTempDirectory("gptmax-workspace").toFile()
        val outside = Files.createTempFile("gptmax-outside", ".txt").toFile()
        outside.writeText("secret")
        try {
            val result = runCatching {
                WorkspaceReadTool(root).execute(
                    AgentToolCall("r1", "file.read", mapOf("path" to "../${outside.name}")),
                    AtomicBoolean(false),
                )
            }
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun blocksCredentialFiles() = runBlocking {
        val root = Files.createTempDirectory("gptmax-workspace").toFile()
        try {
            File(root, ".env").writeText("API_KEY=not-for-agent")
            val result = runCatching {
                WorkspaceReadTool(root).execute(
                    AgentToolCall("r1", "file.read", mapOf("path" to ".env")),
                    AtomicBoolean(false),
                )
            }
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesOnlyInsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("gptmax-workspace").toFile()
        try {
            val result = WorkspaceWriteTool(root).execute(
                AgentToolCall("w1", "file.write", mapOf("path" to "New.kt", "content" to "class New")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, result.status)
            assertEquals("class New", File(root, "New.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

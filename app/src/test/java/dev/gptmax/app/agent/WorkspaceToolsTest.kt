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
    fun searchNeverCrawlsSecretDirectories() = runBlocking {
        val root = Files.createTempDirectory("gptmax-secret").toFile()
        try {
            File(root, ".ssh").mkdirs()
            File(root, ".ssh/id_rsa").writeText("SECRET_RSA_MATERIAL")
            File(root, ".aws").mkdirs()
            File(root, ".aws/notes.txt").writeText("SECRET_AWS_NOTES")
            File(root, ".env.development").writeText("DEV_SECRET_TOKEN")
            File(root, "src").mkdirs()
            File(root, "src/App.kt").writeText("mentions SECRET_AWS_NOTES in docs")
            val search = WorkspaceSearchTool(root).execute(
                AgentToolCall("s1", "file.search", mapOf("query" to "SECRET")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, search.status)
            assertTrue(!search.output.contains(".ssh"))
            assertTrue(!search.output.contains(".aws"))
            assertTrue(!search.output.contains(".env.development"))
            assertTrue(search.output.contains("src/App.kt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsOverwriteWithoutPriorRead() = runBlocking {
        val root = Files.createTempDirectory("gptmax-noread").toFile()
        try {
            File(root, "Existing.kt").writeText("keep me")
            val writer = WorkspaceWriteTool(root)
            val result = runCatching {
                writer.execute(
                    AgentToolCall("w1", "file.write", mapOf("path" to "Existing.kt", "content" to "clobber")),
                    AtomicBoolean(false),
                )
            }
            assertTrue("unread overwrite must be rejected", result.isFailure)
            assertEquals("keep me", File(root, "Existing.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsFileChangedSinceLastRead() = runBlocking {
        val root = Files.createTempDirectory("gptmax-stale").toFile()
        try {
            val guard = FileWriteGuard(File(root, ".gptmax/edit-log.jsonl"))
            val reader = WorkspaceReadTool(root, guard)
            val writer = WorkspaceWriteTool(root, guard)
            File(root, "Notes.kt").writeText("original content")
            reader.execute(
                AgentToolCall("r1", "file.read", mapOf("path" to "Notes.kt")),
                AtomicBoolean(false),
            )
            File(root, "Notes.kt").writeText("external edit")
            val stale = runCatching {
                writer.execute(
                    AgentToolCall("w1", "file.write", mapOf("path" to "Notes.kt", "content" to "agent overwrite")),
                    AtomicBoolean(false),
                )
            }
            assertTrue("stale overwrite must be rejected", stale.isFailure)
            assertEquals("external edit", File(root, "Notes.kt").readText())
            reader.execute(
                AgentToolCall("r2", "file.read", mapOf("path" to "Notes.kt")),
                AtomicBoolean(false),
            )
            val ok = writer.execute(
                AgentToolCall("w2", "file.write", mapOf("path" to "Notes.kt", "content" to "agent overwrite v2")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, ok.status)
            assertTrue(ok.output.contains("Rollback"))
            val backups = root.listFiles { f -> f.name.startsWith("Notes.kt.gptmax-backup-") }.orEmpty()
            assertEquals(1, backups.size)
            assertEquals("external edit", backups.single().readText())
            val journal = File(root, ".gptmax/edit-log.jsonl")
            assertTrue(journal.isFile)
            assertTrue(journal.readText().contains("Notes.kt.gptmax-backup-"))
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

    @Test
    fun overviewDetectsStackAndTestsWithoutReadingSecrets() = runBlocking {
        val root = Files.createTempDirectory("gptmax-overview").toFile()
        try {
            File(root, "src/main").mkdirs()
            File(root, "tests").mkdirs()
            File(root, "pyproject.toml").writeText("[project]\nname='fixture'\n")
            File(root, "src/main/App.kt").writeText("class App")
            File(root, "tests/test_app.py").writeText("def test_app(): pass")
            File(root, ".env").writeText("API_KEY=must-not-appear")
            val result = WorkspaceOverviewTool(root).execute(
                AgentToolCall("overview-1", "workspace.overview", emptyMap()),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, result.status)
            assertTrue(result.output.contains("Python"))
            assertTrue(result.output.contains("src/main/App.kt"))
            assertTrue(result.output.contains("tests/test_app.py"))
            assertTrue(!result.output.contains("must-not-appear"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gitWorkspaceReportsTrackedChanges() = runBlocking {
        val root = Files.createTempDirectory("gptmax-git").toFile()
        try {
            fun git(vararg args: String) {
                val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
                val output = p.inputStream.bufferedReader().readText()
                assertEquals(output, 0, p.waitFor())
            }
            git("init")
            git("config", "user.email", "agent-test@example.invalid")
            git("config", "user.name", "GpTMax test")
            File(root, "tracked.txt").writeText("before\n")
            git("add", "tracked.txt")
            git("commit", "-m", "fixture")
            File(root, "tracked.txt").writeText("after\n")
            val status = GitWorkspaceTool(root).execute(
                AgentToolCall("status-1", "git.workspace", mapOf("action" to "status")),
                AtomicBoolean(false),
            )
            val diff = GitWorkspaceTool(root).execute(
                AgentToolCall("diff-1", "git.workspace", mapOf("action" to "diff")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, status.status)
            assertEquals(ToolExecutionStatus.SUCCESS, diff.status)
            assertTrue(status.output.contains("tracked.txt"))
            assertTrue(diff.output.contains("tracked.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

}

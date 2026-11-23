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
    fun blocksCredentialDirectoriesAndEnvVariants() = runBlocking {
        val root = Files.createTempDirectory("gptmax-workspace").toFile()
        try {
            File(root, ".ssh").mkdirs()
            File(root, ".ssh/keys.json").writeText("{\"token\": \"PRIVATE_TOKEN_ABC\"}")
            File(root, ".aws").mkdirs()
            File(root, ".aws/config.json").writeText("AWS_SECRET_KEY_ABC")
            File(root, ".env.development").writeText("DEV_TOKEN_XYZ")
            File(root, "src").mkdirs()
            File(root, "src/App.kt").writeText("class App")

            val directSsh = runCatching {
                WorkspaceReadTool(root).execute(
                    AgentToolCall("r1", "file.read", mapOf("path" to ".ssh/keys.json")),
                    AtomicBoolean(false),
                )
            }
            assertTrue("direct read of .ssh content must fail", directSsh.isFailure)
            val directAws = runCatching {
                WorkspaceReadTool(root).execute(
                    AgentToolCall("r2", "file.read", mapOf("path" to ".aws/config.json")),
                    AtomicBoolean(false),
                )
            }
            assertTrue("direct read of .aws content must fail", directAws.isFailure)
            val directEnvVariant = runCatching {
                WorkspaceReadTool(root).execute(
                    AgentToolCall("r3", "file.read", mapOf("path" to ".env.development")),
                    AtomicBoolean(false),
                )
            }
            assertTrue(".env.* variants must be blocked", directEnvVariant.isFailure)

            val searchSsh = WorkspaceSearchTool(root).execute(
                AgentToolCall("s1", "file.search", mapOf("query" to "PRIVATE_TOKEN_ABC")),
                AtomicBoolean(false),
            )
            assertTrue("search must not leak .ssh content", !searchSsh.output.contains("PRIVATE_TOKEN_ABC"))
            val searchAws = WorkspaceSearchTool(root).execute(
                AgentToolCall("s2", "file.search", mapOf("query" to "AWS_SECRET_KEY_ABC")),
                AtomicBoolean(false),
            )
            assertTrue("search must not leak .aws content", !searchAws.output.contains("AWS_SECRET_KEY_ABC"))
            val searchEnv = WorkspaceSearchTool(root).execute(
                AgentToolCall("s3", "file.search", mapOf("query" to "DEV_TOKEN_XYZ")),
                AtomicBoolean(false),
            )
            assertTrue("search must not leak .env.* content", !searchEnv.output.contains("DEV_TOKEN_XYZ"))
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
    fun writeRejectsStaleFileAndRecoversAfterRead() = runBlocking {
        val root = Files.createTempDirectory("gptmax-edit").toFile()
        try {
            val journal = EditSafetyJournal()
            val reader = WorkspaceReadTool(root, journal)
            val writer = WorkspaceWriteTool(root, journal)

            val create = writer.execute(
                AgentToolCall("w1", "file.write", mapOf("path" to "Notes.kt", "content" to "version one")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, create.status)

            // Simulate a modification performed outside the agent session.
            File(root, "Notes.kt").writeText("external edit")

            val stale = runCatching {
                writer.execute(
                    AgentToolCall("w2", "file.write", mapOf("path" to "Notes.kt", "content" to "version two")),
                    AtomicBoolean(false),
                )
            }
            assertTrue("stale overwrite must be rejected", stale.isFailure)
            assertEquals("external edit", File(root, "Notes.kt").readText())

            reader.execute(
                AgentToolCall("r1", "file.read", mapOf("path" to "Notes.kt")),
                AtomicBoolean(false),
            )
            val retry = writer.execute(
                AgentToolCall("w3", "file.write", mapOf("path" to "Notes.kt", "content" to "version two")),
                AtomicBoolean(false),
            )
            assertEquals(ToolExecutionStatus.SUCCESS, retry.status)
            assertEquals("version two", File(root, "Notes.kt").readText())
            assertTrue(
                "previous version must be preserved as a backup",
                root.listFiles()!!.any { it.name.startsWith("Notes.kt.gptmax-backup-") },
            )
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

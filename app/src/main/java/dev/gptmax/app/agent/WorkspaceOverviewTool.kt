package dev.gptmax.app.agent

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Produces a bounded, read-only repository map without executing project code
 * or reading file contents. This is a safe first step for repository-wide tasks.
 */
class WorkspaceOverviewTool(private val root: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "workspace.overview",
        description = "Build a bounded read-only map of the selected repository: project type, important manifests, source/test directories, and shallow file tree. Use first for repository-wide tasks.",
        requiredArguments = emptySet(),
        allowedArguments = emptySet(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(
        call: AgentToolCall,
        cancellation: AtomicBoolean,
    ): AgentToolResult {
        val start = System.nanoTime()
        val workspace = root.canonicalFile
        require(workspace.isDirectory) { "Workspace root is not a directory." }

        val ignored = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "__pycache__",
            "node_modules", "build", "dist", "target", ".next",
            ".pytest_cache", ".mypy_cache", ".ruff_cache",
        )
        val importantFiles = setOf(
            "README.md", "pyproject.toml", "requirements.txt", "package.json",
            "tsconfig.json", "Cargo.toml", "go.mod", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pubspec.yaml", "pom.xml", "composer.json", "Gemfile",
            "Makefile", "Dockerfile",
        )
        val entries = mutableListOf<String>()
        val manifestNames = linkedSetOf<String>()
        val sourceRoots = linkedSetOf<String>()
        val testRoots = linkedSetOf<String>()
        var visited = 0
        var truncated = false

        workspace.walkTopDown().onEnter { directory ->
            if (cancellation.get()) return@onEnter false
            if (directory == workspace) true
            else {
                val relative = directory.relativeTo(workspace).path
                val depth = relative.split(File.separatorChar).size
                depth < 4 && directory.name !in ignored
            }
        }.forEach { file ->
            if (cancellation.get()) return@forEach
            if (visited >= MAX_ENTRIES) {
                truncated = true
                return@forEach
            }
            if (file == workspace) return@forEach
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(workspace.path + File.separator)) return@forEach
            visited++
            val relative = file.relativeTo(workspace).path.replace(File.separatorChar, '/')
            val depth = relative.count { it == '/' }
            if (depth <= 3) entries += (if (file.isDirectory) "$relative/" else relative)

            if (file.isFile && !isSensitiveFile(file)) {
                if (file.name in importantFiles) manifestNames += relative
                val segments = relative.split('/')
                if (segments.any {
                        it.equals("src", true) || it.equals("app", true) ||
                            it.equals("lib", true) || it.equals("main", true)
                    }) {
                    sourceRoots += segments.first()
                }
                if (segments.any {
                        it.equals("test", true) || it.equals("tests", true) ||
                            it.equals("__tests__", true)
                    }) {
                    testRoots += segments.first()
                }
            }
        }

        val stack = buildList {
            if (manifestNames.any { it.endsWith("pyproject.toml") || it.endsWith("requirements.txt") }) add("Python")
            if (manifestNames.any { it.endsWith("package.json") }) add("JavaScript/Node.js")
            if (manifestNames.any { it.endsWith("tsconfig.json") }) add("TypeScript")
            if (manifestNames.any { it.endsWith("Cargo.toml") }) add("Rust")
            if (manifestNames.any { it.endsWith("go.mod") }) add("Go")
            if (manifestNames.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") }) add("Gradle/JVM/Android")
            if (manifestNames.any { it.endsWith("pubspec.yaml") }) add("Dart/Flutter")
            if (manifestNames.any { it.endsWith("pom.xml") }) add("Maven/Java")
            if (manifestNames.any { it.endsWith("composer.json") }) add("PHP")
            if (manifestNames.any { it.endsWith("Gemfile") }) add("Ruby")
        }.ifEmpty { listOf("Unknown / inspect manifests") }

        val status = if (cancellation.get()) ToolExecutionStatus.CANCELLED else ToolExecutionStatus.SUCCESS
        val output = buildString {
            appendLine("Repository overview (read-only; no code executed)")
            appendLine("Detected stack: ${stack.joinToString(", ")}")
            appendLine("Important manifests/docs:")
            appendLine(manifestNames.sorted().take(60).joinToString("\n").ifBlank { "(none found)" })
            appendLine("Source roots: ${sourceRoots.sorted().joinToString(", ").ifBlank { "(not detected)" }}")
            appendLine("Test roots: ${testRoots.sorted().joinToString(", ").ifBlank { "(not detected)" }}")
            appendLine("Shallow tree:")
            appendLine(entries.sorted().take(MAX_TREE_LINES).joinToString("\n").ifBlank { "(empty workspace)" })
            if (truncated || entries.size > MAX_TREE_LINES) appendLine("Tree truncated; use workspace.list and file.search for focused exploration.")
            append("Scanned entries: $visited")
        }
        return AgentToolResult(
            spec.name,
            call.id,
            status,
            output.take(MAX_OUTPUT_CHARS),
            (System.nanoTime() - start) / 1_000_000L,
        )
    }

    companion object {
        private const val MAX_ENTRIES = 1_500
        private const val MAX_TREE_LINES = 250
        private const val MAX_OUTPUT_CHARS = 16_000
    }
}

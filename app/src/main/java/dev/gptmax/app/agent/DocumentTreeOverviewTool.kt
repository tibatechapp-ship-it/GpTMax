package dev.gptmax.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean

/** SAF-backed bounded project map. It reads names/metadata only and never executes project code. */
class DocumentTreeOverviewTool(context: Context, treeUri: Uri) : AgentTool {
    private val root = DocumentFile.fromTreeUri(context.applicationContext, treeUri)
        ?: throw IllegalArgumentException("Invalid workspace folder.")

    override val spec = AgentToolSpec(
        name = "workspace.overview",
        description = "Build a bounded read-only map of the selected project using file names only: project type, manifests, source/test roots, and shallow tree. Use first for repository-wide tasks.",
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val started = System.nanoTime()
        val ignored = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "__pycache__",
            "node_modules", "build", "dist", "target", ".next",
            ".pytest_cache", ".mypy_cache", ".ruff_cache",
        )
        val important = setOf(
            "README.md", "pyproject.toml", "requirements.txt", "package.json",
            "tsconfig.json", "Cargo.toml", "go.mod", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pubspec.yaml", "pom.xml", "composer.json", "Gemfile",
            "Makefile", "Dockerfile",
        )
        val tree = mutableListOf<String>()
        val manifests = linkedSetOf<String>()
        val sourceRoots = linkedSetOf<String>()
        val testRoots = linkedSetOf<String>()
        var visited = 0
        var truncated = false

        fun visit(directory: DocumentFile, prefix: String, depth: Int) {
            if (cancellation.get() || visited >= MAX_ENTRIES || depth >= MAX_DEPTH) {
                if (visited >= MAX_ENTRIES) truncated = true
                return
            }
            val children = runCatching { directory.listFiles().sortedBy { it.name.orEmpty() } }.getOrDefault(emptyList())
            for (item in children) {
                if (cancellation.get()) return
                if (visited >= MAX_ENTRIES) {
                    truncated = true
                    return
                }
                val name = item.name ?: continue
                if (item.isDirectory && name in ignored) continue
                visited++
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (relative.count { it == '/' } < MAX_TREE_DEPTH) {
                    tree += relative + if (item.isDirectory) "/" else ""
                }
                if (item.isFile) {
                    if (name in important && safeDocumentName(name)) manifests += relative
                    val segments = relative.split('/')
                    if (segments.any { it.equals("src", true) || it.equals("app", true) || it.equals("lib", true) || it.equals("main", true) }) {
                        sourceRoots += segments.first()
                    }
                    if (segments.any { it.equals("test", true) || it.equals("tests", true) || it.equals("__tests__", true) }) {
                        testRoots += segments.first()
                    }
                } else if (item.isDirectory) {
                    visit(item, relative, depth + 1)
                }
            }
        }
        visit(root, "", 0)

        val stack = buildList {
            if (manifests.any { it.endsWith("pyproject.toml") || it.endsWith("requirements.txt") }) add("Python")
            if (manifests.any { it.endsWith("package.json") }) add("JavaScript/Node.js")
            if (manifests.any { it.endsWith("tsconfig.json") }) add("TypeScript")
            if (manifests.any { it.endsWith("Cargo.toml") }) add("Rust")
            if (manifests.any { it.endsWith("go.mod") }) add("Go")
            if (manifests.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") }) add("Gradle/JVM/Android")
            if (manifests.any { it.endsWith("pubspec.yaml") }) add("Dart/Flutter")
            if (manifests.any { it.endsWith("pom.xml") }) add("Maven/Java")
            if (manifests.any { it.endsWith("composer.json") }) add("PHP")
            if (manifests.any { it.endsWith("Gemfile") }) add("Ruby")
        }.ifEmpty { listOf("Unknown / inspect manifests") }

        val status = if (cancellation.get()) ToolExecutionStatus.CANCELLED else ToolExecutionStatus.SUCCESS
        val output = buildString {
            appendLine("Selected project overview (SAF; read-only; filenames/metadata only; no code executed)")
            appendLine("Detected stack: ${stack.joinToString(", ")}")
            appendLine("Important manifests/docs:")
            appendLine(manifests.sorted().take(60).joinToString("\n").ifBlank { "(none found)" })
            appendLine("Source roots: ${sourceRoots.sorted().joinToString(", ").ifBlank { "(not detected)" }}")
            appendLine("Test roots: ${testRoots.sorted().joinToString(", ").ifBlank { "(not detected)" }}")
            appendLine("Shallow tree:")
            appendLine(tree.sorted().take(MAX_TREE_LINES).joinToString("\n").ifBlank { "(empty workspace)" })
            if (truncated || tree.size > MAX_TREE_LINES) appendLine("Tree truncated; use workspace.list and file.search for focused exploration.")
            append("Scanned entries: $visited")
        }.take(MAX_OUTPUT_CHARS)

        return AgentToolResult(
            spec.name, call.id, status, output,
            (System.nanoTime() - started) / 1_000_000L,
        )
    }

    companion object {
        private const val MAX_ENTRIES = 1_500
        private const val MAX_DEPTH = 8
        private const val MAX_TREE_DEPTH = 3
        private const val MAX_TREE_LINES = 250
        private const val MAX_OUTPUT_CHARS = 16_000
    }
}

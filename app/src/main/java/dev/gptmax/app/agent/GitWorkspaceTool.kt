package dev.gptmax.app.agent

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only Git status and diff summary. No repository state is changed. */
class GitWorkspaceTool(private val workspace: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "git.workspace",
        description = "Read Git branch/status and a bounded diff summary. action=status or action=diff. This tool never stages, commits, resets, or checks out.",
        requiredArguments = setOf("action"),
        allowedArguments = setOf("action"),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult {
        val started = System.nanoTime()
        val root = workspace.canonicalFile
        require(root.isDirectory) { "Workspace root is not a directory." }
        val action = call.arguments.getValue("action")
        require(action in setOf("status", "diff")) { "Action must be status or diff." }
        val status = git(root, listOf("status", "--short", "--branch"), cancellation)
        if (status.cancelled) return result(call, ToolExecutionStatus.CANCELLED, "Cancelled.", started)
        if (status.exitCode != 0) return result(call, ToolExecutionStatus.FAILURE, status.output, started)
        if (action == "status") return result(call, ToolExecutionStatus.SUCCESS,
            status.output.ifBlank { "Git status is clean." }, started)

        val args = listOf("diff", "--no-ext-diff", "--no-color", "--stat", "--")
        val diff = git(root, args, cancellation)
        if (diff.cancelled) return result(call, ToolExecutionStatus.CANCELLED, "Cancelled.", started)
        if (diff.exitCode != 0) return result(call, ToolExecutionStatus.FAILURE, diff.output, started)
        val staged = git(root, listOf("diff", "--cached", "--no-ext-diff", "--no-color", "--stat", "--"), cancellation)
        if (staged.cancelled) return result(call, ToolExecutionStatus.CANCELLED, "Cancelled.", started)
        if (staged.exitCode != 0) return result(call, ToolExecutionStatus.FAILURE, staged.output, started)
        return result(call, ToolExecutionStatus.SUCCESS,
            "Git status:\n${status.output}\n[unstaged diff stat]\n${diff.output}\n[staged diff stat]\n${staged.output}", started)
    }

    private data class ProcessResult(val exitCode: Int, val output: String, val cancelled: Boolean = false)

    private fun git(root: File, args: List<String>, cancellation: AtomicBoolean): ProcessResult {
        val process = try {
            ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).apply {
                environment().clear()
                environment()["PATH"] = "/system/bin:/system/xbin:/usr/bin:/bin"
                environment()["HOME"] = root.path
                environment()["GIT_OPTIONAL_LOCKS"] = "0"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }.start()
        } catch (error: Exception) {
            return ProcessResult(-1, "Git executable unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
        val capture = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val buffer = CharArray(2048)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(capture) {
                        val remaining = MAX_OUTPUT_CHARS - capture.length
                        if (remaining > 0) capture.append(buffer, 0, minOf(n, remaining))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (process.isAlive) {
            if (cancellation.get()) {
                process.destroy()
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                reader.join(1000)
                return ProcessResult(-1, "", true)
            }
            if (System.nanoTime() >= deadline) {
                process.destroyForcibly()
                reader.join(1000)
                return ProcessResult(-1, "Git command timed out.")
            }
            process.waitFor(100, TimeUnit.MILLISECONDS)
        }
        reader.join(1500)
        return ProcessResult(process.exitValue(), synchronized(capture) { capture.toString() })
    }

    private fun result(call: AgentToolCall, status: ToolExecutionStatus, output: String, started: Long) =
        AgentToolResult(spec.name, call.id, status, output.take(MAX_OUTPUT_CHARS),
            (System.nanoTime() - started) / 1_000_000L)

    companion object { private const val MAX_OUTPUT_CHARS = 12_000 }
}

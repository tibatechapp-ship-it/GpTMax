package dev.gptmax.app.agent

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Executes a small allowlist of developer tools without a shell.
 * This is a managed process API, not an unrestricted terminal.
 */
class ManagedProcessTool(private val workspace: File) : AgentTool {
    override val spec = AgentToolSpec(
        name = "process.run",
        description = "Run an installed allowlisted developer executable. Arguments are newline-separated, never shell-parsed.",
        requiredArguments = setOf("executable"),
        allowedArguments = setOf("executable", "arguments", "cwd", "timeout_ms"),
        risk = ToolRisk.PROCESS_EXECUTION,
    )

    override suspend fun execute(
        call: AgentToolCall,
        cancellation: AtomicBoolean,
    ): AgentToolResult {
        val started = System.nanoTime()
        val executable = call.arguments.getValue("executable")
        require(executable in allowedExecutables) { "Executable is not allowlisted." }

        val argsText = call.arguments["arguments"].orEmpty()
        require(argsText.length <= MAX_ARGUMENT_TEXT) { "Arguments exceed the configured limit." }
        val args = if (argsText.isEmpty()) emptyList() else argsText.split('\n')
        require(args.size <= MAX_ARGUMENTS && args.none { it.contains('\u0000') }) {
            "Invalid or excessive process arguments."
        }

        val cwd = call.arguments["cwd"].orEmpty().let {
            if (it.isBlank()) workspace.canonicalFile else safeWorkspaceFile(workspace, it)
        }
        require(cwd.isDirectory) { "Working directory does not exist." }

        val timeoutMs = call.arguments["timeout_ms"]?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        require(timeoutMs in 1_000L..MAX_TIMEOUT_MS) { "Timeout must be between 1 and 120 seconds." }

        val process = ProcessBuilder(listOf(executable) + args)
            .directory(cwd)
            .redirectErrorStream(false)
        val env = process.environment()
        env.clear()
        env["PATH"] = "/system/bin:/system/xbin:/usr/bin:/bin"
        env["HOME"] = workspace.canonicalPath
        env["LANG"] = "C.UTF-8"

        val startedProcess = try {
            process.start()
        } catch (error: Exception) {
            return result(call, ToolExecutionStatus.FAILURE,
                "Could not start '$executable': ${error.message ?: error.javaClass.simpleName}", started)
        }

        val stdout = BoundedStreamCapture(startedProcess.inputStream)
        val stderr = BoundedStreamCapture(startedProcess.errorStream)
        stdout.start()
        stderr.start()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var timedOut = false
        while (startedProcess.isAlive) {
            if (cancellation.get()) {
                startedProcess.destroy()
                if (!startedProcess.waitFor(500, TimeUnit.MILLISECONDS)) startedProcess.destroyForcibly()
                stdout.joinQuietly()
                stderr.joinQuietly()
                return result(call, ToolExecutionStatus.CANCELLED, "Process cancelled.", started)
            }
            if (System.nanoTime() >= deadline) {
                timedOut = true
                startedProcess.destroy()
                if (!startedProcess.waitFor(500, TimeUnit.MILLISECONDS)) startedProcess.destroyForcibly()
                break
            }
            startedProcess.waitFor(100, TimeUnit.MILLISECONDS)
        }

        stdout.joinQuietly()
        stderr.joinQuietly()
        val exitCode = runCatching { startedProcess.exitValue() }.getOrDefault(-1)
        val status = when {
            timedOut -> ToolExecutionStatus.FAILURE
            exitCode == 0 -> ToolExecutionStatus.SUCCESS
            else -> ToolExecutionStatus.FAILURE
        }
        val output = buildString {
            append("exit_code=").append(exitCode)
            if (timedOut) append(" timed_out=true")
            appendLine()
            appendLine("[stdout]")
            append(stdout.text())
            appendLine()
            appendLine("[stderr]")
            append(stderr.text())
        }
        return result(call, status, output, started)
    }

    private fun result(
        call: AgentToolCall,
        status: ToolExecutionStatus,
        output: String,
        started: Long,
    ) = AgentToolResult(
        spec.name,
        call.id,
        status,
        output.take(MAX_OUTPUT_CHARS),
        (System.nanoTime() - started) / 1_000_000L,
    )

    private class BoundedStreamCapture(private val input: InputStream) {
        private val output = StringBuilder()
        private var truncated = false
        private val reader = thread(start = false, isDaemon = true, name = "gptmax-process-output") {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val remaining = MAX_OUTPUT_CHARS - output.length
                        if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        if (count > remaining) truncated = true
                    }
                }
            }
        }

        fun start() = reader.start()
        fun joinQuietly() {
            runCatching { reader.join(2_000) }
        }
        fun text(): String = synchronized(output) {
            output.toString() + if (truncated) "\n[output truncated]" else ""
        }
    }

    companion object {
        private val allowedExecutables = setOf(
            "git", "python", "python3", "node", "npm", "gradle", "pytest",
            "ruff", "cmake", "clang", "java", "javac", "make", "cargo", "go",
        )
        private const val MAX_ARGUMENT_TEXT = 8_000
        private const val MAX_ARGUMENTS = 64
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_OUTPUT_CHARS = 24_000
    }
}

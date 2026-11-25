package com.tibatech.gptmax.core.guardrails

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 4 (external-review roadmap): the execution half of the run_command
 * sandbox, extracted from ToolRegistry so its mechanics are PROVABLE on
 * their own.
 *
 * Before this file existed the spawn/kill logic was a private method of
 * ToolRegistry, reachable from tests only through the whole registry — so
 * the "sandbox passes its device gate" precondition that Phase 1 set for
 * re-enabling run_command had nothing concrete to gate on. Now:
 *
 * - JVM unit tests ([Phase4CommandSandboxTests]) execute REAL child
 *   processes (pwd, printenv, sleep, yes) and prove the cwd pin, the bare
 *   environment, the hard output cap, the timeout kill, and the scratch
 *   cleanup on a plain Linux runner;
 * - the CI emulator job runs [CommandSandboxDeviceTest], which proves the
 *   same guarantees against toybox on a real Android runtime. That test IS
 *   the device gate Phase 1 asked for; run_command ships enabled (with the
 *   kill switch retained) only because this gate runs on every push.
 *
 * Guarantees, per execution:
 * 1. Fresh, EMPTY scratch working directory, unique per run, deleted in a
 *    finally — state never leaks between commands (per-task working dir).
 * 2. Bare environment: the child env is cleared, then only PATH and HOME
 *    (pinned to the scratch dir) are provided.
 * 3. No shell: argv is exec'ed directly, so metacharacters are inert and
 *    CommandIsolation's path-separator denial stays airtight.
 * 4. Output is drained on a daemon thread with a hard byte cap concurrent
 *    with waitFor(timeout); a timed-out process is destroyed forcibly.
 * 5. Truncation is REPORTED (reachedCap), never silent.
 *
 * Known boundary (documented, accepted): destroyForcibly kills the direct
 * child only. The allowlist contains no binary that spawns grandchildren,
 * and every spawn requires explicit user approval, so an orphan tree is
 * out of the threat model for this tool.
 *
 * Pure Kotlin + java.lang.ProcessBuilder: runs on the JVM and on Android.
 */
object CommandSandbox {

    /**
     * Bare environment every sandboxed process runs with. PATH points at
     * the toybox locations on Android; the JVM tests inject their own PATH
     * instead of asserting on this value. HOME is pinned per-run to the
     * scratch directory.
     */
    val BASE_ENV: Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin",
    )

    data class SandboxResult(
        /** Capped, UTF-8-decoded, combined (stdout+stderr) output. */
        val output: String,
        /** Null when the process was killed on timeout (never trust it). */
        val exitCode: Int?,
        val timedOut: Boolean,
        /** The byte cap was reached — output may have been cut. */
        val truncated: Boolean,
        /** Non-null when the drain thread died (output is unusable). */
        val drainError: String?,
    )

    /**
     * Execute [argv] directly (no shell) inside a fresh scratch directory
     * under [sandboxRoot]. The scratch directory is removed when this call
     * returns, whatever the outcome.
     *
     * @param timeoutMs hard wall-clock budget for the child process
     * @param maxOutputBytes hard cap for the drained output (bytes)
     * @param maxOutputChars second cap applied after UTF-8 decoding
     * @param env environment map exposed to the child (cleared first;
     *        HOME is always overwritten with the scratch path)
     */
    fun execute(
        argv: List<String>,
        sandboxRoot: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        env: Map<String, String> = BASE_ENV,
    ): SandboxResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(maxOutputBytes > 0 && maxOutputChars > 0) { "output caps must be positive" }

        // Unique per run: a fixed name would let one command's leftovers
        // (or a leftover from a crashed run) feed the next command.
        val scratch = File(
            sandboxRoot,
            "run-${System.nanoTime()}-${(0..Int.MAX_VALUE).random()}",
        )
        try {
            scratch.mkdirs()

            val builder = ProcessBuilder(argv)
                .directory(scratch)
                .redirectErrorStream(true)
            // Strip the inherited environment entirely, then expose the bare
            // minimum the child needs to answer.
            builder.environment().clear()
            for ((k, v) in env) builder.environment()[k] = v
            builder.environment()["HOME"] = scratch.absolutePath
            val process = builder.start()

            val drained = AtomicReference<CommandIsolation.BoundedRead?>(null)
            val drainError = AtomicReference<Exception?>(null)
            val drainer = Thread {
                try {
                    drained.set(
                        CommandIsolation.readBoundedCounted(process.inputStream, maxOutputBytes),
                    )
                } catch (e: Exception) {
                    drainError.set(e)
                }
            }
            drainer.isDaemon = true
            drainer.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            // Give the drain thread a moment to observe the EOF that the
            // destroy (or normal exit) produces; never block indefinitely.
            drainer.join(2_000)

            val read = drained.get()
            return SandboxResult(
                output = (read?.text ?: "").take(maxOutputChars),
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished,
                truncated = read?.reachedCap == true,
                drainError = drainError.get()?.let { it.message ?: it.toString() },
            )
        } finally {
            // Cleanup is unconditional: timeout, drain error, spawn failure
            // — no scratch directory outlives the call.
            scratch.deleteRecursively()
        }
    }

    const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    const val DEFAULT_MAX_OUTPUT_BYTES: Int = 65_536
    const val DEFAULT_MAX_OUTPUT_CHARS: Int = 8_000
}

package com.tibatech.gptmax.core.guardrails

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Sandbox validator for the `run_command` tool.
 *
 * Commands run only after explicit user approval (the tool is marked
 * isWrite, so the ApprovalGate always asks first), and only when they pass
 * this allowlist scan. Execution itself is isolated: bare-environment
 * process, pinned scratch working directory, hard timeout and output cap.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 *
 * Phase 1 (data-safety lockdown, external review):
 * - Path-argument isolation: the sandbox pins cwd to an empty scratch
 *   directory, but nothing before now stopped `cat ../../data/x` or
 *   `grep pattern /data/local/tmp` from REACHING outside it. Any argument
 *   containing a path separator ('/' or '\\') is now denied outright, so
 *   every file the command touches must be a bare name inside the sandbox.
 * - Bounded output: [readBounded] is the only sanctioned way to consume a
 *   process stream — never readBytes() the raw stream, which blocks forever
 *   (or exhausts memory) when a process never closes stdout, and applies
 *   the timeout only after the unbounded read already finished.
 */
object CommandIsolation {

    /**
     * Read-only inspection binaries available via toybox on Android.
     * Anything that can spawn processes (awk `system()`, find `-exec`),
     * mutate state (mkdir, sed -i) or touch the network is deliberately
     * absent.
     */
    val ALLOWED_BINARIES = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "sort", "uniq", "cut", "tr",
        "diff", "stat", "du", "df", "echo", "date", "pwd", "which", "whoami",
        "md5sum", "sha256sum", "base64", "file",
    )

    sealed class Verdict {
        data class Allowed(val argv: List<String>) : Verdict()
        data class Denied(val reason: String) : Verdict()
    }

    private const val MAX_TOKENS = 64
    private const val MAX_TOKEN_LENGTH = 4_096
    private val FORBIDDEN_CHARS = charArrayOf('\n', '\r', '\u0000', '`')
    private val WHITESPACE = Regex("\\s+")

    fun validate(raw: String): Verdict {
        // Injection guard runs on the RAW string first: splitting on
        // whitespace would otherwise launder newlines/CRs into argument
        // separators instead of denying them (caught by CommandIsolationTest).
        if (raw.any { it in FORBIDDEN_CHARS }) {
            return Verdict.Denied("forbidden character in command")
        }
        val argv = raw.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (argv.isEmpty()) return Verdict.Denied("empty command")
        if (argv.size > MAX_TOKENS) return Verdict.Denied("too many arguments (${argv.size})")
        if (argv[0].contains('/')) {
            return Verdict.Denied("binary must be a bare name, got: ${argv[0]}")
        }
        if (argv[0].contains('\\')) {
            return Verdict.Denied("binary must be a bare name, got: ${argv[0]}")
        }
        if (argv[0].lowercase() !in ALLOWED_BINARIES) {
            return Verdict.Denied("binary not allowlisted: ${argv[0]}")
        }
        for (token in argv) {
            if (token.length > MAX_TOKEN_LENGTH) return Verdict.Denied("argument too long")
            if (FORBIDDEN_CHARS.any { it in token }) {
                return Verdict.Denied("forbidden character in argument")
            }
        }
        // Phase 1 path isolation: argv is executed directly (no shell) with
        // cwd pinned to the scratch dir, so a path separator is the only way
        // to reach anything outside it. Deny it in EVERY argument — that
        // kills absolute paths, '../' traversal, --flag=/x values and
        // windows drive letters in one rule.
        for (token in argv.drop(1)) {
            if (token.contains('/')) {
                return Verdict.Denied("path arguments must stay inside the sandbox (no '/')")
            }
            if (token.contains('\\')) {
                return Verdict.Denied("path arguments must stay inside the sandbox (no '\\')")
            }
        }
        return Verdict.Allowed(argv)
    }

    /**
     * Phase 1: read a process output stream with a HARD byte cap. Stops
     * reading at [maxBytes] — a process producing endless output can no
     * longer block the caller forever or grow memory without bound. Pair
     * with a drain thread + waitFor(timeout) so the timeout applies WHILE
     * the stream is being drained, not after the read finished.
     */
    fun readBounded(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val buf = ByteArray(8_192)
        val out = ByteArrayOutputStream(minOf(maxBytes, 1 shl 16))
        var total = 0
        while (total < maxBytes) {
            val want = minOf(buf.size, maxBytes - total)
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toString("UTF-8")
    }
}

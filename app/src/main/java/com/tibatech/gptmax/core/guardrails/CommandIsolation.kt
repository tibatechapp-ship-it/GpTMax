package com.tibatech.gptmax.core.guardrails

/**
 * Fix #6 (code review): sandbox validator for the `run_command` tool.
 *
 * Commands run only after explicit user approval (the tool is marked
 * isWrite, so the ApprovalGate always asks first), and only when they pass
 * this allowlist scan. Execution itself is isolated: bare-environment
 * process, pinned scratch working directory, hard timeout and output cap.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
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
        if (argv[0].lowercase() !in ALLOWED_BINARIES) {
            return Verdict.Denied("binary not allowlisted: ${argv[0]}")
        }
        for (token in argv) {
            if (token.length > MAX_TOKEN_LENGTH) return Verdict.Denied("argument too long")
            if (FORBIDDEN_CHARS.any { it in token }) {
                return Verdict.Denied("forbidden character in argument")
            }
        }
        return Verdict.Allowed(argv)
    }
}

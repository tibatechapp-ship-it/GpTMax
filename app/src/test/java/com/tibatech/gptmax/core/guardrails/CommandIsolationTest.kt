package com.tibatech.gptmax.core.guardrails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix #6 regression tests: the sandbox validator that gates `run_command`.
 * Only allowlisted read-only binaries may pass, and shell-injection style
 * input is always denied before a process is ever spawned.
 */
class CommandIsolationTest {

    @Test
    fun `allowlisted read-only commands pass`() {
        // Phase 1 path isolation: arguments must be bare names inside the
        // sandbox — 'src/App.kt' (any '/') is denied by the later test, so
        // the positive case exercises the same grep with a bare filename.
        val verdict = CommandIsolation.validate("grep -n TODO App.kt")
        assertTrue(verdict is CommandIsolation.Verdict.Allowed)
        assertEquals(
            listOf("grep", "-n", "TODO", "App.kt"),
            (verdict as CommandIsolation.Verdict.Allowed).argv,
        )
    }

    @Test
    fun `mutating or unknown binaries are denied`() {
        assertTrue(CommandIsolation.validate("rm -rf /") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("curl http://example.com") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("sh -c id") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("awk '{system(\"id\")}' f") is CommandIsolation.Verdict.Denied)
    }

    @Test
    fun `absolute paths as the binary are denied`() {
        assertTrue(CommandIsolation.validate("/system/bin/ls") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("./ls -la") is CommandIsolation.Verdict.Denied)
    }

    @Test
    fun `injection characters are denied`() {
        assertTrue(CommandIsolation.validate("echo a\nrm -rf x") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("grep `id` file.txt") is CommandIsolation.Verdict.Denied)
        // Phase 4 defense-in-depth: shell substitution syntax is inert
        // without a shell, but it is denied anyway so a future execution
        // change cannot resurrect it silently.
        assertTrue(CommandIsolation.validate("grep \$(echo hi) file") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("grep <(cat f) file") is CommandIsolation.Verdict.Denied)
    }

    @Test
    fun `empty commands are denied`() {
        assertTrue(CommandIsolation.validate("   ") is CommandIsolation.Verdict.Denied)
        assertTrue(CommandIsolation.validate("") is CommandIsolation.Verdict.Denied)
    }
}

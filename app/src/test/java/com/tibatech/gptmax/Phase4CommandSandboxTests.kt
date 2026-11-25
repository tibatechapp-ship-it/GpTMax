package com.tibatech.gptmax

import com.tibatech.gptmax.core.guardrails.CommandIsolation
import com.tibatech.gptmax.core.guardrails.CommandSandbox
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 4 (command sandbox enablement, external-review roadmap):
 * acceptance criteria for the extracted [CommandSandbox], executed against
 * REAL child processes on the JVM (the CI runner is Linux):
 *
 * - the working directory is pinned to a fresh per-run scratch dir;
 * - the child environment is BARE (only PATH + HOME);
 * - unbounded output is capped (bytes) and the cap is reported;
 * - a hung or endless process is killed by the timeout and its output
 *   survives as partial output;
 * - the scratch directory never outlives the call;
 * - the counted bounded read reports the cap honestly.
 *
 * These tests are the JVM half of the device gate; CommandSandboxDeviceTest
 * proves the same guarantees against toybox on Android.
 */
class Phase4CommandSandboxTests {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRoot(): File = tmp.newFolder("sandbox-root")

    // ------------------------------------------------------ cwd pinning

    @Test
    fun `working directory is pinned to the per-run scratch dir`() {
        val root = newRoot()
        val r = CommandSandbox.execute(listOf("pwd"), root)
        assertNull("pwd must not hang", r.drainError)
        assertEquals(0, r.exitCode)
        val out = r.output.trim()
        // getcwd() prints the physical path; canonicalize the root too so
        // a symlinked TMPDIR (macOS /tmp, CI bind-mounts) cannot fool us.
        val expected = root.canonicalPath
        assertTrue("pwd output '$out' must live under $expected", out.startsWith(expected))
        assertTrue("scratch dir must be per-run ('run-…'): $out", out.contains(File.separatorChar + "run-"))
    }

    // ------------------------------------------------------ bare environment

    @Test
    fun `child environment is bare — only PATH and HOME`() {
        val root = newRoot()
        val r = CommandSandbox.execute(
            listOf("printenv"),
            root,
            env = mapOf("PATH" to "/usr/bin:/bin"),
        )
        assertNull(r.drainError)
        assertEquals(0, r.exitCode)
        val lines = r.output.lines().filter { it.isNotBlank() }.sorted()
        assertEquals("exactly PATH and HOME must be exported", 2, lines.size)
        // Sorted lines put HOME before PATH — assert by prefix, not index.
        assertTrue("PATH must be the injected value: $lines", lines.any { it == "PATH=/usr/bin:/bin" })
        assertTrue("HOME must be pinned to the scratch dir: $lines", lines.any { it.startsWith("HOME=${root.absolutePath}") })
    }

    // ------------------------------------------------------ exit codes

    @Test
    fun `exit codes propagate`() {
        val root = newRoot()
        assertEquals(0, CommandSandbox.execute(listOf("true"), root).exitCode)
        assertEquals(1, CommandSandbox.execute(listOf("false"), root).exitCode)
    }

    // ------------------------------------------------------ output cap

    @Test
    fun `endless output is capped, reported truncated, and the process is killed`() {
        val root = newRoot()
        // `yes` prints forever and never exits: exercises the cap AND the
        // timeout-kill path in one run.
        val r = CommandSandbox.execute(
            listOf("yes", "abcdefghij"),
            root,
            timeoutMs = 500,
            maxOutputBytes = 2_048,
        )
        assertTrue("endless producer must hit the timeout", r.timedOut)
        assertTrue("endless producer must be reported truncated", r.truncated)
        assertNull("no exit code is ever taken from a killed process", r.exitCode)
        assertTrue("partial output must survive the kill", r.output.isNotBlank())
        assertTrue("output must respect the byte cap", r.output.length <= 2_048)
    }

    @Test
    fun `hung process is killed and reports no output`() {
        val root = newRoot()
        val r = CommandSandbox.execute(listOf("sleep", "5"), root, timeoutMs = 300)
        assertTrue("sleep 5 must be killed at 300ms", r.timedOut)
        assertNull(r.exitCode)
        assertEquals("", r.output)
        assertFalse(r.truncated)
    }

    @Test
    fun `char cap applies after the byte cap`() {
        val root = newRoot()
        val longArg = "z".repeat(20_000)
        val r = CommandSandbox.execute(
            listOf("echo", longArg),
            root,
            maxOutputBytes = 65_536,
            maxOutputChars = 200,
        )
        assertEquals("20k bytes fit under the byte cap, so not truncated", false, r.truncated)
        assertEquals("char cap must still trim", 200, r.output.length)
    }

    // ------------------------------------------------------ scratch cleanup

    @Test
    fun `scratch directories never outlive the call`() {
        val root = newRoot()
        CommandSandbox.execute(listOf("true"), root)
        CommandSandbox.execute(listOf("pwd"), root)
        CommandSandbox.execute(listOf("yes", "x"), root, timeoutMs = 200, maxOutputBytes = 64)
        val leftovers = root.listFiles()?.filter { it.name.startsWith("run-") } ?: emptyList()
        assertTrue("leftover scratch dirs: $leftovers", leftovers.isEmpty())
    }

    // ------------------------------------------------------ counted bounded read

    @Test
    fun `readBoundedCounted reports the cap honestly`() {
        val exact = CommandIsolation.readBoundedCounted(ByteArrayInputStream("x".repeat(64).toByteArray()), 64)
        assertEquals(64, exact.text.length)
        assertTrue("cap reached must be reported", exact.reachedCap)

        val short = CommandIsolation.readBoundedCounted(ByteArrayInputStream("hi".toByteArray()), 64)
        assertEquals("hi", short.text)
        assertFalse("short stream must not claim truncation", short.reachedCap)

        val over = CommandIsolation.readBoundedCounted(ByteArrayInputStream("y".repeat(1_000).toByteArray()), 64)
        assertTrue(over.reachedCap)
        assertTrue("drain must stop AT the cap", over.text.length == 64)
    }

    // ------------------------------------------------------ argument hygiene

    @Test
    fun `empty argv and non-positive caps are rejected`() {
        val root = newRoot()
        try {
            CommandSandbox.execute(emptyList(), root)
            org.junit.Assert.fail("empty argv must throw")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
        try {
            CommandSandbox.execute(listOf("true"), root, maxOutputBytes = 0)
            org.junit.Assert.fail("zero cap must throw")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }
}

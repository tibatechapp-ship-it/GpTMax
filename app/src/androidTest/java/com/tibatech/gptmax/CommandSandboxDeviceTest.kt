package com.tibatech.gptmax

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tibatech.gptmax.core.guardrails.CommandIsolation
import com.tibatech.gptmax.core.guardrails.CommandSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE device gate for run_command (phase 4). Phase 1 disabled command
 * execution until "the sandbox passes its device gate"; this class is that
 * gate: it executes REAL child processes (toybox on the emulator / real
 * device) through [CommandSandbox] and asserts every guarantee the
 * enablement rests on:
 *
 * 1. CommandIsolation denials behave identically on-device (path escape,
 *    absolute binary path, injection characters);
 * 2. the working directory is pinned to a fresh per-run scratch dir;
 * 3. the child environment is bare (only PATH + HOME);
 * 4. output is capped and reported truncated;
 * 5. an endless process is killed by the timeout;
 * 6. no scratch directory outlives the call.
 *
 * If any of these fails in the CI emulator job, the push stays red and the
 * commandsEnabled default must not ship.
 */
@RunWith(AndroidJUnit4::class)
class CommandSandboxDeviceTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "cmd-sandbox-device-test")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------- policy behaves identically

    @Test
    fun pathEscapeArgumentsAreDeniedOnDevice() {
        for (bad in listOf(
            "cat /data/local/tmp/secret.txt",
            "cat ../../data/App.kt",
            "grep -r pattern --exclude=/etc/passwd .",
        )) {
            val verdict = CommandIsolation.validate(bad)
            assertTrue("$bad must be denied on device", verdict is CommandIsolation.Verdict.Denied)
        }
    }

    @Test
    fun injectionCharactersAreDeniedOnDevice() {
        assertTrue(
            CommandIsolation.validate("grep \$(echo hi) file") is CommandIsolation.Verdict.Denied,
        )
        assertTrue(
            CommandIsolation.validate("grep `whoami` file") is CommandIsolation.Verdict.Denied,
        )
        assertTrue(
            CommandIsolation.validate("ls\nrm") is CommandIsolation.Verdict.Denied,
        )
    }

    @Test
    fun allowlistedBareNameIsAllowedOnDevice() {
        val verdict = CommandIsolation.validate("grep -rn pattern .")
        assertTrue("bare-name grep must stay allowed on device", verdict is CommandIsolation.Verdict.Allowed)
    }

    // ------------------------------------------- execution mechanics

    @Test
    fun workingDirectoryIsPinnedOnDevice() {
        val r = CommandSandbox.execute(listOf("pwd"), root)
        assertNull("pwd must not fail on device", r.drainError)
        assertEquals(0, r.exitCode)
        val out = r.output.trim()
        assertTrue("pwd '$out' must live under ${root.absolutePath}", out.startsWith(root.absolutePath))
        assertTrue("per-run scratch naming missing: $out", out.contains("/run-"))
    }

    @Test
    fun childEnvironmentIsBareOnDevice() {
        val r = CommandSandbox.execute(listOf("printenv"), root)
        assertNull(r.drainError)
        assertEquals(0, r.exitCode)
        val lines = r.output.lines().filter { it.isNotBlank() }.sorted()
        assertEquals("exactly PATH and HOME must be exported on device", 2, lines.size)
        // Sorted lines put HOME before PATH — assert by prefix, not index.
        assertTrue("PATH line malformed: $lines", lines.any { it.startsWith("PATH=") })
        assertTrue("HOME must be the scratch dir: $lines", lines.any { it.startsWith("HOME=${root.absolutePath}") })
    }

    @Test
    fun simpleCommandSucceedsOnDevice() {
        val r = CommandSandbox.execute(listOf("ls"), root)
        assertNull(r.drainError)
        assertEquals(0, r.exitCode)
        assertFalse(r.timedOut)
    }

    @Test
    fun endlessOutputIsCappedAndKilledOnDevice() {
        val r = CommandSandbox.execute(
            listOf("yes", "abcdefghij"),
            root,
            timeoutMs = 500,
            maxOutputBytes = 2_048,
        )
        assertTrue("endless producer must hit the timeout on device", r.timedOut)
        assertTrue("endless producer must be reported truncated on device", r.truncated)
        assertNull(r.exitCode)
        assertTrue("partial output must survive the kill", r.output.isNotBlank())
    }

    @Test
    fun hungProcessIsKilledOnDevice() {
        val r = CommandSandbox.execute(listOf("sleep", "5"), root, timeoutMs = 300)
        assertTrue(r.timedOut)
        assertNull(r.exitCode)
    }

    @Test
    fun scratchDirectoriesNeverOutliveTheCallOnDevice() {
        CommandSandbox.execute(listOf("pwd"), root)
        CommandSandbox.execute(listOf("yes", "x"), root, timeoutMs = 200, maxOutputBytes = 64)
        val leftovers = root.listFiles()?.filter { it.name.startsWith("run-") } ?: emptyList()
        assertTrue("leftover scratch dirs on device: $leftovers", leftovers.isEmpty())
    }
}

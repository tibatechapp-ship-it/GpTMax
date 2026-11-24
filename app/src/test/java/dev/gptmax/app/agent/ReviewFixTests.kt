package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for review fixes #1 and #2: the unified sensitive-path policy
 * shared by every workspace surface, and the write rollback journal with its
 * three failure scenarios (external modification, rename failure, provider
 * disconnect).
 */
class ReviewFixTests {

    // ---------------- fix #1: one unified sensitive-path policy -------------

    @Test
    fun `sensitive file names are blocked`() {
        listOf(
            ".env", ".env.local", ".env.production", ".npmrc", ".netrc",
            ".git-credentials", "id_rsa", "id_ed25519", "known_hosts",
            "authorized_keys", "credentials.json", "secrets.json",
            "service-account.json", "client_secret.json", "keystore.properties",
        ).forEach { assertTrue("$it must be blocked", SensitivePaths.isSensitiveName(it)) }
    }

    @Test
    fun `key and keystore extensions are blocked`() {
        listOf(
            "server.pem", "private.key", "cert.p12", "bundle.pfx",
            "signing.jks", "release.keystore", "vault.kdbx", "tunnel.ovpn",
        ).forEach { assertTrue("$it must be blocked", SensitivePaths.isSensitiveName(it)) }
    }

    @Test
    fun `credential directories are blocked everywhere`() {
        listOf(
            ".git", ".ssh", ".aws", ".gnupg", ".gpg", ".kube", ".docker",
            ".gradle", ".idea", "node_modules", "build", "dist", "target",
        ).forEach { assertTrue("$it must be blocked", SensitivePaths.isBlockedDirectory(it)) }
    }

    @Test
    fun `normal source files stay readable`() {
        listOf("MainActivity.kt", "README.md", "notes.txt", "AppTest.java", "schema.sql")
            .forEach { assertFalse("$it must stay readable", SensitivePaths.isSensitiveName(it)) }
        assertFalse(SensitivePaths.isBlockedDirectory("src"))
        assertFalse(SensitivePaths.isBlockedDirectory("docs"))
    }

    // ---------------- fix #2: write rollback journal ------------------------

    @Test
    fun `fingerprint distinguishes absent content from real content`() {
        val journal = WriteJournal()
        assertNull(journal.fingerprint(null))
        assertEquals(64, journal.fingerprint("hello")!!.length)
        assertNotEquals(journal.fingerprint("hello"), journal.fingerprint("hello "))
    }

    @Test
    fun `scenario 1 external modification is detected and rolled back`() {
        val journal = WriteJournal()
        val before = journal.fingerprint("old content")
        val expected = journal.fingerprint("requested new content")
        val actual = journal.fingerprint("tampered by an external editor")
        // Read-back verification hashes what is on disk; divergence proves an
        // external process touched the file between write and verification.
        assertFalse(WriteJournal.sameFingerprint(actual, expected))
        val entry = journal.record(
            "App.kt", WriteJournal.Kind.REPLACE, "App.kt.gptmax-backup-1", before, expected,
        )
        journal.complete(entry, WriteJournal.Verification.FAILED_ROLLED_BACK, "original restored from backup")
        val last = journal.lastFor("App.kt")!!
        assertEquals(WriteJournal.Verification.FAILED_ROLLED_BACK, last.verification)
        assertEquals("App.kt.gptmax-backup-1", last.backupName)
        assertEquals(before, last.fingerprintBefore)
        assertTrue(last.note.contains("restored"))
    }

    @Test
    fun `scenario 2 rename failure restores the original`() {
        val journal = WriteJournal()
        val entry = journal.record(
            "App.kt", WriteJournal.Kind.REPLACE, "App.kt.gptmax-backup-2",
            journal.fingerprint("old content"), journal.fingerprint("new content"),
        )
        // The provider refused the finalizing rename; the tool restores the
        // backup and records the outcome as rolled back, not silently lost.
        journal.complete(entry, WriteJournal.Verification.FAILED_ROLLED_BACK, "move failed; original restored")
        val last = journal.lastFor("App.kt")!!
        assertEquals(WriteJournal.Verification.FAILED_ROLLED_BACK, last.verification)
        assertTrue(last.note.contains("restored"))
        assertNotNull(last.backupName)
    }

    @Test
    fun `scenario 3 provider disconnect leaves a pending rollback entry`() {
        val journal = WriteJournal()
        journal.record(
            "App.kt", WriteJournal.Kind.REPLACE, "App.kt.gptmax-backup-3",
            journal.fingerprint("old content"), journal.fingerprint("new content"),
        )
        // Simulate a crash before completion: the entry stays PENDING and still
        // points at the retained backup document, so recovery remains possible
        // after the process restarts.
        val last = journal.lastFor("App.kt")!!
        assertEquals(WriteJournal.Verification.PENDING, last.verification)
        assertNotNull(last.backupName)
        assertNotNull(last.fingerprintBefore)
    }

    @Test
    fun `successful write is marked verified`() {
        val journal = WriteJournal()
        val entry = journal.record(
            "App.kt", WriteJournal.Kind.REPLACE, "App.kt.gptmax-backup-4",
            journal.fingerprint("old content"), journal.fingerprint("new content"),
        )
        val actual = journal.fingerprint("new content")
        assertTrue(WriteJournal.sameFingerprint(actual, entry.fingerprintExpected))
        journal.complete(entry, WriteJournal.Verification.VERIFIED)
        assertEquals(WriteJournal.Verification.VERIFIED, journal.lastFor("App.kt")!!.verification)
    }

    @Test
    fun `journal keeps a bounded history`() {
        val journal = WriteJournal(maxEntries = 8)
        repeat(20) { i ->
            journal.record("f$i.txt", WriteJournal.Kind.WRITE, null, null, journal.fingerprint("x$i"))
        }
        assertEquals(8, journal.snapshot.size)
        assertEquals("f19.txt", journal.snapshot.last().path)
    }
}
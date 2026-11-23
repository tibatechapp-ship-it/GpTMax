package com.tibatech.gptmax.core.guardrails

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Review #2 scenarios:
 *  1. external modification between read and write is detected and rejected;
 *  2. failure to stash the rollback backup fails CLOSED (no silent destroy);
 *  3. journal/provider failures never block the write (backup already exists).
 */
class FileWriteGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newGuard(): FileWriteGuard = FileWriteGuard(
        journalFile = File(tmp.root, "journal.jsonl"),
        backupDir = File(tmp.root, "backups"),
    )

    @Test
    fun `overwrite of an unread existing file is rejected`() {
        val guard = newGuard()
        val check = guard.checkWrite("src/App.kt", fileExists = true, currentFingerprint = "abc")
        assertTrue(check is FileWriteGuard.WriteCheck.Stale)
        assertTrue(
            (check as FileWriteGuard.WriteCheck.Stale).reason.contains("not read in this session"),
        )
    }

    @Test
    fun `external modification between read and write is detected`() {
        val guard = newGuard()
        guard.recordRead("src/App.kt", ContentFingerprints.sha256("original"))

        val stale = guard.checkWrite(
            "src/App.kt",
            fileExists = true,
            currentFingerprint = ContentFingerprints.sha256("externally edited"),
        )
        assertTrue(stale is FileWriteGuard.WriteCheck.Stale)
        assertTrue((stale as FileWriteGuard.WriteCheck.Stale).reason.contains("changed since"))

        // After a re-read, the same write is allowed again.
        guard.recordRead("src/App.kt", ContentFingerprints.sha256("externally edited"))
        assertEquals(
            FileWriteGuard.WriteCheck.Allowed,
            guard.checkWrite(
                "src/App.kt",
                fileExists = true,
                currentFingerprint = ContentFingerprints.sha256("externally edited"),
            ),
        )
    }

    @Test
    fun `new files do not require a prior read`() {
        val guard = newGuard()
        assertEquals(
            FileWriteGuard.WriteCheck.Allowed,
            guard.checkWrite("new/File.kt", fileExists = false, currentFingerprint = null),
        )
    }

    @Test
    fun `unfingerprintable existing content fails closed`() {
        val guard = newGuard()
        guard.recordRead("src/App.kt", "known-fingerprint")
        val check = guard.checkWrite("src/App.kt", fileExists = true, currentFingerprint = null)
        assertTrue(check is FileWriteGuard.WriteCheck.Stale)
    }

    @Test
    fun `backup failure fails closed`() {
        // No backup dir configured: the caller must refuse the mutation.
        val guard = FileWriteGuard(journalFile = null, backupDir = null)
        val result = guard.stashBackup("src/App.kt", "original content")
        assertTrue(result is FileWriteGuard.BackupResult.Failed)
    }

    @Test
    fun `backup is stashed and journalled for rollback`() {
        val guard = newGuard()

        val backup = guard.stashBackup("src/App.kt", "original content")
        assertTrue(backup is FileWriteGuard.BackupResult.Stashed)
        val backupName = (backup as FileWriteGuard.BackupResult.Stashed).fileName
        val backupsDir = File(tmp.root, "backups")
        assertTrue(File(backupsDir, backupName).exists())
        assertEquals("original content", File(backupsDir, backupName).readText())

        guard.appendJournal(guard.journalEntry("src/App.kt", backupName, "oldfp", "newfp"))
        val line = File(tmp.root, "journal.jsonl").readText().trim()
        assertTrue(line.contains("\"path\":\"src/App.kt\""))
        assertTrue(line.contains("\"backup\":\"$backupName\""))
        assertTrue(line.contains("\"oldSha256\":\"oldfp\""))
        assertTrue(line.contains("\"newSha256\":\"newfp\""))
    }

    @Test
    fun `journal failure never blocks the write`() {
        // Provider-disconnect analog: a null journal target must be a silent no-op
        // (the backup already exists, so rollback stays possible).
        val guard = FileWriteGuard(journalFile = null, backupDir = File(tmp.root, "backups"))
        guard.appendJournal(guard.journalEntry("a.kt", null, null, null)) // must not throw
        val backup = guard.stashBackup("a.kt", "content")
        assertTrue(backup is FileWriteGuard.BackupResult.Stashed)
    }

    @Test
    fun `only the newest three backups per path are kept`() {
        val guard = FileWriteGuard(
            journalFile = null,
            backupDir = File(tmp.root, "backups"),
            clock = { 1_700_000_000_000L },
        )
        repeat(5) { i ->
            val result = guard.stashBackup("src/App.kt", "version $i")
            assertTrue(result is FileWriteGuard.BackupResult.Stashed)
        }
        val backups = File(tmp.root, "backups").listFiles().orEmpty()
        assertEquals(3, backups.size)
    }
}

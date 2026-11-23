package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security review priority #2: a write must never silently destroy work.
 * Covers the three failure classes from the review: external modification,
 * rename failure, and provider disconnect.
 */
class EditSafetyJournalTest {

    private fun blockedReason(decision: EditSafetyJournal.WriteDecision): String {
        assertTrue("expected a Blocked decision, got $decision", decision is EditSafetyJournal.WriteDecision.Blocked)
        return (decision as EditSafetyJournal.WriteDecision.Blocked).reason
    }

    @Test
    fun newFilesCanBeCreatedWithoutPriorRead() {
        val journal = EditSafetyJournal()
        assertTrue(
            journal.checkWrite("new.kt", currentFingerprint = null, fileExists = false)
                is EditSafetyJournal.WriteDecision.Allowed,
        )
    }

    @Test
    fun existingFileRequiresReadBeforeOverwrite() {
        val journal = EditSafetyJournal()
        val reason = blockedReason(journal.checkWrite("Notes.kt", currentFingerprint = "fp-1", fileExists = true))
        assertTrue(reason.contains("never read"))
    }

    @Test
    fun externalModificationBetweenReadAndWriteIsRejected() {
        val journal = EditSafetyJournal()
        journal.recordRead("src/Api.kt", "fp-read-1")
        val reason = blockedReason(
            journal.checkWrite("src/Api.kt", currentFingerprint = "fp-disk-changed", fileExists = true),
        )
        assertTrue(reason.contains("changed after it was last read"))
    }

    @Test
    fun matchingFingerprintAllowsWrite() {
        val journal = EditSafetyJournal()
        journal.recordRead("src/Api.kt", "fp-read-1")
        assertTrue(
            journal.checkWrite("src/Api.kt", "fp-read-1", fileExists = true)
                is EditSafetyJournal.WriteDecision.Allowed,
        )
    }

    @Test
    fun renameFailureBlocksWritesUntilFileIsReRead() {
        val journal = EditSafetyJournal()
        journal.recordRead("src/Api.kt", "fp-read-1")
        journal.recordFailedWrite("src/Api.kt", backupName = "Api.kt.gptmax-backup-7")
        assertEquals("Api.kt.gptmax-backup-7", journal.lastBackupName("src/Api.kt"))
        val reason = blockedReason(
            journal.checkWrite("src/Api.kt", currentFingerprint = "fp-read-1", fileExists = true),
        )
        assertTrue(reason.contains("failed or was interrupted"))
        journal.recordRead("src/Api.kt", "fp-read-1")
        assertTrue(
            journal.checkWrite("src/Api.kt", "fp-read-1", fileExists = true)
                is EditSafetyJournal.WriteDecision.Allowed,
        )
    }

    @Test
    fun providerDisconnectWithoutBackupIsFailClosed() {
        val journal = EditSafetyJournal()
        journal.recordRead("src/Api.kt", "fp-read-1")
        journal.recordFailedWrite("src/Api.kt", backupName = null)
        assertTrue(
            journal.checkWrite("src/Api.kt", currentFingerprint = "fp-read-1", fileExists = true)
                is EditSafetyJournal.WriteDecision.Blocked,
        )
        assertEquals(2, journal.history("src/Api.kt").size)
    }

    @Test
    fun successfulWriteRefreshesFingerprintSoSecondWriteWorks() {
        val journal = EditSafetyJournal()
        journal.recordRead("a.txt", "fp-1")
        journal.recordWrite("a.txt", "fp-2", backupName = "a.txt.gptmax-backup-1")
        assertTrue(
            journal.checkWrite("a.txt", "fp-2", fileExists = true)
                is EditSafetyJournal.WriteDecision.Allowed,
        )
        val reason = blockedReason(journal.checkWrite("a.txt", "fp-external", fileExists = true))
        assertTrue(reason.contains("changed after it was last read"))
    }

    @Test
    fun historyIsAppendOnlyPerPath() {
        val journal = EditSafetyJournal()
        journal.recordRead("a.txt", "fp-1")
        journal.recordWrite("a.txt", "fp-2", backupName = null)
        journal.recordFailedWrite("a.txt", backupName = null)
        val entries = journal.history("a.txt")
        assertEquals(3, entries.size)
        assertEquals(EditSafetyJournal.EntryKind.READ, entries[0].kind)
        assertEquals(EditSafetyJournal.EntryKind.WRITE, entries[1].kind)
        assertEquals(EditSafetyJournal.EntryKind.FAILED_WRITE, entries[2].kind)
    }

    @Test
    fun pathsAreNormalizedAcrossSeparators() {
        val journal = EditSafetyJournal()
        journal.recordRead("src\\Api.kt", "fp-1")
        assertTrue(
            journal.checkWrite("src/Api.kt", "fp-1", fileExists = true)
                is EditSafetyJournal.WriteDecision.Allowed,
        )
    }
}

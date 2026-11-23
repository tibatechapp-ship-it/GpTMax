package dev.gptmax.app.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review priority #2: writes must never silently destroy work. */
class FileWriteGuardTest {

    @Test
    fun newFilesCanBeCreatedWithoutPriorRead() {
        val guard = FileWriteGuard()
        assertTrue(
            guard.checkWrite("new.kt", fileExists = false, currentFingerprint = null)
                is FileWriteGuard.WriteCheck.Allowed,
        )
    }

    @Test
    fun existingFilesRequireAPriorRead() {
        val guard = FileWriteGuard()
        val check = guard.checkWrite("App.kt", fileExists = true, currentFingerprint = "sha-1")
        assertTrue(check is FileWriteGuard.WriteCheck.Stale)
        assertTrue((check as FileWriteGuard.WriteCheck.Stale).reason.contains("not read"))
    }

    @Test
    fun staleFingerprintsAreRejected() {
        val guard = FileWriteGuard()
        guard.recordRead("App.kt", "sha-old")
        val check = guard.checkWrite("App.kt", fileExists = true, currentFingerprint = "sha-new")
        assertTrue(check is FileWriteGuard.WriteCheck.Stale)
        assertTrue((check as FileWriteGuard.WriteCheck.Stale).reason.contains("changed since it was last read"))
    }

    @Test
    fun matchingFingerprintsAreAllowed() {
        val guard = FileWriteGuard()
        guard.recordRead("App.kt", "sha-1")
        assertTrue(
            guard.checkWrite("App.kt", fileExists = true, currentFingerprint = "sha-1")
                is FileWriteGuard.WriteCheck.Allowed,
        )
    }

    @Test
    fun recordedWritesRefreshTheFingerprint() {
        val guard = FileWriteGuard()
        guard.recordRead("App.kt", "sha-1")
        guard.recordWrite("App.kt", "sha-2")
        assertTrue(
            guard.checkWrite("App.kt", fileExists = true, currentFingerprint = "sha-2")
                is FileWriteGuard.WriteCheck.Allowed,
        )
    }

    @Test
    fun journalEntriesAreAppended() {
        val dir = Files.createTempDirectory("gptmax-journal").toFile()
        try {
            val journal = File(dir, "edit-log.jsonl")
            val guard = FileWriteGuard(journal)
            guard.appendJournal("{\"ts\":1,\"path\":\"a.kt\"}")
            guard.appendJournal("{\"ts\":2,\"path\":\"b.kt\"}")
            assertEquals(2, journal.readText().trim().lines().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingJournalFileIsTolerated() {
        val guard = FileWriteGuard()
        guard.appendJournal("no-op")
    }

    @Test
    fun jsonEscapeProducesSafeSingleLines() {
        assertEquals("a\\\"b\\\\c", jsonEscape("a\"b\\c"))
        assertFalse(jsonEscape("line1\nline2").contains('\n'))
    }
}

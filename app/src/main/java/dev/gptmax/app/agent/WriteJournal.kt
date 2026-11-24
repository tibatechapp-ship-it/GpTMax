package dev.gptmax.app.agent

import java.security.MessageDigest

/**
 * Review fix #2 — rollback journal for agent file writes.
 *
 * The previous write flow deleted its backup the moment the replacement
 * succeeded, which destroyed the only undo path, and it never verified that
 * the bytes on disk matched what the agent asked for. This journal records,
 * for every destructive step:
 *
 *  1. a SHA-256 fingerprint of the content BEFORE the change,
 *  2. the fingerprint the finalized file MUST have after the change,
 *  3. the name of the retained SAF backup document, and
 *  4. the verification outcome (verified / rolled back / unresolved).
 *
 * The Android tool layer (DocumentTreeWriteTool) drives the actual SAF
 * dance; this class keeps the pure, device-free logic so the policy is unit
 * testable on the JVM, including the three failure scenarios: external
 * modification detected at read-back, rename/move failure with restore, and
 * provider disconnect leaving a PENDING entry that still points at backup.
 */
class WriteJournal(private val maxEntries: Int = 32) {

    enum class Kind { WRITE, REPLACE }

    enum class Verification { PENDING, VERIFIED, FAILED_ROLLED_BACK, FAILED_UNRESOLVED }

    data class Entry(
        val path: String,
        val kind: Kind,
        val backupName: String?,
        val fingerprintBefore: String?,
        val fingerprintExpected: String?,
        val verification: Verification = Verification.PENDING,
        val note: String = "",
        val at: Long,
    )

    private val log = ArrayDeque<Entry>()

    /** Most recent entries, oldest first, bounded to [maxEntries]. */
    val snapshot: List<Entry> get() = log.toList()

    /** SHA-256 of UTF-8 content; absent content (null) has no fingerprint. */
    fun fingerprint(content: String?): String? = when (content) {
        null -> null
        else -> MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Called BEFORE the destructive step; captures the rollback contract. */
    fun record(
        path: String,
        kind: Kind,
        backupName: String?,
        fingerprintBefore: String?,
        fingerprintExpected: String?,
        at: Long = System.currentTimeMillis(),
    ): Entry {
        val entry = Entry(
            path = path,
            kind = kind,
            backupName = backupName,
            fingerprintBefore = fingerprintBefore,
            fingerprintExpected = fingerprintExpected,
            at = at,
        )
        log.addLast(entry)
        while (log.size > maxEntries) log.removeFirst()
        return entry
    }

    /** Finalizes an entry with the read-back verification outcome. */
    fun complete(entry: Entry, verification: Verification, note: String = "") {
        val index = log.indexOfFirst { it == entry }
        if (index >= 0) log[index] = entry.copy(verification = verification, note = note)
    }

    fun lastFor(path: String): Entry? = log.lastOrNull { it.path == path }

    companion object {
        /** True only when both hashes exist and match exactly. */
        fun sameFingerprint(actual: String?, expected: String?): Boolean =
            actual != null && expected != null && actual == expected
    }
}

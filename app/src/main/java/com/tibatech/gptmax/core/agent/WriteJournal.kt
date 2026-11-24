package com.tibatech.gptmax.core.agent

import java.security.MessageDigest

/**
 * Rollback journal for agent writes and deletes (review item #2).
 *
 * The earlier design kept the backup only until the write succeeded, which
 * left no undo path. For every destructive step this journal records
 * (1) a SHA-256 fingerprint of the content BEFORE the change and
 * (2) the previous content itself, kept until the agent run finishes, so a
 * failed write, an external modification detected at verification time, or a
 * mid-run provider disconnect can all be rolled back.
 */
class WriteJournal(
    private val maxEntries: Int = 20,
    private val maxBackupChars: Int = 64_000,
) {

    enum class Kind { WRITE, DELETE }

    data class Entry(
        val path: String,
        val kind: Kind,
        val fingerprintBefore: String,
        val hadFileBefore: Boolean,
        val previousContent: String?,
        val at: Long,
    )

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size

    /** Must be called before every write/delete; keeps the rollback copy. */
    fun record(path: String, kind: Kind, previousContent: String?): Entry {
        val entry = Entry(
            path = path,
            kind = kind,
            fingerprintBefore = fingerprintOf(previousContent),
            hadFileBefore = previousContent != null,
            previousContent = previousContent?.take(maxBackupChars),
            at = System.currentTimeMillis(),
        )
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
        return entry
    }

    fun entriesSnapshot(): List<Entry> = entries.toList()

    fun lastFor(path: String): Entry? = entries.lastOrNull { it.path == path }

    fun fingerprintOf(content: String?): String = when (content) {
        null -> "ABSENT"
        else -> sha256(content)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

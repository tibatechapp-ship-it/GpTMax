package com.tibatech.gptmax.data.repo

/**
 * Fix #2 (code review): rollback journal for workspace mutations.
 *
 * Every write or delete records the previous content plus SHA-256
 * fingerprints BEFORE the mutation, and the entry is retained after the
 * write succeeds — the old behaviour deleted the backup immediately, which
 * made a bad write unrecoverable. Content above [MAX_ENTRY_CHARS] is
 * fingerprint-only (recorded for audit, not restorable) to bound memory.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
class WriteJournal(private val capacity: Int = DEFAULT_CAPACITY) {

    enum class Kind { WRITE, DELETE }

    data class Entry(
        val id: Long,
        val kind: Kind,
        val path: String,
        /** Content before the mutation; null when the file did not exist. */
        val oldContent: String?,
        /** SHA-256 of the previous content; null for a plain create. */
        val oldFingerprint: String?,
        /** SHA-256 of what we wrote; empty for deletes. */
        val newFingerprint: String,
        val bytes: Int,
        val at: Long,
        val restorable: Boolean,
        val restored: Boolean = false,
    )

    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L

    @Synchronized
    fun recordWrite(
        path: String,
        oldContent: String?,
        newContent: String,
        at: Long = System.currentTimeMillis(),
    ) {
        push(
            Entry(
                id = nextId++,
                kind = Kind.WRITE,
                path = path,
                oldContent = oldContent?.takeIf { it.length <= MAX_ENTRY_CHARS },
                oldFingerprint = oldContent?.let { Fingerprint.sha256(it) },
                newFingerprint = Fingerprint.sha256(newContent),
                bytes = newContent.toByteArray(Charsets.UTF_8).size,
                at = at,
                restorable = oldContent != null && oldContent.length <= MAX_ENTRY_CHARS,
            ),
        )
    }

    @Synchronized
    fun recordDelete(
        path: String,
        oldContent: String?,
        at: Long = System.currentTimeMillis(),
    ) {
        push(
            Entry(
                id = nextId++,
                kind = Kind.DELETE,
                path = path,
                oldContent = oldContent?.takeIf { it.length <= MAX_ENTRY_CHARS },
                oldFingerprint = oldContent?.let { Fingerprint.sha256(it) },
                newFingerprint = "",
                bytes = oldContent?.toByteArray(Charsets.UTF_8)?.size ?: 0,
                at = at,
                restorable = oldContent != null && oldContent.length <= MAX_ENTRY_CHARS,
            ),
        )
    }

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    /** Latest unrestored entry for [path] — the target of the undo operation. */
    @Synchronized
    fun lastFor(path: String): Entry? =
        entries.lastOrNull { it.path == path && !it.restored }

    @Synchronized
    fun markRestored(id: Long) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) entries[idx] = entries[idx].copy(restored = true)
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun push(entry: Entry) {
        while (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
        const val MAX_ENTRY_CHARS = 262_144
    }
}

package dev.gptmax.app.agent

import java.security.MessageDigest

/** Content fingerprints used to detect files that changed after the agent last saw them. */
object ContentFingerprints {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Session-scoped append-only journal of file reads and writes, keyed by normalized workspace path.
 *
 * Purpose (security review priority #2, "prevent silent edit loss"):
 *  1. Require a read before overwriting an existing file, so the agent cannot blind-write.
 *  2. Reject writes when the file changed on disk since the agent last read it (external modification guard).
 *  3. Fail closed after an interrupted or failed write (rename failure, provider disconnect) until the
 *     file is read again.
 *  4. Remember timestamped backup names so the previous version of every replaced file stays
 *     recoverable instead of being deleted immediately after replacement.
 *
 * Search results intentionally do NOT record reads: a partial scan must never authorize a full overwrite.
 */
class EditSafetyJournal(private val maxTrackedPaths: Int = 512) {

    enum class EntryKind { READ, WRITE, FAILED_WRITE }

    data class Entry(
        val kind: EntryKind,
        val fingerprint: String?,
        val backupName: String?,
        val atMs: Long,
    )

    sealed interface WriteDecision {
        object Allowed : WriteDecision
        data class Blocked(val reason: String) : WriteDecision
    }

    private val lock = Any()
    private val history = LinkedHashMap<String, MutableList<Entry>>()

    fun recordRead(path: String, fingerprint: String, atMs: Long = System.currentTimeMillis()) =
        record(path, Entry(EntryKind.READ, fingerprint, null, atMs), preserveBackup = true)

    fun recordWrite(path: String, newFingerprint: String, backupName: String?, atMs: Long = System.currentTimeMillis()) =
        record(path, Entry(EntryKind.WRITE, newFingerprint, backupName, atMs), preserveBackup = true)

    fun recordFailedWrite(path: String, backupName: String?, atMs: Long = System.currentTimeMillis()) =
        record(path, Entry(EntryKind.FAILED_WRITE, null, backupName, atMs), preserveBackup = true)

    /**
     * Decides whether a write to [path] may proceed.
     *
     * @param currentFingerprint fingerprint of the file content as it exists right now on disk,
     *        or null when the caller could not fingerprint it (callers pass null only for new files).
     * @param fileExists whether the target file currently exists.
     */
    fun checkWrite(path: String, currentFingerprint: String?, fileExists: Boolean): WriteDecision = synchronized(lock) {
        if (!fileExists) return@synchronized WriteDecision.Allowed
        val neverReadReason =
            "Existing file was never read in this session; run file.read before overwriting it."
        val entries = history[normalize(path)]
            ?: return@synchronized WriteDecision.Blocked(neverReadReason)
        when (val last = entries.lastOrNull()) {
            null -> WriteDecision.Blocked(neverReadReason)
            else -> when (last.kind) {
                EntryKind.FAILED_WRITE -> WriteDecision.Blocked(
                    "A previous write to this file failed or was interrupted; re-read the file, then retry.",
                )
                EntryKind.READ, EntryKind.WRITE -> when {
                    last.fingerprint == null -> WriteDecision.Allowed
                    currentFingerprint == null -> WriteDecision.Blocked(
                        "Current file content could not be fingerprinted; re-read the file before writing.",
                    )
                    last.fingerprint == currentFingerprint -> WriteDecision.Allowed
                    else -> WriteDecision.Blocked(
                        "File changed after it was last read (external modification or stale view); " +
                            "re-read the file and merge your changes before writing.",
                    )
                }
            }
        }
    }

    /** Most recent backup name recorded for this path, for rollback hints in tool output. */
    fun lastBackupName(path: String): String? = synchronized(lock) {
        history[normalize(path)]?.lastOrNull { it.backupName != null }?.backupName
    }

    /** Append-only history snapshot for one path (oldest first). */
    fun history(path: String): List<Entry> = synchronized(lock) {
        history[normalize(path)]?.toList().orEmpty()
    }

    private fun record(path: String, entry: Entry, preserveBackup: Boolean) = synchronized(lock) {
        val key = normalize(path)
        val effectiveBackup = entry.backupName
            ?: if (preserveBackup) {
                history[key]?.lastOrNull { it.backupName != null }?.backupName
            } else {
                null
            }
        history.getOrPut(key) { mutableListOf() }.add(entry.copy(backupName = effectiveBackup))
        trim()
        Unit
    }

    private fun trim() {
        while (history.size > maxTrackedPaths) {
            val oldest = history.keys.firstOrNull() ?: break
            history.remove(oldest)
        }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')
}

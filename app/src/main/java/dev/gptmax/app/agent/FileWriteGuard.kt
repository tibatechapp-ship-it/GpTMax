package dev.gptmax.app.agent

import java.io.File
import java.security.MessageDigest

/** SHA-256 fingerprints used to detect external file modifications between read and write. */
internal object ContentFingerprints {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Minimal JSON string escaping for single-line journal entries. */
internal fun jsonEscape(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/**
 * Review finding #2: writes had no "changed since last read" validation, and the rollback backup was
 * deleted immediately after replacement, so an external edit could be destroyed silently with no way
 * to recover it.
 *
 * FileWriteGuard keeps a per-session map of path to fingerprint of the content the agent last saw:
 *  - overwriting an existing file requires a prior read in the same session;
 *  - a write is rejected when the on-disk fingerprint differs from the recorded one;
 *  - successful replacements are appended to a JSONL journal (path, backup name, fingerprints) so
 *    edits stay rollback-able.
 */
internal class FileWriteGuard(private val journalFile: File? = null) {

    sealed class WriteCheck {
        object Allowed : WriteCheck()
        data class Stale(val reason: String) : WriteCheck()
    }

    private val readFingerprints = LinkedHashMap<String, String>()
    private val lock = Any()

    fun recordRead(path: String, fingerprint: String) = synchronized(lock) {
        readFingerprints[normalize(path)] = fingerprint
        Unit
    }

    fun recordWrite(path: String, newFingerprint: String) = synchronized(lock) {
        readFingerprints[normalize(path)] = newFingerprint
        Unit
    }

    /**
     * @param fileExists whether the target currently exists on disk.
     * @param currentFingerprint fingerprint of the current on-disk content, or null when the file
     *        does not exist or its content could not be read for fingerprinting.
     */
    fun checkWrite(path: String, fileExists: Boolean, currentFingerprint: String?): WriteCheck = synchronized(lock) {
        val known = readFingerprints[normalize(path)]
        when {
            !fileExists -> WriteCheck.Allowed
            known == null -> WriteCheck.Stale(
                "This file exists but was not read in this session. Call file.read first; overwriting unread files risks destroying external changes.",
            )
            currentFingerprint == null -> WriteCheck.Stale(
                "Current content could not be fingerprinted; refusing to overwrite. Re-read the file first.",
            )
            known != currentFingerprint -> WriteCheck.Stale(
                "File changed since it was last read (external modification or stale view). Re-read the file and merge your changes.",
            )
            else -> WriteCheck.Allowed
        }
    }

    /** Appends one raw JSON line. Failures are swallowed: journaling must never block a write. */
    fun appendJournal(line: String) {
        val target = journalFile ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n")
        }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')
}

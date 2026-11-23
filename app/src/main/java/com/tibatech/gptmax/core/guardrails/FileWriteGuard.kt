package com.tibatech.gptmax.core.guardrails

import java.io.File
import java.security.MessageDigest

/** SHA-256 fingerprints used to detect external file modifications between read and write. */
object ContentFingerprints {
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
 * Review finding #2: writes had no "changed since last read" validation and
 * the previous content was destroyed with no way to recover it.
 *
 * [FileWriteGuard] keeps a per-session map of path to fingerprint of the
 * content the agent last saw:
 *  - overwriting an existing file requires a prior read in the same session;
 *  - a write is rejected when the on-disk fingerprint differs from the
 *    recorded one (external modification);
 *  - replacements and deletes stash a timestamped backup first (fail-closed:
 *    if the backup cannot be created the mutation is refused) and append a
 *    JSONL rollback journal entry (path, backup name, fingerprints).
 */
class FileWriteGuard(
    private val journalFile: File? = null,
    private val backupDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBackupsPerPath: Int = 3,
) {

    sealed class WriteCheck {
        object Allowed : WriteCheck()
        data class Stale(val reason: String) : WriteCheck()
    }

    sealed class BackupResult {
        data class Stashed(val fileName: String) : BackupResult()
        data class Failed(val reason: String) : BackupResult()
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
     * @param fileExists whether the target currently exists in the tree.
     * @param currentFingerprint fingerprint of the current content, or null
     *        when the file does not exist or could not be fingerprinted.
     */
    fun checkWrite(
        path: String,
        fileExists: Boolean,
        currentFingerprint: String?,
    ): WriteCheck = synchronized(lock) {
        val known = readFingerprints[normalize(path)]
        when {
            !fileExists -> WriteCheck.Allowed
            known == null -> WriteCheck.Stale(
                "This file exists but was not read in this session. Read it first; overwriting " +
                    "unread files risks destroying external changes.",
            )
            currentFingerprint == null -> WriteCheck.Stale(
                "Current content could not be fingerprinted; refusing to overwrite. Re-read the file first.",
            )
            known != currentFingerprint -> WriteCheck.Stale(
                "File changed since it was last read (external modification or stale view). " +
                    "Re-read the file and merge your changes.",
            )
            else -> WriteCheck.Allowed
        }
    }

    /**
     * Stashes the current content as a timestamped backup before an overwrite
     * or delete. Fail-closed: callers must refuse the mutation when this
     * returns [BackupResult.Failed].
     */
    fun stashBackup(path: String, content: String): BackupResult {
        val dir = backupDir ?: return BackupResult.Failed("no backup directory configured")
        return runCatching {
            dir.mkdirs()
            val safeName = sanitize(path.substringAfterLast('/'))
            val ts = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT)
                .format(java.util.Date(clock()))
            var fileName = "$ts-$safeName.bak"
            var target = File(dir, fileName)
            var n = 1
            while (target.exists()) {
                fileName = "$ts-$safeName-$n.bak"
                target = File(dir, fileName)
                n++
            }
            target.writeText(content)
            prune(path, safeName)
            BackupResult.Stashed(fileName)
        }.getOrElse { BackupResult.Failed("backup failed: ${it.message}") }
    }

    /** One raw JSON line for the rollback journal. */
    fun journalEntry(
        path: String,
        backupName: String?,
        oldFingerprint: String?,
        newFingerprint: String?,
    ): String =
        "{\"ts\":${clock()},\"path\":\"${jsonEscape(path)}\",\"backup\":" +
            (backupName?.let { "\"${jsonEscape(it)}\"" } ?: "null") +
            ",\"oldSha256\":" + (oldFingerprint?.let { "\"$it\"" } ?: "null") +
            ",\"newSha256\":" + (newFingerprint?.let { "\"$it\"" } ?: "null") + "}"

    /** Appends one raw JSON line. Failures are swallowed: journaling must never block a write. */
    fun appendJournal(line: String) {
        val target = journalFile ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n")
        }
    }

    /** Keeps only the newest [maxBackupsPerPath] backups for one source path. */
    private fun prune(path: String, safeName: String) {
        val dir = backupDir ?: return
        val pattern = Regex("\\d{8}T\\d{6}(?:-\\d+)?-${Regex.escape(safeName)}\\.bak$")
        val matching = dir.listFiles { f -> pattern.containsMatchIn(f.name) } ?: return
        matching.sortByDescending { it.name }
        matching.drop(maxBackupsPerPath).forEach { it.delete() }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')
}

package com.tibatech.gptmax.core.agent

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable rollback journal for agent writes and deletes (review items #2/#3).
 *
 * History of this class:
 * - v1 kept the backup only until the write succeeded — no undo path.
 * - v2 kept an in-memory ring of the last N entries but TRUNCATED the backup
 *   copies to 64k chars while computing the fingerprint over the FULL
 *   content — rolling back a >64k file restored a silently corrupted,
 *   truncated version. Everything also died with the process, so "resume"
 *   could not verify what had actually landed on disk.
 *
 * This version (external review, data-safety priority) fixes both:
 *
 * - **Full byte copies.** The previous content AND the intended new content
 *   are stored COMPLETELY — never truncated — so a rollback restores the
 *   original bytes exactly and the resume check can fingerprint the intent.
 * - **Durable across process death.** With a non-null [dir] every entry is
 *   persisted as `jnl-<seq>.meta` + `jnl-<seq>.before` + `jnl-<seq>.after`.
 *   Contents are written FIRST and the meta file LAST — the meta file is
 *   the commit point, so a torn write can only ever leave an orphan file
 *   that the index scan ignores. A new process rebuilds the index from disk
 *   and AgentCore uses it to verify step state before resuming.
 * - Bounded: only the newest [maxEntries] entries are kept; older files are
 *   deleted.
 *
 * [dir] == null keeps the v2 in-memory behaviour (JVM tests) — still with
 * full copies, never truncated.
 *
 * Single-writer: the agent loop is the only caller; no locking is needed.
 */
class WriteJournal(
    private val dir: File?,
    private val maxEntries: Int = 20,
) {

    enum class Kind { WRITE, DELETE }

    /** Persisted index record (metadata only — contents live in side files). */
    @Serializable
    data class Meta(
        val seq: Long,
        val path: String,
        val kind: String,
        val fingerprintBefore: String,
        val hadFileBefore: Boolean,
        val hadPreviousContent: Boolean,
        val hadNewContent: Boolean,
        val fingerprintAfter: String,
        val stepIndex: Int,
        val at: Long,
    )

    data class Entry(
        val path: String,
        val kind: Kind,
        val fingerprintBefore: String,
        val hadFileBefore: Boolean,
        /** FULL previous content — never truncated (the v2 bug is fixed). */
        val previousContent: String?,
        /** FULL intended new content — the resume verifier's source of truth. */
        val newContent: String?,
        val fingerprintAfter: String,
        /** Agent step this write belongs to (-1 when not recorded by a step). */
        val stepIndex: Int,
        val at: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Oldest-first metadata index (rebuilt from disk when [dir] is set). */
    private val index = ArrayDeque<Meta>()

    /** Memory-mode content store: seq -> (previousContent, newContent). */
    private val cache = HashMap<Long, Pair<String?, String?>>()

    private var nextSeq: Long = 0

    init {
        if (dir != null) rebuildIndex()
    }

    val size: Int get() = index.size

    /**
     * Must be called before every write/delete; keeps the FULL rollback copy
     * and (for writes) the FULL intended content with its fingerprint.
     */
    fun record(
        path: String,
        kind: Kind,
        previousContent: String?,
        newContent: String? = null,
        stepIndex: Int = -1,
    ): Entry {
        val entry = Entry(
            path = path,
            kind = kind,
            fingerprintBefore = fingerprintOf(previousContent),
            hadFileBefore = previousContent != null,
            previousContent = previousContent,
            newContent = newContent,
            fingerprintAfter = fingerprintOf(newContent),
            stepIndex = stepIndex,
            at = System.currentTimeMillis(),
        )
        val meta = Meta(
            seq = nextSeq,
            path = entry.path,
            kind = entry.kind.name,
            fingerprintBefore = entry.fingerprintBefore,
            hadFileBefore = entry.hadFileBefore,
            hadPreviousContent = entry.previousContent != null,
            hadNewContent = entry.newContent != null,
            fingerprintAfter = entry.fingerprintAfter,
            stepIndex = entry.stepIndex,
            at = entry.at,
        )
        nextSeq += 1
        if (dir != null) {
            // Contents FIRST, meta LAST — meta is the commit point.
            // (local capture: smart casts don't cross the lambda boundary)
            val d = dir
            runCatching {
                d.mkdirs()
                if (entry.previousContent != null) {
                    File(d, beforeFile(meta.seq)).writeText(entry.previousContent, Charsets.UTF_8)
                }
                if (entry.newContent != null) {
                    File(d, afterFile(meta.seq)).writeText(entry.newContent, Charsets.UTF_8)
                }
                File(d, metaFile(meta.seq)).writeText(json.encodeToString(meta), Charsets.UTF_8)
            }
            index.addLast(meta)
            while (index.size > maxEntries) dropOldest()
        } else {
            index.addLast(meta)
            cache[meta.seq] = entry.previousContent to entry.newContent
            while (index.size > maxEntries) dropOldest()
        }
        return entry
    }

    /** Snapshot of every kept entry (bounded by [maxEntries]). */
    fun entriesSnapshot(): List<Entry> = index.mapNotNull { readEntry(it) }

    fun lastFor(path: String): Entry? =
        index.lastOrNull { it.path == path }?.let { readEntry(it) }

    /**
     * Resume verification (external review): the LAST entry recorded by the
     * agent step [stepIndex] on [path] — the fingerprint pair tells the core
     * whether the write already landed, never ran, or the file drifted.
     */
    fun lastForStep(path: String, stepIndex: Int): Entry? =
        index.lastOrNull { it.path == path && it.stepIndex == stepIndex }?.let { readEntry(it) }

    fun fingerprintOf(content: String?): String = when (content) {
        null -> ABSENT
        else -> sha256(content)
    }

    private fun readEntry(meta: Meta): Entry? = runCatching {
        val d = dir
        val pair = if (d != null) {
            val before = if (meta.hadPreviousContent) {
                File(d, beforeFile(meta.seq)).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            } else {
                null
            }
            val after = if (meta.hadNewContent) {
                File(d, afterFile(meta.seq)).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            } else {
                null
            }
            before to after
        } else {
            cache[meta.seq]
        } ?: return null
        Entry(
            path = meta.path,
            kind = runCatching { Kind.valueOf(meta.kind) }.getOrDefault(Kind.WRITE),
            fingerprintBefore = meta.fingerprintBefore,
            hadFileBefore = meta.hadFileBefore,
            previousContent = pair.first,
            newContent = pair.second,
            fingerprintAfter = meta.fingerprintAfter,
            stepIndex = meta.stepIndex,
            at = meta.at,
        )
    }.getOrNull()

    private fun dropOldest() {
        val oldest = index.removeFirstOrNull() ?: return
        cache.remove(oldest.seq)
        if (dir != null) {
            runCatching {
                File(dir, metaFile(oldest.seq)).delete()
                File(dir, beforeFile(oldest.seq)).delete()
                File(dir, afterFile(oldest.seq)).delete()
            }
        }
    }

    /** Rebuilds the index from an existing journal directory (new process). */
    private fun rebuildIndex() {
        val d = dir ?: return
        runCatching {
            val metas = d.listFiles { f -> f.name.endsWith(".meta") }
                ?.mapNotNull { f ->
                    runCatching { json.decodeFromString<Meta>(f.readText(Charsets.UTF_8)) }.getOrNull()
                }
                ?.sortedBy { it.seq }
                ?: emptyList()
            index.clear()
            metas.takeLast(maxEntries).forEach { index.addLast(it) }
            // Drop orphan side files of pruned/orphaned seqs so the dir cannot
            // grow without bound across runs.
            val keep = index.map { it.seq }.toSet()
            d.listFiles { f -> f.name.startsWith("jnl-") }?.forEach { f ->
                val seq = f.name.removePrefix("jnl-").substringBefore('.').toLongOrNull()
                if (seq == null || seq !in keep) runCatching { f.delete() }
            }
            nextSeq = (index.lastOrNull()?.seq ?: -1) + 1
        }
    }

    private fun metaFile(seq: Long) = "jnl-$seq.meta"
    private fun beforeFile(seq: Long) = "jnl-$seq.before"
    private fun afterFile(seq: Long) = "jnl-$seq.after"

    companion object {
        /** Fingerprint of "the file did not exist / must not exist". */
        const val ABSENT = "ABSENT"

        /** Memory-only journal (JVM tests): full copies, no disk. */
        fun inMemory(maxEntries: Int = 20): WriteJournal = WriteJournal(null, maxEntries)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

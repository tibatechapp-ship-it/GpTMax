package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths

/**
 * Phase 1 (external review, high severity): verified save for the editor.
 *
 * The old EditorViewModel ignored the result of workspace.write() and
 * cleared the "unsaved" flag unconditionally — a failed provider write
 * silently looked saved, losing user edits on the next navigation. The
 * transaction returns a confirmed outcome ONLY after the write succeeded
 * AND a read-back matches the bytes handed in; the caller must clear the
 * unsaved flag exactly when confirmed == true, and surface the error
 * otherwise.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
class SaveTransaction(private val workspace: WorkspaceOps) {

    data class Outcome(
        val confirmed: Boolean,
        val error: String,
    )

    suspend fun save(path: String, content: String): Outcome {
        val clean = path.trim()
        if (clean.isBlank()) return Outcome(false, "no file open")
        if (!PathGuard.isAllowed(clean)) return Outcome(false, PathGuard.describe())
        if (SensitivePaths.isSensitive(clean)) return Outcome(false, SensitivePaths.describe(clean))
        if (SensitivePaths.isProtectedFromWrite(clean)) {
            return Outcome(false, SensitivePaths.describeWrite(clean))
        }

        val wrote = runCatching { workspace.write(clean, content) }.getOrDefault(false)
        if (!wrote) return Outcome(false, "save failed — your changes are NOT saved yet")

        // Read-back verify: an externally rewritten file must not count as saved.
        val back = runCatching { workspace.read(clean) }.getOrNull()
        return if (back == content) Outcome(true, "")
        else Outcome(false, "save could not be verified (file diverged) — your changes are NOT saved yet")
    }
}

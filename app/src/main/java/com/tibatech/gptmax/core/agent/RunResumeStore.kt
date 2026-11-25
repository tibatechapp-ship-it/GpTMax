package com.tibatech.gptmax.core.agent

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 3 (external review, resumability): persisted per-step state of ONE
 * interruptible agent run.
 *
 * The old flow kept the plan, the step statuses and the rollback journal in
 * process memory only — a process death (OOM killer, user swipe, crash)
 * silently destroyed everything and the only recovery was starting over.
 * This store serializes the run to one JSON file in the app-private dir:
 *
 * - written ATOMICALLY (tmp file + rename) so a crash mid-write can never
 *   corrupt the previous snapshot;
 * - cleared when the run COMPLETES; kept when the run is cancelled, fails,
 *   or the process dies — the next launch offers the user a Resume;
 * - oversized step args are capped PER STRING FIELD and re-serialized, so
 *   the stored args are ALWAYS valid JSON (the old blind string truncation
 *   produced broken JSON that failed to parse on resume). Any capped value
 *   carries [TRUNCATED_MARK] INSIDE the string; the tool layer refuses to
 *   execute a write whose content carries that mark — a capped write is
 *   never silently executed as a partial overwrite (fail-closed).
 *
 * Pure Kotlin (java.io.File): unit-testable on the JVM without Android.
 */
@Serializable
data class StepState(
    val index: Int,
    val tool: String,
    val argsJson: String,
    val description: String,
    val isWrite: Boolean,
    val status: String,
    val result: String,
)

@Serializable
data class ResumableRun(
    val taskId: String,
    val goal: String,
    /** The workspace the run was pinned to — resume refuses a different one. */
    val workspaceUri: String?,
    val phase: String,
    val startedAt: Long,
    val steps: List<StepState>,
)

class RunResumeStore(
    private val dir: File,
    private val maxStepArgsChars: Int = MAX_STEP_ARGS_CHARS,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val file = File(dir, FILE_NAME)

    /** True when a resumable snapshot is currently on disk. */
    val exists: Boolean
        get() = file.exists() && file.length() > 0

    /**
     * Persists [run] atomically. Oversized string fields inside step args
     * are capped and re-encoded to VALID JSON (never a broken mid-string
     * cut); capped values carry [TRUNCATED_MARK] and the tool layer refuses
     * to execute writes carrying it. If the total snapshot would exceed the
     * file budget, resumability is dropped instead of crashing.
     */
    fun save(run: ResumableRun) {
        runCatching {
            dir.mkdirs()
            val capped = run.copy(
                steps = run.steps.map { step ->
                    if (step.argsJson.length > maxStepArgsChars) {
                        step.copy(
                            argsJson = capArgs(step.argsJson, maxStepArgsChars),
                            result = step.result + " [args capped for resume]",
                        )
                    } else {
                        step
                    }
                },
            )
            val text = json.encodeToString(capped)
            if (text.length > MAX_FILE_CHARS) {
                clear()
                return
            }
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                clear()
            }
        }.onFailure { runCatching { clear() } }
    }

    /** Returns the persisted run, or null when absent/corrupted. */
    fun load(): ResumableRun? {
        val decoded = runCatching {
            if (!file.exists()) return null
            json.decodeFromString<ResumableRun>(file.readText(Charsets.UTF_8))
        }.getOrNull()
        if (decoded == null) runCatching { file.delete() }
        return decoded
    }

    /** Removes the snapshot (idempotent, safe when nothing is stored). */
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Caps string fields of a JSON document WITHOUT breaking its structure:
     * parse, cap each string value over [budget] (appending the
     * [TRUNCATED_MARK] sentinel), re-encode. Unparsable args degrade to
     * `{}` — every tool then fails cleanly on missing args instead of
     * choking on invalid JSON at resume time.
     */
    private fun capArgs(argsJson: String, budget: Int): String {
        val root = runCatching { json.parseToJsonElement(argsJson) }.getOrNull()
            ?: return "{}"
        return json.encodeToString(JsonElement.serializer(), capStrings(root, budget))
    }

    private fun capStrings(el: JsonElement, budget: Int): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.map { (k, v) -> k to capStrings(v, budget) }.toMap())
        is JsonArray -> JsonArray(el.map { capStrings(it, budget) })
        is JsonPrimitive ->
            if (el.isString && el.content.length > budget) {
                JsonPrimitive(el.content.take(budget) + TRUNCATED_MARK)
            } else {
                el
            }
        else -> el
    }

    companion object {
        const val FILE_NAME = "resumable-run.json"

        /** Per-step args cap (chars) — write_file content beyond this truncates. */
        const val MAX_STEP_ARGS_CHARS = 65_536

        /** Whole-snapshot budget (chars) — beyond it the run is not resumable. */
        const val MAX_FILE_CHARS = 1_000_000

        /**
         * Sentinel inside a capped string value. The tool layer REFUSES any
         * write whose content/diff contains it — a capped write is never
         * executed as a silent partial overwrite (fail-closed).
         */
        const val TRUNCATED_MARK = "[GPTMAX-RESUME-TRUNCATED]"

        /** Builds a [ResumableRun] from live [AgentStep]s. */
        fun from(
            steps: List<AgentStep>,
            taskId: String,
            goal: String,
            workspaceUri: String?,
            phase: String,
            startedAt: Long,
        ): ResumableRun = ResumableRun(
            taskId = taskId,
            goal = goal,
            workspaceUri = workspaceUri,
            phase = phase,
            startedAt = startedAt,
            steps = steps.map {
                StepState(
                    index = it.index,
                    tool = it.tool,
                    argsJson = it.argsJson,
                    description = it.description,
                    isWrite = it.isWrite,
                    status = it.status.name,
                    result = it.result,
                )
            },
        )
    }
}

/** Restores live [AgentStep]s from a persisted run; unknown statuses degrade to PENDING. */
fun ResumableRun.toAgentSteps(): List<AgentStep> = steps.map {
    AgentStep(
        index = it.index,
        tool = it.tool,
        argsJson = it.argsJson,
        description = it.description,
        isWrite = it.isWrite,
    ).apply {
        status = runCatching { AgentStep.Status.valueOf(it.status) }
            .getOrDefault(AgentStep.Status.PENDING)
        result = it.result
    }
}

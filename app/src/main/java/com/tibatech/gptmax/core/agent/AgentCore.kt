package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.core.context.ContextBudget
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.ApprovalGate
import com.tibatech.gptmax.core.guardrails.InferenceSafety
import com.tibatech.gptmax.core.prompts.PromptTemplates
import com.tibatech.gptmax.core.router.InferenceRouter
import com.tibatech.gptmax.core.router.LlmRequest
import com.tibatech.gptmax.core.router.TaskKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The GpTMax agent loop:
 *
 *   PLANNING -> GATHERING -> ACTING -> VALIDATING -> (approval gate) -> DONE
 *
 * Planning and validation prefer the cloud (when cloud-first is on and a key
 * is set) because they benefit from long context; execution steps run on the
 * local GGUF engine whenever it is loaded.
 *
 * Review #3: [running] is exposed so the Models screen can refuse to delete
 * or unload the engine while the agent holds it.
 * Review #5: [cancel] gives per-request cancellation; every loop boundary
 * (before each step, after the approval gate, before validation) aborts, and
 * step observations are trimmed to a hard budget before they are fed back to
 * the validator, so stale results cannot crowd the context window.
 * Review #6: phases and tool calls are timed and emitted as Metrics events,
 * the plan is bounded to [MAX_STEPS] to cap agent autonomy, and run_command
 * previews show the sandboxed command before the user approves it.
 *
 * Phase 2 (external review, inference safety): planning and validation now
 * go through [InferenceRouter.completeWithFallback] — a typed client failure
 * ("cloud error", "engine not loaded") is retried once on the other source
 * instead of being parsed as a model answer, and review text is sanitized
 * before it reaches the log/UI.
 *
 * Phase 3 (external review, resumability): when a [RunResumeStore] is wired,
 * the plan and every step status are persisted at each loop boundary. A
 * process death, a crash or a user stop leaves a resumable snapshot on disk;
 * [run] accepts that snapshot via [resume], skips steps already DONE, and
 * re-asks the approval gate for every pending write. A COMPLETED run clears
 * the snapshot; a cancelled/failed one keeps it resumable.
 *
 * Phase 5 (external-review roadmap M5, adaptive loop + honest outcomes):
 * - `patch_file` joins the toolset; its approval preview is the actual
 *   unified diff the model wants to apply.
 * - The reviewer now sees REAL evidence: the unified diff of every applied
 *   change (via [ToolResult.diff]) plus every failed/rejected step — the
 *   old input was file names and trimmed observations only.
 * - DONE is EARNED, not assumed: a rejected write ends BLOCKED, failed or
 *   truncated or unfinished plans end PARTIAL, a reviewer demanding changes
 *   ends VERIFY_FAILED, an inconclusive review stays unverified (PARTIAL).
 *   Only a clean run with an explicit approval verdict reports DONE.
 * - Resumability follows honesty: only DONE clears the snapshot —
 *   PARTIAL/BLOCKED/VERIFY_FAILED runs stay resumable, completed steps are
 *   never re-run, failed ones retry from the exact boundary.
 */
class AgentCore(
    private val router: InferenceRouter,
    private val tools: ToolRegistry,
    private val context: ContextBuilder,
    private val approval: ApprovalGate,
    private val resumeStore: RunResumeStore? = null,
    private val maxSteps: Int = MAX_STEPS,
) {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentEvent> = _events

    /** True while a run is in flight; the Models screen must not delete/unload when set. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var cancelled = false

    /** Per-request cancellation: takes effect at the next loop boundary. */
    fun cancel() {
        cancelled = true
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(task: AgentTask, resume: ResumableRun? = null) {
        if (_running.value) {
            emit(AgentEvent.Failed("another run is already active"))
            return
        }
        cancelled = false
        _running.value = true
        val runStart = now()
        try {
            val bounded: List<AgentStep>
            var planTruncated = false
            if (resume != null) {
                // ---- RESUMED run: no re-planning, restore persisted steps ----
                val done = resume.steps.count { it.status == AgentStep.Status.DONE.name }
                emit(AgentEvent.Resumed(done, resume.steps.size))
                emit(
                    AgentEvent.Log(
                        "resuming run ${resume.taskId.take(8)} — " +
                            "$done/${resume.steps.size} steps already completed",
                    ),
                )
                bounded = resume.toAgentSteps().take(maxSteps)
                // Phase 6 (external review, data durability): verify the
                // CURRENT on-disk state of every pending write against the
                // DURABLE journal before anything re-runs. A process death
                // between executing a write and logging its DONE must not
                // duplicate the write on resume; an externally-changed file
                // must be surfaced, not silently overwritten.
                verifyResumedWrites(bounded)
                emit(AgentEvent.PhaseChanged(AgentPhase.GATHERING))
                val touched = bounded.mapNotNull { pathArg(it) }.distinct()
                context.digest(touched)
            } else {
                emit(AgentEvent.PhaseChanged(AgentPhase.PLANNING))
                val planStart = now()
                val plan = plan(task.goal)
                emit(AgentEvent.Metrics("planning", elapsed(planStart)))
                if (plan.isEmpty()) {
                    resumeStore?.clear()
                    emit(AgentEvent.Failed("planning produced no usable steps"))
                    emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                    return
                }
                // Bound agent autonomy: never execute more than maxSteps actions.
                // Phase 5: a TRUNCATED plan is never a silent success — the
                // finish step or later steps may have been cut off.
                planTruncated = plan.size > maxSteps
                if (planTruncated) {
                    emit(
                        AgentEvent.Log(
                            "plan truncated to $maxSteps of ${plan.size} steps — " +
                                "the run cannot be reported as fully successful",
                        ),
                    )
                }
                bounded = plan.take(maxSteps)

                // ---- GATHERING: preload files the plan will read ----------------
                emit(AgentEvent.PhaseChanged(AgentPhase.GATHERING))
                val gatherStart = now()
                val touched = bounded.mapNotNull { pathArg(it) }.distinct()
                context.digest(touched)
                emit(AgentEvent.Metrics("gathering", elapsed(gatherStart)))
            }
            persistSnapshot(bounded, task, "ACTING", runStart)

            // ---- ACTING ------------------------------------------------------
            emit(AgentEvent.PhaseChanged(AgentPhase.ACTING))
            val executed = mutableListOf<AgentStep>()
            val observations = mutableListOf<String>()
            // Phase 5: per-step unified diffs (M5 review evidence) + outcome
            // counters for the honest completion status.
            val stepDiffs = mutableMapOf<Int, String>()
            var rejectedCount = 0
            var failedCount = 0
            var reachedFinish = false
            var summary = ""
            var stopped = false
            for (step in bounded) {
                // Phase 3: work already completed before the interruption is
                // NEVER re-executed — its persisted result feeds validation.
                if (resume != null && step.status == AgentStep.Status.DONE) {
                    emit(
                        AgentEvent.Log(
                            "resuming: step ${step.index} (${step.tool}) already done — skipped",
                        ),
                    )
                    executed += step
                    observations += "${step.tool} ${argValue(step, "path")} -> " +
                        "done: ${step.result.take(OBSERVATION_CHARS)}"
                    continue
                }
                if (cancelled) {
                    stopped = true
                    break
                }
                if (step.tool == "finish") {
                    summary = argValue(step, "summary").ifBlank { "done" }
                    reachedFinish = true
                    break
                }
                emit(AgentEvent.StepStarted(step))
                step.status = AgentStep.Status.RUNNING

                if (step.isWrite) {
                    val diff = previewWrite(step)
                    emit(AgentEvent.ApprovalRequired(step, diff))
                    val approved = approval.request(step, diff)
                    if (cancelled) {
                        stopped = true
                        break
                    }
                    if (!approved) {
                        step.status = AgentStep.Status.REJECTED
                        step.result = "rejected by user"
                        rejectedCount++
                        emit(AgentEvent.StepFinished(step))
                        persistSnapshot(bounded, task, "ACTING", runStart)
                        continue
                    }
                }

                val stepStart = now()
                val result = tools.execute(step)
                emit(AgentEvent.Metrics("tool:${step.tool}", elapsed(stepStart)))
                result.diff?.let { stepDiffs[step.index] = it }
                step.result = InferenceSafety.sanitize(result.output, 500)
                step.status = if (result.ok) AgentStep.Status.DONE else AgentStep.Status.FAILED
                if (!result.ok) failedCount++
                executed += step
                emit(AgentEvent.StepFinished(step))
                persistSnapshot(bounded, task, "ACTING", runStart)
                // Review #5: one bounded observation per executed step;
                // ContextBudget trims the OLDEST ones before validation.
                observations += "${step.tool} ${argValue(step, "path")} -> " +
                    "${step.status.name.lowercase()}: ${result.output.take(OBSERVATION_CHARS)}"
            }
            if (stopped) {
                // Cancelled: the snapshot stays on disk — this run remains
                // resumable from the exact boundary the user stopped at.
                persistSnapshot(bounded, task, "ACTING", runStart)
                emit(AgentEvent.Failed("cancelled by user"))
                emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                return
            }

            // ---- VALIDATING ---------------------------------------------------
            emit(AgentEvent.PhaseChanged(AgentPhase.VALIDATING))
            if (cancelled) {
                persistSnapshot(bounded, task, "ACTING", runStart)
                emit(AgentEvent.Failed("cancelled by user"))
                emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                return
            }
            val validateStart = now()
            // Phase 5 (M5 review evidence): the reviewer sees the REAL
            // unified diff of every applied change plus every failed/rejected
            // step with its result — the old input was file names and
            // trimmed observations, never enough to judge correctness.
            val diffEvidence = executed.mapNotNull { stepDiffs[it.index] }
            val boundedDiffs = ContextBudget
                .trimNewestFirst(diffEvidence, DIFF_EVIDENCE_CHARS) { it.length }
                .joinToString("\n\n")
            val problemList = executed
                .filter {
                    it.status == AgentStep.Status.FAILED || it.status == AgentStep.Status.REJECTED
                }
                .joinToString("\n") {
                    "step ${it.index} ${it.tool} ${argValue(it, "path")}: " +
                        "${it.status.name.lowercase()} — ${it.result.take(200)}"
                }
            // Review #5: old observations must not crowd the context budget —
            // keep the newest ones that fit, newest-first, and feed only those
            // to the validator alongside the change evidence.
            val boundedObservations = ContextBudget
                .trimNewestFirst(observations, OBSERVATION_BUDGET_CHARS) { it.length }
                .joinToString("\n")
            val reviewInput = buildString {
                if (boundedDiffs.isNotBlank()) {
                    append("Applied changes (unified diff):\n")
                    append(boundedDiffs)
                }
                if (problemList.isNotBlank()) {
                    append("\n\nProblems during execution:\n")
                    append(problemList)
                }
                if (boundedObservations.isNotBlank()) {
                    append("\n\nRecent observations:\n")
                    append(boundedObservations)
                }
                if (isEmpty()) append("(no writes were applied — read-only run)")
            }
            val validation = router.completeWithFallback(
                LlmRequest(messages = listOf(PromptTemplates.validateSystem(reviewInput))),
                TaskKind.VALIDATE,
            )
            emit(AgentEvent.Metrics("validating", elapsed(validateStart)))
            emit(AgentEvent.Log("review via ${validation.source.name.lowercase()}: " +
                InferenceSafety.sanitize(validation.text, 300)))

            // Review #6: surface the p95 snapshot of the slowest tools.
            val stats = tools.snapshot().take(3)
            if (stats.isNotEmpty()) {
                emit(
                    AgentEvent.Log(
                        "perf p95 — " + stats.joinToString(", ") { "${it.tool} p95=${it.p95Ms}ms" },
                    ),
                )
            }

            emit(AgentEvent.Metrics("total", elapsed(runStart)))

            // Phase 5 (M5 honest outcomes): DONE is EARNED, not assumed.
            val finishIdx = bounded.indexOfFirst { it.tool == "finish" }
            val pendingBeforeFinish = if (finishIdx >= 0) {
                bounded.subList(0, finishIdx).count { it.status == AgentStep.Status.PENDING }
            } else {
                bounded.count { it.status == AgentStep.Status.PENDING }
            }
            val outcome = when {
                rejectedCount > 0 -> CompletionStatus.BLOCKED to
                    "$rejectedCount step(s) rejected by the user"
                failedCount > 0 -> CompletionStatus.PARTIAL to
                    "$failedCount step(s) failed"
                planTruncated -> CompletionStatus.PARTIAL to
                    "plan truncated to $maxSteps steps"
                !reachedFinish || pendingBeforeFinish > 0 -> CompletionStatus.PARTIAL to
                    if (!reachedFinish) "plan ended without its finish step"
                    else "$pendingBeforeFinish planned step(s) never ran"
                else -> when (ReviewClassifier.classify(validation.text)) {
                    ReviewClassifier.Verdict.APPROVED -> CompletionStatus.DONE to ""
                    ReviewClassifier.Verdict.CHANGES_NEEDED -> CompletionStatus.VERIFY_FAILED to
                        "reviewer requested changes"
                    ReviewClassifier.Verdict.INCONCLUSIVE -> CompletionStatus.PARTIAL to
                        "reviewer gave no verdict — results are unverified"
                }
            }
            val (status, reason) = outcome
            val finalSummary = buildString {
                append(summary.ifBlank { "task finished" })
                if (reason.isNotBlank()) append(" — ").append(reason)
            }
            emit(AgentEvent.Completed(finalSummary, validation.source.name, status))
            emit(AgentEvent.PhaseChanged(status.toPhase()))
            // Phase 5 resumability policy: only a fully DONE run clears the
            // snapshot — a partial/blocked/unverified run stays resumable so
            // the user can fix the blocker and retry exactly the steps that
            // did not succeed.
            if (status == CompletionStatus.DONE) {
                resumeStore?.clear()
            } else {
                persistSnapshot(bounded, task, "ACTING", runStart)
                emit(AgentEvent.Log("run kept resumable (status: ${status.name.lowercase()}))"))
            }
        } catch (t: Throwable) {
            emit(AgentEvent.Failed(t.message ?: t.javaClass.simpleName))
            emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
        } finally {
            _running.value = false
        }
    }

    // ------------------------------------------------------------------ plan
    private suspend fun plan(goal: String): List<AgentStep> {
        val repoMap = context.repoMap()
        val raw = router.completeWithFallback(
            LlmRequest(
                messages = listOf(
                    PromptTemplates.planSystem(repoMap),
                    PromptTemplates.planUser(goal, repoMap),
                ),
                maxTokens = 1024,
                temperature = 0.1f,
            ),
            TaskKind.PLAN,
        )
        emit(AgentEvent.Log("plan via ${raw.source.name.lowercase()}: " +
            InferenceSafety.sanitize(raw.text, 200)))
        // A typed failure must never reach PlanParser as if it were a plan.
        if (InferenceSafety.isFailureText(raw.text)) return emptyList()
        return PlanParser.parse(InferenceSafety.sanitize(raw.text))
    }

    // ------------------------------------------------------------------ resume
    private fun persistSnapshot(steps: List<AgentStep>, task: AgentTask, phase: String, startedAt: Long) {
        resumeStore?.save(
            RunResumeStore.from(
                steps = steps,
                taskId = task.id,
                goal = task.goal,
                workspaceUri = task.workspaceUri,
                phase = phase,
                startedAt = startedAt,
            ),
        )
    }

    // ------------------------------------------------------------------ utils
    private fun now() = System.currentTimeMillis()

    private fun elapsed(since: Long) = now() - since

    private fun pathArg(step: AgentStep): String? = runCatching {
        val obj = json.parseToJsonElement(step.argsJson) as JsonObject
        obj["path"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun argValue(step: AgentStep, key: String): String = runCatching {
        val obj = json.parseToJsonElement(step.argsJson) as JsonObject
        obj[key]?.jsonPrimitive?.content ?: ""
    }.getOrDefault("")

    /** Shows what a write step will do before asking the user. */
    private fun previewWrite(step: AgentStep): String = when (step.tool) {
        "delete_file" -> "DELETE ${argValue(step, "path")}"
        "run_command" -> "RUN (sandboxed, read-only)\n" + argValue(step, "command").take(600)
        // Phase 5: the patch IS the preview — the user approves exact hunks,
        // not a vague description of an edit.
        "patch_file" -> "PATCH ${argValue(step, "path")}\n" + argValue(step, "diff").take(1200)
        else -> "WRITE ${argValue(step, "path")}\n" + argValue(step, "content").take(600)
    }

    /**
     * Phase 6 (data durability, resume verification): compare every pending
     * write step against the DURABLE journal — full fingerprints, not names.
     *
     * - current == journal fingerprintAfter  -> the write/deletion ALREADY
     *   landed before the interruption: mark the step DONE so the loop
     *   skips it (no duplicate execution).
     * - current == journal fingerprintBefore -> the write never ran: leave
     *   PENDING; the approval gate re-asks and the step re-executes safely.
     * - neither matches -> the file drifted since the interruption: leave
     *   PENDING with an explicit warning; the TOCTOU recheck and the patch
     *   context check will catch any stale mutation attempt.
     */
    private suspend fun verifyResumedWrites(steps: List<AgentStep>) {
        val journal = tools.journal
        for (step in steps) {
            if (!step.isWrite || step.status != AgentStep.Status.PENDING) continue
            val path = pathArg(step) ?: continue
            val entry = journal.lastForStep(path, step.index) ?: continue
            val currentFp = journal.fingerprintOf(tools.peek(path))
            when (currentFp) {
                entry.fingerprintAfter -> {
                    step.status = AgentStep.Status.DONE
                    step.result = "already applied before the interruption " +
                        "(journal-verified) — not re-executed"
                    emit(
                        AgentEvent.Log(
                            "resume check: step ${step.index} (${step.tool} $path) " +
                                "already applied — skipped",
                        ),
                    )
                }
                entry.fingerprintBefore -> Unit // never executed — safe to re-run
                else -> emit(
                    AgentEvent.Log(
                        "resume check: step ${step.index} ($path) differs from both " +
                            "journal states — re-approval will re-evaluate against current content",
                    ),
                )
            }
        }
    }

    private suspend fun emit(e: AgentEvent) = _events.emit(e)

    private companion object {
        const val MAX_STEPS = 24

        /** Per-observation cap before budgeting (review #5). */
        const val OBSERVATION_CHARS = 200

        /** Hard budget for fed-back observations, newest first (review #5). */
        const val OBSERVATION_BUDGET_CHARS = 6_000

        /** Phase 5: hard budget for the unified-diff evidence fed to the reviewer. */
        const val DIFF_EVIDENCE_CHARS = 4_000
    }
}

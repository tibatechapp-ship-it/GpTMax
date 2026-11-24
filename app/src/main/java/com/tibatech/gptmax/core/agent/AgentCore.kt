package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.core.context.ContextBudget
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.ApprovalGate
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
 */
class AgentCore(
    private val router: InferenceRouter,
    private val tools: ToolRegistry,
    private val context: ContextBuilder,
    private val approval: ApprovalGate,
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

    suspend fun run(task: AgentTask) {
        if (_running.value) {
            emit(AgentEvent.Failed("another run is already active"))
            return
        }
        cancelled = false
        _running.value = true
        val runStart = now()
        try {
            emit(AgentEvent.PhaseChanged(AgentPhase.PLANNING))
            val planStart = now()
            val plan = plan(task.goal)
            emit(AgentEvent.Metrics("planning", elapsed(planStart)))
            if (plan.isEmpty()) {
                emit(AgentEvent.Failed("planning produced no usable steps"))
                emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                return
            }
            // Bound agent autonomy: never execute more than maxSteps actions.
            val bounded = plan.take(maxSteps)

            // ---- GATHERING: preload files the plan will read ----------------
            emit(AgentEvent.PhaseChanged(AgentPhase.GATHERING))
            val gatherStart = now()
            val touched = bounded.mapNotNull { pathArg(it) }.distinct()
            val fileContext = context.digest(touched)
            emit(AgentEvent.Metrics("gathering", elapsed(gatherStart)))

            // ---- ACTING ------------------------------------------------------
            emit(AgentEvent.PhaseChanged(AgentPhase.ACTING))
            val executed = mutableListOf<AgentStep>()
            val observations = mutableListOf<String>()
            var summary = ""
            var stopped = false
            for (step in bounded) {
                if (cancelled) {
                    stopped = true
                    break
                }
                if (step.tool == "finish") {
                    summary = argValue(step, "summary").ifBlank { "done" }
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
                        emit(AgentEvent.StepFinished(step))
                        continue
                    }
                }

                val stepStart = now()
                val result = tools.execute(step)
                emit(AgentEvent.Metrics("tool:${step.tool}", elapsed(stepStart)))
                step.result = result.output.take(500)
                step.status = if (result.ok) AgentStep.Status.DONE else AgentStep.Status.FAILED
                executed += step
                emit(AgentEvent.StepFinished(step))
                // Review #5: one bounded observation per executed step;
                // ContextBudget trims the OLDEST ones before validation.
                observations += "${step.tool} ${argValue(step, "path")} -> " +
                    "${step.status.name.lowercase()}: ${result.output.take(OBSERVATION_CHARS)}"
            }
            if (stopped) {
                emit(AgentEvent.Failed("cancelled by user"))
                emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                return
            }

            // ---- VALIDATING ---------------------------------------------------
            emit(AgentEvent.PhaseChanged(AgentPhase.VALIDATING))
            if (cancelled) {
                emit(AgentEvent.Failed("cancelled by user"))
                emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
                return
            }
            val validateStart = now()
            val diffSummary = executed
                .filter { it.isWrite && it.status == AgentStep.Status.DONE }
                .joinToString("\n") { "${it.tool} ${argValue(it, "path")}" }
            // Review #5: old observations must not crowd the context budget —
            // keep the newest ones that fit, newest-first, and feed only those
            // to the validator alongside the change summary.
            val boundedObservations = ContextBudget
                .trimNewestFirst(observations, OBSERVATION_BUDGET_CHARS) { it.length }
                .joinToString("\n")
            val reviewInput = buildString {
                append(diffSummary)
                if (boundedObservations.isNotBlank()) {
                    append("\n\nRecent observations:\n")
                    append(boundedObservations)
                }
            }
            val validation = router.forTask(TaskKind.VALIDATE)
                .complete(LlmRequest(messages = listOf(PromptTemplates.validateSystem(reviewInput))))
            emit(AgentEvent.Metrics("validating", elapsed(validateStart)))
            emit(AgentEvent.Log("review: ${validation.text.take(300)}"))

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
            emit(AgentEvent.Completed(summary.ifBlank { "task finished" }, validation.source.name))
            emit(AgentEvent.PhaseChanged(AgentPhase.DONE))
        } catch (t: Throwable) {
            emit(AgentEvent.Failed(t.message ?: t.javaClass.simpleName))
            emit(AgentEvent.PhaseChanged(AgentPhase.FAILED))
        } finally {
            _running.value = false
        }
    }

    // ------------------------------------------------------------------ plan
    private suspend fun plan(goal: String): List<AgentStep> {
        val client = router.forTask(TaskKind.PLAN)
        val repoMap = context.repoMap()
        val raw = client.complete(
            LlmRequest(
                messages = listOf(
                    PromptTemplates.planSystem(repoMap),
                    PromptTemplates.planUser(goal, repoMap),
                ),
                maxTokens = 1024,
                temperature = 0.1f,
            ),
        )
        emit(AgentEvent.Log("plan via ${raw.source.name}: ${raw.text.take(200)}"))
        return PlanParser.parse(raw.text)
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
        else -> "WRITE ${argValue(step, "path")}\n" + argValue(step, "content").take(600)
    }

    private suspend fun emit(e: AgentEvent) = _events.emit(e)

    private companion object {
        const val MAX_STEPS = 24

        /** Per-observation cap before budgeting (review #5). */
        const val OBSERVATION_CHARS = 200

        /** Hard budget for fed-back observations, newest first (review #5). */
        const val OBSERVATION_BUDGET_CHARS = 6_000
    }
}

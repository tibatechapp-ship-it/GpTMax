package com.tibatech.gptmax.core.agent

/** Phases of the GpTMax agent loop (matches the architecture diagram). */
enum class AgentPhase {
    IDLE, PLANNING, GATHERING, ACTING, VALIDATING, AWAITING_APPROVAL, DONE, FAILED,
}

data class AgentTask(
    val id: String,
    val goal: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Phase 3 (resumability): the workspace the run is pinned to. Persisted
     * with the resume snapshot so a later resume refuses a DIFFERENT
     * project instead of replaying steps against the wrong files.
     */
    val workspaceUri: String? = null,
)

/** One planned action. [isWrite] marks steps that hit the approval gate. */
data class AgentStep(
    val index: Int,
    val tool: String,
    val argsJson: String,
    val description: String,
    val isWrite: Boolean,
) {
    var result: String = ""
    var status: Status = Status.PENDING

    enum class Status { PENDING, RUNNING, DONE, NEEDS_APPROVAL, REJECTED, FAILED }
}

sealed class AgentEvent {
    data class PhaseChanged(val phase: AgentPhase) : AgentEvent()
    data class StepStarted(val step: AgentStep) : AgentEvent()
    data class StepFinished(val step: AgentStep) : AgentEvent()
    data class ApprovalRequired(val step: AgentStep, val diffPreview: String) : AgentEvent()
    data class Token(val text: String) : AgentEvent()
    data class Log(val message: String) : AgentEvent()

    /** Perf measurement (review #6): phase/tool durations in milliseconds. */
    data class Metrics(val label: String, val millis: Long) : AgentEvent()

    /**
     * Phase 3 (resumability): a run resumed from a persisted snapshot.
     * [fromStep] counts the steps already completed, [totalSteps] the plan.
     */
    data class Resumed(val fromStep: Int, val totalSteps: Int) : AgentEvent()

    data class Completed(val summary: String, val source: String) : AgentEvent()
    data class Failed(val error: String) : AgentEvent()
}

/** Parses the plan JSON emitted by the model into typed steps. */
object PlanParser {

    fun parse(raw: String): List<AgentStep> {
        val json = extractJsonArray(raw) ?: return emptyList()
        return runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { it as kotlinx.serialization.json.JsonArray }
            arr.mapIndexed { i, el ->
                val obj = el as kotlinx.serialization.json.JsonObject
                val tool = obj["tool"]?.toString()?.trim('"') ?: ""
                val args = (obj["args"] ?: obj["arguments"])?.toString() ?: "{}"
                val why = obj["why"]?.toString()?.trim('"') ?: tool
                AgentStep(
                    index = i,
                    tool = tool,
                    argsJson = args,
                    description = why,
                    // Review #6: run_command is write-class — it always goes
                    // through the user approval gate before execution.
                    isWrite = tool == "write_file" ||
                        tool == "delete_file" ||
                        tool == "run_command",
                )
            }.filter { it.tool.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** Accepts ```json fences or a bare array anywhere in the output. */
    private fun extractJsonArray(raw: String): String? {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)
        val body = fence?.groupValues?.get(1)?.trim() ?: raw.trim()
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else null
    }
}

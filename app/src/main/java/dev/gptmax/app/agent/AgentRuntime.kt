package dev.gptmax.app.agent

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stable tool contract exposed to the model. Tool implementations remain model-agnostic. */
data class AgentToolSpec(
    val name: String,
    val description: String,
    val requiredArguments: Set<String> = emptySet(),
    val allowedArguments: Set<String> = requiredArguments,
    val risk: ToolRisk,
    val version: Int = 1,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>,
)

enum class ToolExecutionStatus { SUCCESS, FAILURE, DENIED, APPROVAL_REQUIRED, CANCELLED }

data class AgentToolResult(
    val toolName: String,
    val callId: String,
    val status: ToolExecutionStatus,
    val output: String,
    val durationMs: Long = 0,
)

interface AgentTool {
    val spec: AgentToolSpec
    suspend fun execute(call: AgentToolCall, cancellation: AtomicBoolean): AgentToolResult
}

class AgentToolRegistry(tools: Collection<AgentTool>) {
    private val byName = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.size) { "Tool names must be unique." }
        require(byName.keys.all { it.matches(Regex("^[a-z][a-z0-9_.]{1,63}$")) }) {
            "Tool names must use the lowercase identifier format."
        }
    }

    fun get(name: String): AgentTool? = byName[name]
    fun allSpecs(): List<AgentToolSpec> = byName.values.map { it.spec }.sortedBy { it.name }
}

sealed interface AgentModelAction {
    data class CallTool(val call: AgentToolCall) : AgentModelAction
    data class FinalAnswer(val text: String) : AgentModelAction
}

/** Adapter boundary. Local llama.cpp or a remote model provider can implement this. */
interface AgentModel {
    suspend fun nextAction(
        userRequest: String,
        observations: List<AgentObservation>,
        availableTools: List<AgentToolSpec>,
        step: Int,
    ): AgentModelAction
}

data class AgentObservation(
    val role: String,
    val text: String,
)

data class AgentRunLimits(
    val maxToolCalls: Int = 12,
    val maxObservationChars: Int = 12_000,
) {
    init {
        require(maxToolCalls in 1..100)
        require(maxObservationChars in 256..100_000)
    }
}

data class AgentRunResult(
    val status: AgentRunStatus,
    val answer: String,
    val toolResults: List<AgentToolResult>,
    val steps: Int,
)

enum class AgentRunStatus { COMPLETED, BUDGET_EXHAUSTED, CANCELLED, MODEL_ERROR }

class AgentRuntime(
    private val model: AgentModel,
    private val registry: AgentToolRegistry,
    private val limits: AgentRunLimits = AgentRunLimits(),
) {
    suspend fun run(
        request: String,
        grantedCapabilities: Set<ToolRisk>,
        approvedCallIds: Set<String> = emptySet(),
        cancellation: AtomicBoolean = AtomicBoolean(false),
        approvalRequest: suspend (AgentToolCall, AgentToolSpec) -> Boolean = { _, _ -> false },
        onToolResult: suspend (AgentToolResult) -> Unit = {},
    ): AgentRunResult {
        require(request.isNotBlank()) { "Task request must not be blank." }
        val observations = mutableListOf<AgentObservation>()
        val results = mutableListOf<AgentToolResult>()
        var calls = 0

        while (calls < limits.maxToolCalls) {
            if (cancellation.get()) {
                return AgentRunResult(AgentRunStatus.CANCELLED, "Task cancelled.", results, calls)
            }

            val action = try {
                model.nextAction(request, observations.toList(), registry.allSpecs(), calls)
            } catch (error: Exception) {
                if (cancellation.get()) {
                    return AgentRunResult(AgentRunStatus.CANCELLED, "Task cancelled.", results, calls)
                }
                return AgentRunResult(
                    AgentRunStatus.MODEL_ERROR,
                    "Model action failed: ${error.message ?: error.javaClass.simpleName}",
                    results,
                    calls,
                )
            }

            if (cancellation.get()) {
                return AgentRunResult(AgentRunStatus.CANCELLED, "Task cancelled.", results, calls)
            }

            when (action) {
                is AgentModelAction.FinalAnswer ->
                    return AgentRunResult(AgentRunStatus.COMPLETED, action.text, results, calls)

                is AgentModelAction.CallTool -> {
                    calls++
                    val result = executeToolCall(
                        action.call,
                        grantedCapabilities,
                        approvedCallIds,
                        cancellation,
                        approvalRequest,
                    )
                    results += result
                    runCatching { onToolResult(result) }
                    val safeOutput = result.output.take(limits.maxObservationChars)
                    observations += AgentObservation(
                        role = "tool",
                        text = "Tool ${action.call.name} status=${result.status}\n$safeOutput",
                    )
                }
            }
        }

        return AgentRunResult(
            AgentRunStatus.BUDGET_EXHAUSTED,
            "Tool-call budget reached; stopped to prevent an unbounded agent loop.",
            results,
            calls,
        )
    }

    private suspend fun executeToolCall(
        call: AgentToolCall,
        grantedCapabilities: Set<ToolRisk>,
        approvedCallIds: Set<String>,
        cancellation: AtomicBoolean,
        approvalRequest: suspend (AgentToolCall, AgentToolSpec) -> Boolean,
    ): AgentToolResult {
        val tool = registry.get(call.name)
            ?: return AgentToolResult(call.name, call.id, ToolExecutionStatus.DENIED, "Unknown tool.")
        validateArguments(tool.spec, call)?.let { error ->
            return AgentToolResult(call.name, call.id, ToolExecutionStatus.FAILURE, error)
        }

        val initiallyApproved = call.id in approvedCallIds
        val initialDecision = ToolPermissionPolicy.decide(
            tool.spec.risk,
            grantedCapabilities,
            initiallyApproved,
        )
        if (initialDecision == ToolDecision.DENY_MISSING_CAPABILITY) {
            return AgentToolResult(
                call.name,
                call.id,
                ToolExecutionStatus.DENIED,
                "Capability not granted for this task.",
            )
        }

        val approved = initiallyApproved || (
            initialDecision == ToolDecision.REQUIRE_USER_APPROVAL &&
                runCatching { approvalRequest(call, tool.spec) }.getOrDefault(false)
            )
        return when (ToolPermissionPolicy.decide(tool.spec.risk, grantedCapabilities, approved)) {
            ToolDecision.DENY_MISSING_CAPABILITY ->
                AgentToolResult(call.name, call.id, ToolExecutionStatus.DENIED,
                    "Capability not granted for this task.")
            ToolDecision.REQUIRE_USER_APPROVAL ->
                AgentToolResult(call.name, call.id, ToolExecutionStatus.APPROVAL_REQUIRED,
                    "User approval required for this action.")
            ToolDecision.ALLOW -> try {
                withContext(Dispatchers.IO) { tool.execute(call, cancellation) }
            } catch (error: Exception) {
                AgentToolResult(call.name, call.id, ToolExecutionStatus.FAILURE,
                    "Tool failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun validateArguments(spec: AgentToolSpec, call: AgentToolCall): String? {
        if (call.id.isBlank() || call.id.length > 128) return "Invalid tool call id."
        if (!call.arguments.keys.containsAll(spec.requiredArguments)) return "Missing required tool arguments."
        if (call.arguments.keys.any { it !in spec.allowedArguments }) return "Unexpected tool argument."
        if (call.arguments.any { (key, value) ->
                value.length > (if (key == "content") 128 * 1024 else 16_000)
            }) {
            return "Tool argument exceeds size limit."
        }
        return null
    }
}

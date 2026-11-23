package dev.gptmax.app.agent

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun executesRegisteredReadToolThenReturnsModelAnswer() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool()
        val model = FakeModel(
            listOf(
                AgentModelAction.CallTool(AgentToolCall("c1", "fake.read", emptyMap())),
                AgentModelAction.FinalAnswer("Verified result."),
            ),
        )
        val runtime = AgentRuntime(model, AgentToolRegistry(listOf(tool)))
        val result = runtime.run("Inspect project", setOf(ToolRisk.READ_ONLY))

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals("Verified result.", result.answer)
        assertEquals(1, result.toolResults.size)
        assertEquals(ToolExecutionStatus.SUCCESS, result.toolResults.single().status)
    }

    @Test
    fun missingCapabilityPreventsToolExecution() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool(risk = ToolRisk.WORKSPACE_WRITE)
        val model = FakeModel(
            listOf(
                AgentModelAction.CallTool(AgentToolCall("write-1", "fake.read", emptyMap())),
                AgentModelAction.FinalAnswer("Done"),
            ),
        )
        val result = AgentRuntime(model, AgentToolRegistry(listOf(tool)))
            .run("Write file", emptySet())

        assertEquals(ToolExecutionStatus.DENIED, result.toolResults.single().status)
        assertEquals(0, tool.executions)
    }

    @Test
    fun approvalRequiredToolDoesNotExecuteWithoutApproval() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool(risk = ToolRisk.PROCESS_EXECUTION)
        val model = FakeModel(
            listOf(
                AgentModelAction.CallTool(AgentToolCall("process-1", "fake.read", emptyMap())),
                AgentModelAction.FinalAnswer("Stopped"),
            ),
        )
        val result = AgentRuntime(model, AgentToolRegistry(listOf(tool)))
            .run("Run command", setOf(ToolRisk.PROCESS_EXECUTION))

        assertEquals(ToolExecutionStatus.APPROVAL_REQUIRED, result.toolResults.single().status)
        assertEquals(0, tool.executions)
    }

    @Test
    fun explicitApprovalAllowsRiskyToolExecution() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool(risk = ToolRisk.PROCESS_EXECUTION)
        val model = FakeModel(
            listOf(
                AgentModelAction.CallTool(AgentToolCall("approved-1", "fake.read", emptyMap())),
                AgentModelAction.FinalAnswer("Executed with approval."),
            ),
        )
        var asked = false
        val result = AgentRuntime(model, AgentToolRegistry(listOf(tool)))
            .run(
                "Run command",
                setOf(ToolRisk.PROCESS_EXECUTION),
                approvalRequest = { call, spec ->
                    asked = call.id == "approved-1" && spec.risk == ToolRisk.PROCESS_EXECUTION
                    asked
                },
            )

        assertTrue(asked)
        assertEquals(ToolExecutionStatus.SUCCESS, result.toolResults.single().status)
        assertEquals(1, tool.executions)
    }

    @Test
    fun stopsAtConfiguredToolCallBudget() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool()
        val model = FakeModel(List(4) {
            AgentModelAction.CallTool(AgentToolCall("call-$it", "fake.read", emptyMap()))
        })
        val result = AgentRuntime(
            model,
            AgentToolRegistry(listOf(tool)),
            AgentRunLimits(maxToolCalls = 2),
        ).run("Keep inspecting", setOf(ToolRisk.READ_ONLY))

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(2, result.toolResults.size)
    }

    @Test
    fun cancellationStopsBeforeNextModelAction() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool()
        val cancellation = AtomicBoolean(true)
        val result = AgentRuntime(FakeModel(emptyList()), AgentToolRegistry(listOf(tool)))
            .run("Cancelled", setOf(ToolRisk.READ_ONLY), cancellation = cancellation)

        assertEquals(AgentRunStatus.CANCELLED, result.status)
    }

    private class FakeModel(private val actions: List<AgentModelAction>) : AgentModel {
        private var index = 0
        override suspend fun nextAction(
            userRequest: String,
            observations: List<AgentObservation>,
            availableTools: List<AgentToolSpec>,
            step: Int,
        ): AgentModelAction = actions.getOrElse(index++) {
            AgentModelAction.FinalAnswer("No further action.")
        }
    }

    private class FakeTool(
        private val risk: ToolRisk = ToolRisk.READ_ONLY,
    ) : AgentTool {
        var executions = 0
        override val spec = AgentToolSpec(
            name = "fake.read",
            description = "Test tool",
            risk = risk,
        )
        override suspend fun execute(
            call: AgentToolCall,
            cancellation: AtomicBoolean,
        ): AgentToolResult {
            executions++
            return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS, "test output")
        }
    }
}

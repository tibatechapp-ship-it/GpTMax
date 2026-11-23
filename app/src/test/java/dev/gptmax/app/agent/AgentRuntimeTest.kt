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

    @Test
    fun observationBudgetDropsOldestObservationsFirst() = kotlinx.coroutines.runBlocking {
        val tool = FakeTool(output = { call -> "payload-${call.id}-" + "x".repeat(800) })
        val model = RecordingModel(
            List(4) {
                AgentModelAction.CallTool(AgentToolCall("call-$it", "fake.read", emptyMap()))
            } + AgentModelAction.FinalAnswer("done"),
        )
        val result = AgentRuntime(
            model,
            AgentToolRegistry(listOf(tool)),
            AgentRunLimits(maxToolCalls = 5, maxObservationChars = 1_000, maxObservationHistoryChars = 1_600),
        ).run("Budget test", setOf(ToolRisk.READ_ONLY))

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        val observations = model.lastObservations
        assertTrue("expected an oldest-dropped marker", observations.first().text.contains("dropped"))
        assertTrue("newest observation must survive", observations.last().text.contains("call-3"))
        assertTrue("oldest observation must be dropped", observations.none { it.text.contains("call-0") })
    }

    @Test
    fun runResultCarriesTotalDuration() = kotlinx.coroutines.runBlocking {
        val result = AgentRuntime(
            FakeModel(listOf(AgentModelAction.FinalAnswer("ok"))),
            AgentToolRegistry(emptyList()),
        ).run("Timing", setOf(ToolRisk.READ_ONLY))

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertTrue(result.durationMs >= 0)
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

    private class RecordingModel(private val actions: List<AgentModelAction>) : AgentModel {
        var lastObservations: List<AgentObservation> = emptyList()
        private var index = 0
        override suspend fun nextAction(
            userRequest: String,
            observations: List<AgentObservation>,
            availableTools: List<AgentToolSpec>,
            step: Int,
        ): AgentModelAction {
            lastObservations = observations
            return actions.getOrElse(index++) {
                AgentModelAction.FinalAnswer("No further action.")
            }
        }
    }

    private class FakeTool(
        private val risk: ToolRisk = ToolRisk.READ_ONLY,
        private val output: (AgentToolCall) -> String = { "test output" },
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
            return AgentToolResult(spec.name, call.id, ToolExecutionStatus.SUCCESS, output(call))
        }
    }
}

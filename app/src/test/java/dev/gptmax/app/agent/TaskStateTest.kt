package dev.gptmax.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateTest {
    @Test
    fun validCodingTaskLifecycleIsAllowed() {
        val path = listOf(
            TaskState.CREATED,
            TaskState.PLANNING,
            TaskState.INSPECTING,
            TaskState.READY_TO_EDIT,
            TaskState.EDITING,
            TaskState.VALIDATING,
            TaskState.REVIEWING,
            TaskState.AWAITING_APPROVAL,
            TaskState.COMPLETED,
        )

        path.zipWithNext().forEach { (current, next) ->
            assertTrue("$current should transition to $next", current.canTransitionTo(next))
        }
    }

    @Test
    fun validationCanEnterBoundedRepairCycle() {
        assertTrue(TaskState.VALIDATING.canTransitionTo(TaskState.REPAIRING))
        assertTrue(TaskState.REPAIRING.canTransitionTo(TaskState.EDITING))
        assertFalse(TaskState.REPAIRING.canTransitionTo(TaskState.PLANNING))
    }

    @Test
    fun terminalStatesCannotBeReopened() {
        assertFalse(TaskState.COMPLETED.canTransitionTo(TaskState.EDITING))
        assertFalse(TaskState.FAILED.canTransitionTo(TaskState.PLANNING))
        assertFalse(TaskState.CANCELLED.canTransitionTo(TaskState.INSPECTING))
    }

    @Test
    fun activeTaskCanBeCancelledOrFailed() {
        assertTrue(TaskState.EDITING.canTransitionTo(TaskState.CANCELLED))
        assertTrue(TaskState.VALIDATING.canTransitionTo(TaskState.FAILED))
    }
}

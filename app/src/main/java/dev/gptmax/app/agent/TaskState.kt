package dev.gptmax.app.agent

/**
 * Explicit task lifecycle for agent orchestration.
 *
 * This state model does not execute tools; it prevents the coordinator from
 * silently skipping required phases or reviving terminal tasks.
 */
enum class TaskState {
    CREATED,
    PLANNING,
    INSPECTING,
    READY_TO_EDIT,
    EDITING,
    VALIDATING,
    REPAIRING,
    REVIEWING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    fun canTransitionTo(next: TaskState): Boolean {
        if (this == next || isTerminal()) return false
        if (next == CANCELLED || next == FAILED) return true

        return next in allowedTransitions[this].orEmpty()
    }

    fun isTerminal(): Boolean = this in setOf(COMPLETED, FAILED, CANCELLED)

    companion object {
        private val allowedTransitions: Map<TaskState, Set<TaskState>> = mapOf(
            CREATED to setOf(PLANNING),
            PLANNING to setOf(INSPECTING),
            INSPECTING to setOf(READY_TO_EDIT, REVIEWING),
            READY_TO_EDIT to setOf(EDITING),
            EDITING to setOf(VALIDATING),
            VALIDATING to setOf(REPAIRING, REVIEWING),
            REPAIRING to setOf(EDITING, VALIDATING),
            REVIEWING to setOf(AWAITING_APPROVAL, EDITING),
            AWAITING_APPROVAL to setOf(COMPLETED, EDITING),
        )
    }
}

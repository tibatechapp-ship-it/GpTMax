package dev.gptmax.app.agent

/** Keeps model instructions aligned with the tools actually registered for this workspace. */
internal object AgentToolGuidance {
    fun lines(availableToolNames: Set<String>): List<String> = buildList {
        if ("workspace.overview" in availableToolNames) {
            add("For repository-wide tasks, call workspace.overview first; then inspect relevant files and tests before proposing or editing.")
        }
        if ("git.workspace" in availableToolNames) {
            add("Before editing, inspect Git state with git.workspace action=status; use action=diff to review change summaries. This tool is read-only.")
        }
        if ("context.recall" in availableToolNames) {
            add("For repository work, call context.recall with a focused query before planning. Treat snippets as hints and re-read current files before editing.")
        }
        if ("context.index" in availableToolNames) {
            add("Call context.index if the project context index is missing or stale.")
        }
        if ("context.remember" in availableToolNames) {
            add("Save stable architecture decisions with context.remember. Never store secrets or raw source files in memory.")
        }
        if ("context.checkpoint" in availableToolNames) {
            add("Save meaningful progress and a precise next_step with context.checkpoint before ending long work.")
        }
        if ("context.restore" in availableToolNames) {
            add("Use context.restore with the task_id from the latest checkpoint when continuing a prior task.")
        }
        if ("context.stage" in availableToolNames) {
            add("Mark important phases with context.stage.")
        }
        if (availableToolNames.any { it.startsWith("context.") }) {
            add("The current project context is isolated to the selected workspace.")
        }
    }
}

package com.tibatech.gptmax.core.prompts

import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.router.ChatMessage

/**
 * System prompts for the agent loop: Plan -> Context -> Act -> Validate ->
 * Review (human approval gate on writes). English prompts on purpose —
 * coding models score best on them; user-facing strings stay localized.
 */
object PromptTemplates {

    const val TOOLS_HINT = """Available tools:
- list_dir(path)          -> file listing
- read_file(path)         -> file content
- write_file(path, content) -> create/overwrite a file (requires user approval)
- delete_file(path)       -> delete a file (requires user approval)
- grep(pattern)           -> search the workspace
- finish(summary)         -> end the task"""

    fun chatSystem(): String =
        "You are GpTMax, a local-first AI coding studio running on Android. " +
            "Answer concisely, prefer working code over prose, and use Markdown code fences."

    fun planSystem(repoMap: String): ChatMessage = ChatMessage(
        role = "system",
        content = """
            You are the planning brain of an autonomous coding agent on Android.
            Given a user goal and a repository map, produce a JSON array of steps.
            $TOOLS_HINT

            Rules:
            - Output ONLY a JSON array, no prose, no markdown fences.
            - Each element: {"tool": string, "args": object, "why": string}
            - Prefer the fewest steps that fully satisfy the goal.
            - Read steps (list_dir/read_file/grep) run automatically;
              write steps (write_file/delete_file) require user approval.
            - End with a finish step summarizing the change.
        """.trimIndent(),
    )

    fun planUser(goal: String, repoMap: String): ChatMessage = ChatMessage(
        role = "user",
        content = "Goal: $goal\n\nRepository map:\n$repoMap",
    )

    fun actSystem(step: AgentStep, fileContext: String): ChatMessage = ChatMessage(
        role = "system",
        content = """
            You are executing one step of a coding plan.
            $TOOLS_HINT
            Current step: ${step.description}
            Relevant files:
            $fileContext
            Respond with the tool call arguments only, as compact JSON.
        """.trimIndent(),
    )

    fun validateSystem(diffSummary: String): ChatMessage = ChatMessage(
        role = "system",
        content = """
            You are a strict code reviewer. Review the following change summary
            produced by an autonomous agent. Point out concrete risks (broken
            imports, syntax errors, missing tests, security issues). If the
            change looks safe, say APPROVED and one short reason.

            Change summary:
            $diffSummary
        """.trimIndent(),
    )
}

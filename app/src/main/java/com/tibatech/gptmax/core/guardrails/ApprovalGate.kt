package com.tibatech.gptmax.core.guardrails

import com.tibatech.gptmax.core.agent.AgentStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Human-in-the-loop gate: every write step (write_file / delete_file)
 * suspends the agent until the user approves or rejects in the UI.
 * This is the "Review + approval gate" node of the architecture diagram.
 */
class ApprovalGate(private val autoApprove: suspend () -> Boolean) {

    data class ApprovalRequest(
        val step: AgentStep,
        val diffPreview: String,
    )

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private var continuation: kotlin.coroutines.Continuation<Boolean>? = null

    /**
     * Suspends until the user responds, or returns true when auto-approve
     * is on. Phase 6 (external review): auto-approve NEVER covers command
     * execution — run_command always requires an explicit human decision,
     * because running external processes is the one irreversible action
     * whose effects no journal can roll back.
     */
    suspend fun request(step: AgentStep, diffPreview: String): Boolean {
        val auto = runCatching { autoApprove() }.getOrDefault(false)
        if (auto && step.tool != "run_command") return true
        return suspendCancellableCoroutine { cont ->
            continuation = cont
            _pending.value = ApprovalRequest(step, diffPreview)
            cont.invokeOnCancellation {
                continuation = null
                _pending.value = null
            }
        }
    }

    /** Called by the UI (Approve / Reject buttons on the approval card). */
    fun respond(approved: Boolean) {
        val cont = continuation
        continuation = null
        _pending.value = null
        cont?.resumeWith(Result.success(approved))
    }

    /**
     * Phase 1 (external review): a user-driven cancel must END an in-flight
     * wait, not leave the agent hanging on a card nobody will answer.
     * The suspended request() resumes with `false`; the caller checks its
     * cancelled flag right after the gate and aborts the run instead of
     * executing the step. Idempotent — a no-op when nothing is pending.
     */
    fun cancelPending() {
        val cont = continuation
        continuation = null
        _pending.value = null
        cont?.resumeWith(Result.success(false))
    }
}

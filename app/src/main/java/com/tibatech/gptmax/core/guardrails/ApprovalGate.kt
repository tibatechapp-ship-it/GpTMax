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

    /** Suspends until the user responds, or returns true when auto-approve is on. */
    suspend fun request(step: AgentStep, diffPreview: String): Boolean {
        if (runCatching { autoApprove() }.getOrDefault(false)) return true
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
}

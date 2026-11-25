package com.tibatech.gptmax.core.agent

import java.util.UUID

/**
 * Phase 1 (external review, high severity): a session is pinned to ONE
 * workspace. Chat history and agent runs against project A must never leak
 * into project B just because the user granted a different folder mid-flight.
 *
 * [bind] returns the session id for the CURRENT workspace: the same workspace
 * keeps the same session; a workspace change mints a fresh one, so the next
 * message starts a clean history bound to the new project instead of
 * appending to the previous project's thread.
 *
 * Pure Kotlin: unit-testable on the JVM without Android.
 */
class SessionPinner {

    private var pinned: String? = null
    private var sessionId: String = NO_WORKSPACE_SESSION

    val currentSessionId: String get() = sessionId

    val pinnedWorkspace: String? get() = pinned

    /** Returns the session id bound to [workspaceUri]; mints a new one on change. */
    fun bind(workspaceUri: String?): String {
        if (workspaceUri != pinned) {
            pinned = workspaceUri
            sessionId = if (workspaceUri.isNullOrBlank()) NO_WORKSPACE_SESSION else newSessionId()
        }
        return sessionId
    }

    companion object {
        /** Sessions before any workspace is granted share one local id. */
        const val NO_WORKSPACE_SESSION = "main"

        fun newSessionId(): String = "ws-" + UUID.randomUUID().toString()
    }
}

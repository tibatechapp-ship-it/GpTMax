package dev.gptmax.app.agent

/**
 * Capability/risk gate used before a tool is dispatched.
 * A grant alone is insufficient for high-impact operations: approval is also
 * required for execution, network, destructive, and publish risks.
 */
enum class ToolRisk {
    READ_ONLY,
    WORKSPACE_WRITE,
    PROCESS_EXECUTION,
    NETWORK,
    DESTRUCTIVE,
    PUBLISH,
}

enum class ToolDecision {
    ALLOW,
    DENY_MISSING_CAPABILITY,
    REQUIRE_USER_APPROVAL,
}

object ToolPermissionPolicy {
    private val approvalRequired = setOf(
        ToolRisk.PROCESS_EXECUTION,
        ToolRisk.NETWORK,
        ToolRisk.DESTRUCTIVE,
        ToolRisk.PUBLISH,
    )

    fun decide(
        risk: ToolRisk,
        grantedCapabilities: Set<ToolRisk>,
        userApprovedThisAction: Boolean,
    ): ToolDecision {
        if (risk !in grantedCapabilities) {
            return ToolDecision.DENY_MISSING_CAPABILITY
        }
        if (risk in approvalRequired && !userApprovedThisAction) {
            return ToolDecision.REQUIRE_USER_APPROVAL
        }
        return ToolDecision.ALLOW
    }
}

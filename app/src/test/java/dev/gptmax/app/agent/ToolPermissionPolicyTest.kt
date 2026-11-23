package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPermissionPolicyTest {
    @Test
    fun missingCapabilityIsDenied() {
        assertEquals(
            ToolDecision.DENY_MISSING_CAPABILITY,
            ToolPermissionPolicy.decide(ToolRisk.WORKSPACE_WRITE, emptySet(), false),
        )
    }

    @Test
    fun processExecutionRequiresExplicitActionApproval() {
        val grants = setOf(ToolRisk.PROCESS_EXECUTION)
        assertEquals(
            ToolDecision.REQUIRE_USER_APPROVAL,
            ToolPermissionPolicy.decide(ToolRisk.PROCESS_EXECUTION, grants, false),
        )
        assertEquals(
            ToolDecision.ALLOW,
            ToolPermissionPolicy.decide(ToolRisk.PROCESS_EXECUTION, grants, true),
        )
    }

    @Test
    fun grantedReadOnlyToolDoesNotRequirePerActionApproval() {
        assertEquals(
            ToolDecision.ALLOW,
            ToolPermissionPolicy.decide(ToolRisk.READ_ONLY, setOf(ToolRisk.READ_ONLY), false),
        )
    }
}

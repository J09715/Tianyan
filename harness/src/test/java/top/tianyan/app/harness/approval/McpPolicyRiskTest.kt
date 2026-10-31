package top.tianyan.app.harness.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.harness.ApprovalDecision

class McpPolicyRiskTest {
    @Test
    fun `override replaces base risk without dropping approval`() {
        val decision = applyMcpRiskOverride(ApprovalDecision(true, "high", "base"), "low", null)
        assertEquals("low", decision.riskLevel)
        assertTrue(decision.required)
    }

    @Test
    fun `destructive annotation still escalates over local override`() {
        val decision = applyMcpRiskOverride(
            ApprovalDecision(true, "high", "base"),
            "low",
            McpToolAnnotations(destructiveHint = true),
        )
        assertEquals("high", decision.riskLevel)
    }

    @Test
    fun `invalid override is ignored`() {
        val decision = applyMcpRiskOverride(ApprovalDecision(true, "high", "base"), "bogus", null)
        assertEquals("high", decision.riskLevel)
    }

    @Test
    fun `non-required decision is untouched`() {
        val decision = applyMcpRiskOverride(ApprovalDecision(false, "low"), "critical", null)
        assertFalse(decision.required)
        assertEquals("low", decision.riskLevel)
    }
}

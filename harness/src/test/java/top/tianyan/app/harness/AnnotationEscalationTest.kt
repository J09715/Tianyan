package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.harness.approval.AnnotationEscalation

/**
 * MCP 注解升级（escalation-only）合同：
 * 只允许显式声明抬高审批等级，绝不放低；缺省 hint 不参与（存量服务零回归）。
 */
class AnnotationEscalationTest {

    @Test
    fun `null annotations leave decisions untouched`() {
        val base = ApprovalDecision(true, "high", "r", "s")
        assertEquals(base, AnnotationEscalation.escalate(base, null))
        assertEquals("high", AnnotationEscalation.escalatedRisk("high", null))
    }

    @Test
    fun `destructive hint raises to at least high`() {
        assertEquals("high", AnnotationEscalation.escalatedRisk("low", McpToolAnnotations(destructiveHint = true)))
        assertEquals("high", AnnotationEscalation.escalatedRisk("normal", McpToolAnnotations(destructiveHint = true)))
        assertEquals("high", AnnotationEscalation.escalatedRisk("medium", McpToolAnnotations(destructiveHint = true)))
        // 已是 high/critical 的判定不被降级也不重复升级
        assertEquals("critical", AnnotationEscalation.escalatedRisk("critical", McpToolAnnotations(destructiveHint = true)))
    }

    @Test
    fun `open world hint raises to at least medium unless declared read only`() {
        assertEquals("medium", AnnotationEscalation.escalatedRisk("normal", McpToolAnnotations(openWorldHint = true)))
        // 只读声明压制 openWorld 升级（声明自洽时信任只读语义，但仍不降级本地判定）
        assertEquals("normal", AnnotationEscalation.escalatedRisk("normal", McpToolAnnotations(openWorldHint = true, readOnlyHint = true)))
        // false 显式声明同样不升级
        assertEquals("normal", AnnotationEscalation.escalatedRisk("normal", McpToolAnnotations(openWorldHint = false)))
    }

    @Test
    fun `escalate annotates reason only when risk actually raised`() {
        val raised = AnnotationEscalation.escalate(
            ApprovalDecision(true, "medium", "浏览器操作会改变页面状态。", "s"),
            McpToolAnnotations(destructiveHint = true),
        )
        assertEquals("high", raised.riskLevel)
        assertTrue(raised.reason.contains("注解"))

        val untouched = AnnotationEscalation.escalate(
            ApprovalDecision(true, "high", "r", "s"),
            McpToolAnnotations(destructiveHint = true),
        )
        assertEquals("high", untouched.riskLevel)
        assertFalse(untouched.reason.contains("注解"))

        // 非 required 判定（免审）不受注解影响——注解永不创造审批需求，只抬高既有需求的等级
        val free = AnnotationEscalation.escalate(ApprovalDecision(false), McpToolAnnotations(destructiveHint = true))
        assertFalse(free.required)
    }

    @Test
    fun `unknown risk levels are treated as most severe and never lowered`() {
        assertEquals("weird", AnnotationEscalation.escalatedRisk("weird", McpToolAnnotations(destructiveHint = true)))
    }
}

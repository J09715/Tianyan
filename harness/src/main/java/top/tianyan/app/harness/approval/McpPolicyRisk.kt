package top.tianyan.app.harness.approval

import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.core.model.McpToolPolicy
import top.tianyan.app.harness.ApprovalDecision

/**
 * 应用本地用户对单个 MCP 工具的风险覆盖。
 *
 * 覆盖先替换基础等级，再交给 [AnnotationEscalation]：本地可降级，但服务端声明的
 * destructive / openWorld 仍会把等级抬回去（注解只升不降的边界不变）。
 * 只调整风险等级，不改变 required 判定。
 */
internal fun applyMcpRiskOverride(
    decision: ApprovalDecision,
    override: String?,
    annotations: McpToolAnnotations?,
): ApprovalDecision {
    val normalized = McpToolPolicy.normalizeRisk(override) ?: return decision
    if (!decision.required) return decision
    val risk = AnnotationEscalation.escalatedRisk(normalized, annotations)
    return decision.copy(
        riskLevel = risk,
        reason = decision.reason + "（已应用用户为该工具设置的风险覆盖：$risk。）",
    )
}

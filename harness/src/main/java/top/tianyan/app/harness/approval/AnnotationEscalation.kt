package top.tianyan.app.harness.approval

import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.harness.ApprovalDecision

/**
 * MCP 注解升级（escalation-only）：
 * 外部服务通过 tool.annotations 自述风险特征，**未经本端验证**——因此只允许把
 * 审批等级往高处修（destructive → 至少 high；openWorld 且非只读 → 至少 medium），
 * 绝不允许把本地矩阵的判定往低处放（降级只能来自本地可信规则，如内置浏览器
 * 风险矩阵）。缺省 hint（null）不参与升级，避免对存量服务的行为回归；
 * 只采信显式 `true`。
 *
 * 升级同时影响「本会话内记住」的可用性：riskLevel 达到 high/critical 的操作
 * 不可被 SessionApprovalGrants 记住，声明了破坏性的工具因此无法被一揽子免审。
 */
internal object AnnotationEscalation {

    private val RANK = listOf("low", "normal", "medium", "high", "critical")

    /** 返回升级后的审批等级；annotations 为 null 或无显式声明时原样返回。 */
    fun escalatedRisk(baseRisk: String, annotations: McpToolAnnotations?): String {
        if (annotations == null) return baseRisk
        var risk = baseRisk
        if (annotations.destructiveHint == true && rank(risk) < rank("high")) risk = "high"
        if (annotations.openWorldHint == true && annotations.readOnlyHint != true && rank(risk) < rank("medium")) {
            risk = "medium"
        }
        return risk
    }

    /** 对 required 的判定应用注解升级；等级被抬高时在 reason 中注明依据。 */
    fun escalate(decision: ApprovalDecision, annotations: McpToolAnnotations?): ApprovalDecision {
        if (!decision.required) return decision
        val risk = escalatedRisk(decision.riskLevel, annotations)
        if (risk == decision.riskLevel) return decision
        return decision.copy(
            riskLevel = risk,
            reason = decision.reason + "（服务端注解声明了更高的风险特征，已按声明升级审批等级；该声明未经本端验证。）",
        )
    }

    private fun rank(risk: String): Int = RANK.indexOf(risk).let { if (it < 0) RANK.size - 1 else it }
}

package top.tianyan.app.harness.approval

import java.util.UUID
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.harness.ApprovalPolicyEngine
import top.tianyan.app.harness.AskUserQuestions
import top.tianyan.app.harness.ToolResult

/** Timeout defaults answer questions only; they never approve an expired tool invocation. */
internal suspend fun expiredRequestResult(
    request: AgentApprovalRequestEntity,
    now: Long,
    policy: ApprovalResumePolicy,
): ToolResult {
    val isQuestion = request.toolName == AskUserQuestions.TOOL_NAME
    val invalid = if (isQuestion) policy.invalidationOf(request, now, automaticQuestionTimeout = true) else null
    val fallback = if (isQuestion && invalid == null) runCatching { AskUserQuestions.timeoutOutput(request.argumentsJson) }.getOrNull() else null
    return ToolResult(
        id = UUID.randomUUID().toString(), createdAt = now, toolCallId = request.toolCallId,
        success = fallback != null,
        output = fallback ?: if (isQuestion) {
            "提问已超时且默认答案不可用：${invalid ?: "问题参数损坏"}。请根据现有有效上下文继续，不要重复等待。"
        } else policy.invalidationResultMessage("该审批已过期（超过 ${ApprovalPolicyEngine.APPROVAL_TTL_MS / 60_000L} 分钟未处理）"),
    )
}

package top.tianyan.app.harness

import java.util.UUID
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.harness.core.ToolBackend

data class AskUserRequest(
    val toolCall: ToolCall,
    val sessionId: String,
    val workspace: String,
    val operationId: String?,
    /** 子智能体 Lane 没有可暂停的问题 UI，为 false 时直接采用推荐项继续。 */
    val allowApprovalRequest: Boolean,
    val now: Long,
)

/**
 * Agent 工具后端：ask_user 向用户提问。
 *
 * ask_user 是向用户提问的动作本身，不走审批门控（策略引擎对它亦豁免）：
 * 校验参数 → 落一条 pending 请求（复用审批请求的暂停/恢复管道）→ 返回
 * awaitingApproval 让整轮暂停；UI 渲染问题卡，答案由 HarnessLoop.resolveQuestion
 * 直接作为工具结果落库并续跑，无需重执行。
 *
 * 后台子智能体 Lane 不支持暂停：直接采用推荐答案，不创建悬挂请求。
 */
class AskUserToolBackend(
    private val approvalPolicyEngine: ApprovalPolicyEngine,
    private val approvalRepository: AgentApprovalRepository? = null,
) : ToolBackend<AskUserRequest, ToolResult> {

    override suspend fun execute(request: AskUserRequest): ToolResult {
        fun failure(output: String) = ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = request.now,
            toolCallId = request.toolCall.id,
            success = false,
            output = output,
        )
        if (request.sessionId.isBlank()) return failure("ask_user 需要会话上下文（sessionId 为空）")
        val questions = runCatching { AskUserQuestions.parse(request.toolCall.args) }.getOrElse { error ->
            return failure("ask_user 参数校验未通过：${error.message}。请修正参数后重新调用。")
        }
        if (!request.allowApprovalRequest) return ToolResult(
            id = UUID.randomUUID().toString(), createdAt = request.now, toolCallId = request.toolCall.id,
            success = true, output = AskUserQuestions.timeoutOutput(request.toolCall.args.toString(), waited = false),
        )
        val repository = approvalRepository ?: return failure("审批仓储未初始化，无法向用户提问")
        val approvalRequest = approvalPolicyEngine.createRequest(
            sessionId = request.sessionId,
            toolCall = ToolCall(
                id = request.toolCall.id,
                createdAt = request.now,
                tool = HarnessTool.ASK_USER,
                args = request.toolCall.args,
                rawToolName = AskUserQuestions.TOOL_NAME,
            ),
            workspace = request.workspace,
            decision = ApprovalDecision(
                required = true,
                riskLevel = "none",
                reason = questions.first().question.take(200),
                summary = "智能体向你提问（${questions.size} 个问题）",
            ),
            operationId = request.operationId,
        )
        // 用户可能离开界面；到期采用推荐答案并继续同一任务。
        runCatching { repository.create(approvalRequest.copy(expiresAt = request.now + QUESTION_TTL_MS)) }
            .getOrElse { throwable ->
                return failure("问题请求落库失败：${throwable.message ?: throwable::class.simpleName}")
            }
        return ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = request.now,
            toolCallId = request.toolCall.id,
            success = true,
            output = "已向用户提出 ${questions.size} 个问题，最多等待 60 秒；未回复将采用推荐答案继续。",
            awaitingApproval = true,
            approvalRequestId = approvalRequest.id,
        )
    }

    companion object {
        const val QUESTION_TTL_MS: Long = 60_000L
    }
}

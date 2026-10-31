package top.tianyan.app.harness.approval

import java.util.UUID
import kotlinx.coroutines.CancellationException
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AgentApprovalAuditEntity
import top.tianyan.app.core.database.AgentApprovalAuditRepository

/**
 * 审批审计埋点收口（借鉴 DSH「审批事件写日志、不进模型上下文」）。
 *
 * 只登记 agent_approval_requests 表覆盖不到的审批事实（会话授权写入 / 豁免执行 /
 * 子智能体 deferred 交接），让事后审查能回答「这次为什么没问」。
 * 审计失败绝不影响主链路：插入异常降级为 warn 日志（审计是旁路，不是门禁）。
 */
class ApprovalAuditRecorder(
    private val audit: AgentApprovalAuditRepository?,
    private val logger: AppLogger? = null,
) {

    /** 用户批准并勾选「本会话内记住」时：写入授权表并登记审计。 */
    suspend fun grantWritten(
        sessionId: String,
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
        approvalRequestId: String? = null,
    ) {
        SessionApprovalGrants.grantKey(toolName, argumentsJson)?.let { key ->
            record(
                AgentApprovalAuditEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    kind = AgentApprovalAuditEntity.KIND_GRANT_WRITTEN,
                    toolName = toolName,
                    riskLevel = riskLevel,
                    grantKey = key,
                    approvalRequestId = approvalRequestId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 某次调用因会话内授权豁免而免审批执行时登记。 */
    suspend fun grantExempted(
        sessionId: String,
        toolCallId: String,
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
    ) {
        record(
            AgentApprovalAuditEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                kind = AgentApprovalAuditEntity.KIND_GRANT_EXEMPTED,
                toolName = toolName,
                toolCallId = toolCallId,
                riskLevel = riskLevel,
                grantKey = SessionApprovalGrants.grantKey(toolName, argumentsJson),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 子智能体后台 Lane 交接审批（approvalDeferred）时登记。 */
    suspend fun deferred(
        sessionId: String,
        toolCallId: String,
        toolName: String,
        riskLevel: String,
        reason: String,
    ) {
        record(
            AgentApprovalAuditEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                kind = AgentApprovalAuditEntity.KIND_DEFERRED,
                toolName = toolName,
                toolCallId = toolCallId,
                riskLevel = riskLevel,
                detail = reason.take(DETAIL_MAX_CHARS),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteForSession(sessionId: String) {
        val repository = audit ?: return
        runCatching { repository.deleteForSession(sessionId) }
            .onFailure { if (it is CancellationException) throw it else logger?.w("Failed to clean approval audits for $sessionId: ${it.message}") }
    }

    private suspend fun record(entry: AgentApprovalAuditEntity) {
        val repository = audit ?: return
        runCatching { repository.record(entry) }
            .onFailure { if (it is CancellationException) throw it else logger?.w("Approval audit insert failed (${entry.kind}/${entry.toolName}): ${it.message}") }
    }

    private companion object {
        const val DETAIL_MAX_CHARS = 500
    }
}

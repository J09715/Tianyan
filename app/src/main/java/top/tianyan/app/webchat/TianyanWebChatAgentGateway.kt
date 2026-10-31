package top.tianyan.app.webchat

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.MessageFeedbackService
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.task.TaskDeliveryReport
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.runtime.webchat.WebChatAgentGateway
import top.tianyan.app.runtime.webchat.WebChatApproval
import top.tianyan.app.runtime.webchat.WebChatMessage
import top.tianyan.app.runtime.webchat.WebChatSessionSnapshot
import top.tianyan.app.runtime.webchat.WebChatInputMode
import top.tianyan.app.runtime.webchat.WebChatInputReceipt
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.core.database.task.AgentTaskRepository
import top.tianyan.app.runtime.webchat.WebChatTaskState

class TianyanWebChatAgentGateway(
    private val harnessLoop: SessionControl,
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val approvals: AgentApprovalRepository,
    private val tasks: AgentTaskRepository,
    private val feedback: MessageFeedbackService? = null,
) : WebChatAgentGateway {

    override suspend fun createSession(title: String, workspace: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val defaultModel = models.activeModel()
        sessions.upsert(
            HarnessSessionEntity(
                id = id,
                title = title.trim().ifBlank { "新会话" },
                createdAt = now,
                updatedAt = now,
                modelId = defaultModel?.id,
                modelVariant = defaultModel?.model?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() },
                workspace = workspace,
                approvalMode = ApprovalMode.FULL_ACCESS.id,
            ),
        )
        return id
    }

    override suspend fun deleteSession(sessionId: String) = harnessLoop.deleteSession(sessionId)

    override suspend fun messages(sessionId: String): List<WebChatMessage> =
        harnessLoop.persistedMessages(sessionId).filterNot { it is TaskDelivery && !it.hasExecutionDetails }.map(::toWebMessage)

    override suspend fun pendingApprovals(sessionId: String): List<WebChatApproval> =
        approvals.pendingNow(sessionId).map(::toWebApproval)

    override fun observeTask(sessionId: String, taskId: String): Flow<WebChatTaskState> =
        tasks.observeTask(taskId).map { task ->
            if (task == null || task.sessionId != sessionId) WebChatTaskState.MISSING
            else WebChatTaskState.entries.find { it.name == task.status } ?: WebChatTaskState.UNKNOWN
        }.distinctUntilChanged()

    override fun observeSession(sessionId: String): Flow<WebChatSessionSnapshot> =
        combine(
            harnessLoop.messagesForSession(sessionId),
            harnessLoop.sessionRunStates,
            harnessLoop.sessionStatuses,
            approvals.pendingForSession(sessionId),
        ) { messages, states, statuses, pending ->
            val state = states[sessionId]
            WebChatSessionSnapshot(
                messages = messages.filterNot { it is TaskDelivery && !it.hasExecutionDetails }.map(::toWebMessage),
                running = state == SessionRunState.RUNNING,
                waitingApproval = state == SessionRunState.WAITING_APPROVAL,
                approvals = pending.map(::toWebApproval),
                error = if (state == SessionRunState.FAILED) statuses[sessionId] else null,
            )
        }

    override suspend fun send(sessionId: String, text: String, imageUrls: List<String>,
        mode: WebChatInputMode): WebChatInputReceipt =
        when (val result = harnessLoop.submit(sessionId, text, imageUrls,
            PromptQueue.entries.single { it.id == mode.id })) {
            is PromptSubmission.Accepted -> WebChatInputReceipt(
                result.disposition.name.lowercase(), result.taskId, result.queue?.id, result.queueItemId)
            is PromptSubmission.Rejected -> throw IllegalArgumentException(when (result.reason) {
                PromptSubmission.Rejection.INVALID_SESSION -> "会话 ID 不能为空"
                PromptSubmission.Rejection.EMPTY_INPUT -> "消息不能为空"
                PromptSubmission.Rejection.SESSION_NOT_FOUND -> "会话不存在"
                PromptSubmission.Rejection.LAUNCH_REJECTED -> "会话启动失败"
            })
        }

    override suspend fun resolveApproval(sessionId: String, requestId: String, approved: Boolean): Boolean {
        val request = approvals.find(requestId) ?: return false
        if (request.sessionId != sessionId || request.status != AgentApprovalRequestEntity.STATUS_PENDING) return false
        harnessLoop.resolveApproval(requestId, approved)
        return true
    }

    override fun cancel(sessionId: String) = harnessLoop.cancel(sessionId)

    override suspend fun setMessageFeedback(sessionId: String, messageId: String, rating: String, note: String?) =
        requireNotNull(feedback) { "反馈服务未初始化" }.set(sessionId, messageId, rating, note)

    override suspend fun messageFeedbacks(sessionId: String) =
        feedback?.listForSession(sessionId) ?: emptyList()

    private fun toWebApproval(request: AgentApprovalRequestEntity) = WebChatApproval(
        id = request.id,
        toolName = request.toolName,
        argumentsJson = request.argumentsJson,
        workspace = request.workspace,
        riskLevel = request.riskLevel,
        reason = request.reason,
        summary = request.summary,
        createdAt = request.createdAt,
        expiresAt = request.expiresAt,
    )

    private fun toWebMessage(message: HarnessMessage): WebChatMessage = when (message) {
        is UserMessage -> WebChatMessage(
            id = message.id,
            user = 1,
            type = 1,
            content = buildJsonObject {
                put("text", message.text)
                if (message.imageUrls.isNotEmpty()) {
                    putJsonArray("attachments") {
                        message.imageUrls.forEachIndexed { index, url ->
                            add(buildJsonObject {
                                put("name", "图片 ${index + 1}")
                                put("dataUrl", url)
                                put("isImage", true)
                            })
                        }
                    }
                }
            },
            createAt = message.createdAt,
        )
        is AssistantText -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 1,
            content = buildJsonObject { put("text", message.text) },
            createAt = message.createdAt,
            reasoningContent = message.reasoning,
        )
        is ToolCall -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", message.rawToolName ?: message.tool.name.lowercase())
                put("toolType", message.tool.name.lowercase())
                put("status", "running")
                put("arguments", message.args)
            },
            createAt = message.createdAt,
            reasoningContent = message.reasoning,
            isLoading = true,
        )
        is ToolResult -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", "工具结果")
                put("toolType", "tool")
                put("status", if (message.success) "success" else "error")
                put("output", message.output)
                message.durationMs?.let { put("durationMs", it) }
            },
            createAt = message.createdAt,
            isError = !message.success,
        )
        is CapabilityEvent -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", message.name)
                put("toolType", message.kind.name.lowercase())
                put("status", "success")
                put("details", message.details)
            },
            createAt = message.createdAt,
        )
        is SkillSuggestion -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", "技能建议: ${message.skillName}")
                put("toolType", "skill_suggestion")
                put("status", message.status)
                put("action", message.action)
                put("details", message.description)
            },
            createAt = message.createdAt,
        )
        is TaskDelivery -> WebChatMessage(
            id = message.id, user = 0, type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary"); put("toolTitle", "任务交付记录（行为未验证）")
                put("toolType", "task_delivery"); put("status", "unverified")
                put("details", "${message.report.observations.size} 条工具记录 · ${message.report.artifacts.size} 份产物记录")
                put("report", Json.encodeToJsonElement(TaskDeliveryReport.serializer(), message.report))
            }, createAt = message.createdAt,
        )
        is ModelSwitchEvent -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "model_switch")
                put("toolTitle", message.toLabel)
                put("toolType", "model_switch")
                put("status", if (message.compacted) "compacted" else "success")
                put("details", message.fromLabel)
                put("toContextTokens", message.toContextTokens)
                put("compacted", message.compacted)
                put("foldedMessageCount", message.foldedMessageCount)
            },
            createAt = message.createdAt,
        )
        is TurnChangesEvent -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", "本轮文件改动")
                put("toolType", "turn_changes")
                put("status", "success")
                put("details", (message.created + message.modified + message.touched).joinToString("、"))
            },
            createAt = message.createdAt,
        )
    }
}

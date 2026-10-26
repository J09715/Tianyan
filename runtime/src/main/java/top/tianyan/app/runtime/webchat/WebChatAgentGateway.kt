package top.tianyan.app.runtime.webchat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.database.AgentMessageFeedbackEntity

/** Runtime-facing boundary for exposing Tianyan's native Harness to the LAN Web console. */
interface WebChatAgentGateway {
    suspend fun createSession(title: String, workspace: String): String
    suspend fun deleteSession(sessionId: String)
    suspend fun messages(sessionId: String): List<WebChatMessage>
    suspend fun pendingApprovals(sessionId: String): List<WebChatApproval>
    fun observeSession(sessionId: String): Flow<WebChatSessionSnapshot>
    fun observeTask(sessionId: String, taskId: String): Flow<WebChatTaskState>
    suspend fun send(sessionId: String, text: String, imageUrls: List<String>,
        mode: WebChatInputMode = WebChatInputMode.NEXT_RUN): WebChatInputReceipt
    suspend fun resolveApproval(sessionId: String, requestId: String, approved: Boolean): Boolean
    /** 对单条 assistant 消息写入/覆盖用户反馈（目标校验失败抛 IllegalArgumentException）。 */
    suspend fun setMessageFeedback(sessionId: String, messageId: String, rating: String, note: String?): AgentMessageFeedbackEntity
    suspend fun messageFeedbacks(sessionId: String): List<AgentMessageFeedbackEntity>
    fun cancel(sessionId: String)
}

enum class WebChatTaskState {
    QUEUED, RUNNING, WAITING_APPROVAL, RECOVERING, SUSPENDED, FAILED, COMPLETED, CANCELLED, MISSING, UNKNOWN
}

enum class WebChatInputMode(val id: String) {
    NEXT_RUN("next_run"), STEER("steer"), FOLLOW_UP("follow_up")
}

/** Admission receipt. Completion is reported by the existing session stream. */
data class WebChatInputReceipt(
    val disposition: String,
    val taskId: String?,
    val queue: String? = null,
    val queueItemId: String? = null,
)

@Serializable
data class WebChatApproval(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val workspace: String,
    val riskLevel: String,
    val reason: String,
    val summary: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Serializable
data class WebChatMessage(
    val id: String,
    val user: Int,
    val type: Int,
    val content: JsonObject,
    val createAt: Long,
    val reasoningContent: String? = null,
    val isError: Boolean = false,
    val isLoading: Boolean = false,
)

data class WebChatSessionSnapshot(
    val messages: List<WebChatMessage>,
    val running: Boolean,
    val waitingApproval: Boolean,
    val approvals: List<WebChatApproval> = emptyList(),
    val error: String? = null,
)

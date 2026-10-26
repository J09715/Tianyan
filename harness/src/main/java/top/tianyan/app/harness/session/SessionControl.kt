package top.tianyan.app.harness.session

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.queue.PromptQueue

/** Shared in-process session API. Explicit IDs never select the foreground session. */
interface SessionControl {
    val currentSessionId: StateFlow<String>
    val sessionRunStates: StateFlow<Map<String, SessionRunState>>
    val sessionStatuses: StateFlow<Map<String, String>>
    /** Host-observed model output, including incomplete tool arguments; not status text or SSE heartbeats. */
    val modelOutputRevisions: StateFlow<Map<String, Long>> get() = NO_MODEL_OUTPUT
    val providerActivities: StateFlow<Map<String, ProviderActivity>> get() = NO_PROVIDER_ACTIVITY
    fun messagesForSession(sessionId: String): StateFlow<List<HarnessMessage>>
    suspend fun prepareRemoteSession(sessionId: String): List<HarnessMessage>
    /** Read-only durable history; propagates storage failure instead of returning a fallback. */
    suspend fun persistedMessages(sessionId: String): List<HarnessMessage>
    suspend fun deleteSession(id: String)

    /** Waits for durable admission, not model completion. Admission failures propagate. */
    suspend fun submit(
        sessionId: String,
        text: String,
        imageUrls: List<String> = emptyList(),
        queue: PromptQueue = PromptQueue.NEXT_RUN,
        attachments: List<top.tianyan.app.harness.MessageAttachment> = emptyList(),
    ): PromptSubmission

    /**
     * 发送一条消息。
     *
     * [attachments] 是**结构化**附件元信息，不拼进 [text]：气泡据此渲染文件卡片，
     * 附件清单由 HarnessApiMapper.renderUserContent 在发给模型时展开。
     */
    fun send(
        text: String,
        targetSessionId: String? = null,
        imageUrls: List<String> = emptyList(),
        attachments: List<top.tianyan.app.harness.MessageAttachment> = emptyList(),
    )
    fun steer(
        text: String,
        targetSessionId: String? = null,
        imageUrls: List<String> = emptyList(),
        attachments: List<top.tianyan.app.harness.MessageAttachment> = emptyList(),
    )
    fun followUp(
        text: String,
        targetSessionId: String? = null,
        imageUrls: List<String> = emptyList(),
        attachments: List<top.tianyan.app.harness.MessageAttachment> = emptyList(),
    )
    /** Requests cancellation; state streams report settlement asynchronously. */
    fun cancel(targetSessionId: String? = null)
    fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean = false)
    fun resolveQuestion(requestId: String, answersJson: String)
}

private val NO_MODEL_OUTPUT = MutableStateFlow<Map<String, Long>>(emptyMap()).asStateFlow()
private val NO_PROVIDER_ACTIVITY = MutableStateFlow<Map<String, ProviderActivity>>(emptyMap()).asStateFlow()

sealed interface PromptSubmission {
    data class Accepted(
        val disposition: Disposition,
        val taskId: String?,
        val queue: PromptQueue? = null,
        val queueItemId: String? = null,
    ) : PromptSubmission
    data class Rejected(val reason: Rejection) : PromptSubmission
    enum class Disposition { STARTED, QUEUED }
    enum class Rejection { INVALID_SESSION, EMPTY_INPUT, SESSION_NOT_FOUND, LAUNCH_REJECTED }
}

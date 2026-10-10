package top.tianyan.app.harness

import kotlinx.serialization.Serializable

/** User-facing projection of a durable queued prompt. */
@Serializable
data class PendingMessage(
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Durable task owning this queued prompt. Null keeps old queue payloads readable. */
    val taskId: String? = null,
    /**
     * 附件元信息。随排队消息一起持久化（队列里的消息也要能在 UI 上显示附件卡片），
     * 默认空列表保证旧 payload 可读。
     */
    val attachments: List<MessageAttachment> = emptyList(),
)

/** Durable prompt plus its delivery semantics, exposed for queue-aware UI. */
data class QueuedPrompt(
    val id: String,
    val queue: top.tianyan.app.harness.queue.PromptQueue,
    val message: PendingMessage,
)

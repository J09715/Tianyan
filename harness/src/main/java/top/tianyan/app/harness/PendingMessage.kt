package top.tianyan.app.harness

import kotlinx.serialization.Serializable
import top.tianyan.app.harness.queue.PromptQueue

/** User-facing projection of a durable queued prompt. */
@Serializable
data class PendingMessage(
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Durable task owning this queued prompt. Null keeps old queue payloads readable. */
    val taskId: String? = null,
    val groupWorkId: String? = null,
    val groupId: String? = null,
    /**
     * 附件元信息。默认空列表保证旧队列数据可读（反序列化缺失即空）。
     *
     * 与 [text] 分开存：正文只放用户输入，附件清单在发模型时由
     * HarnessApiMapper.renderUserContent 展开。
     */
    val attachments: List<MessageAttachment> = emptyList(),
)

/** Durable prompt plus its delivery semantics, exposed for queue-aware UI. */
data class QueuedPrompt(
    val id: String,
    val queue: PromptQueue,
    val message: PendingMessage,
)

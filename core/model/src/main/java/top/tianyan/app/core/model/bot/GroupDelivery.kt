package top.tianyan.app.core.model.bot

/** A recipient receipt references its source message; it never owns a second copy of the prompt. */
data class GroupDelivery(
    val id: String,
    val sourceMessageId: String,
    val groupId: String,
    val botId: String,
    val createdAt: Long,
    val status: String = PENDING,
    val sessionId: String? = null,
    val taskId: String? = null,
    val error: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val SUBMITTING = "submitting"
        const val ADMITTED = "admitted"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val REJECTED = "rejected"
        const val UNKNOWN = "unknown"
        const val CANCELLED = "cancelled"
    }
}

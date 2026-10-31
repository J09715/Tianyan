package top.tianyan.app.core.model.bot

/** A durable, addressed next turn. Instructions never interrupt an executing native tool. */
data class BotAssignmentRequest(
    val id: String,
    val assignmentId: String,
    val instruction: String,
    val createdAt: Long,
    val status: String = PENDING,
    val nativeTaskId: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val blockedReason: String? = null,
    val artifacts: List<String> = emptyList(),
) {
    companion object {
        const val PENDING = "pending"
        const val SUBMITTING = "submitting"
        const val ADMITTED = "admitted"
        const val COMPLETED = "completed"
        /** Result was received by the synchronous caller; this does not mean the task was accepted. */
        const val RECEIVED = "received"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"
        const val CANCELLED = "cancelled"
    }
}

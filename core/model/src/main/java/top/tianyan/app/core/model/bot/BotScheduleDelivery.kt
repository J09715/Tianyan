package top.tianyan.app.core.model.bot

/** One immutable occurrence; ADMITTED means durable Harness acceptance, not task completion. */
data class BotScheduleDelivery(
    val id: String,
    val scheduleId: String,
    val botId: String,
    val groupId: String?,
    val instruction: String,
    val scheduledAt: Long,
    val createdAt: Long,
    val status: String = PENDING,
    val error: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val ADMITTING = "admitting"
        const val ADMITTED = "admitted"
        const val REJECTED = "rejected"
        const val UNKNOWN = "unknown"
    }
}

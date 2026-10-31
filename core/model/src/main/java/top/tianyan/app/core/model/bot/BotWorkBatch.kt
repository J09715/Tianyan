package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

@Serializable
enum class BotWorkBatchStatus {
    WAITING_CHILDREN, WAITING_TURN, SETTLED, CANCELLED;

    val isActive: Boolean get() = this == WAITING_CHILDREN || this == WAITING_TURN
}

/** 同一主控回合内派出的多个任务，全部结束后只回报一次。 */
@Serializable
data class BotWorkBatch(
    val id: String,
    val groupId: String,
    val requesterBotId: String,
    val requesterSessionId: String,
    val originOperationId: String?,
    val revision: Int,
    val reportRevision: Int? = null,
    val status: BotWorkBatchStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val settledAt: Long? = null,
) {
    companion object {
        const val MAX_TASKS = 12
    }
}

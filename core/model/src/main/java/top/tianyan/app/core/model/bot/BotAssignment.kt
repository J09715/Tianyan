package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

@Serializable
enum class BotAssignmentStatus {
    QUEUED, RUNNING, WAITING_CHILDREN, BLOCKED, INTERRUPTED, COMPLETED, FAILED, CANCELLED;
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING_CHILDREN
}

/** 主控派发给某个 Bot 的一次后台任务，持久化后可供查询、验收和取消。 */
@Serializable
data class BotAssignment(
    val id: String,
    val groupId: String?,
    val botId: String,
    val botName: String,
    val title: String,
    val task: String,
    val status: BotAssignmentStatus,
    val writePaths: List<String> = emptyList(),
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val summary: String? = null,
    val error: String? = null,
    val sessionId: String? = null,
    val laneName: String? = null,
    /** Background result recipient; null means no asynchronous report is requested. */
    val reportToBotId: String? = null,
    val independent: Boolean = false,
    val revision: Int = 0,
    val activeRequestId: String? = null,
    /** assign-task 的 depends_on 快照；前置任务 ID 列表。 */
    val dependsOn: List<String> = emptyList(),
    /** 同一主控回合的工作批次；null 表示单独回报。 */
    val batchId: String? = null,
    /** Original delegating task; unaffected by later team edits. */
    val parentAssignmentId: String? = null,
    val budgetId: String? = null,
    val proposalId: String? = null,
    val proposalVersion: Int? = null,
    val teamBinding: String? = null,
    /** Business work survives native turns and independent session boundaries. */
    val rootWorkId: String? = null,
    val stageId: String? = null,
    val workItemId: String? = null,
)

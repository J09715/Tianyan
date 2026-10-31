package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val BATCH_OPERATION_STALE_MS = 2L * 60 * 60 * 1000

@Entity(
    tableName = "bot_work_batches",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BotEntity::class,
            parentColumns = ["id"],
            childColumns = ["requesterBotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["status", "updatedAt"]),
        Index(value = ["requesterBotId"]),
    ],
)
data class BotWorkBatchEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val requesterBotId: String,
    val requesterSessionId: String,
    val originOperationId: String?,
    @ColumnInfo(defaultValue = "0") val revision: Int = 0,
    val reportRevision: Int? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val settledAt: Long? = null,
)

private const val REPORT_SUMMARY_MAX = 2_000
private const val REPORT_ERROR_MAX = 1_000
private const val REPORT_TOTAL_MAX = 12_000

internal fun buildBatchReport(batch: BotWorkBatchEntity, tasks: List<BotAssignmentEntity>): String {
    val body = buildString {
        appendLine("【团队工作回报】${tasks.size} 个任务已结束（工作 ${batch.id.take(8)}）")
        tasks.forEach { task ->
            appendLine()
            appendLine("${statusMark(task.status)} ${task.botName}「${task.title}」（任务 ${task.id}）")
            val summary = task.summary?.trim().orEmpty().take(REPORT_SUMMARY_MAX)
            val error = task.error?.trim().orEmpty().take(REPORT_ERROR_MAX)
            when (task.status) {
                "COMPLETED" -> appendLine(summary.ifBlank { "（无结果摘要）" })
                "CANCELLED" -> appendLine("已取消" + error.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty())
                else -> {
                    appendLine(assignmentStatusLabel(task.status) + error.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty())
                    if (summary.isNotBlank()) appendLine(summary)
                }
            }
        }
        appendLine()
        append("以上为同一批任务的全部结果。请核对产物；结果未知时先只读核验，勿重复执行；需要补充时用 continue-assignment 继续原任务。")
    }
    if (body.length <= REPORT_TOTAL_MAX) return body
    val suffix = "\n…（汇总已截断，完整结果见 inspect-assignment）"
    return body.take(REPORT_TOTAL_MAX - suffix.length).trimEnd() + suffix
}

private fun assignmentStatusLabel(status: String): String = when (status) {
    "COMPLETED" -> "已完成"
    "FAILED" -> "执行未完成"
    "BLOCKED" -> "受阻"
    "INTERRUPTED" -> "执行结果待核对"
    "CANCELLED" -> "已取消"
    "QUEUED" -> "排队中"
    "RUNNING" -> "执行中"
    else -> "未完成"
}

private fun statusMark(status: String): String = when (status) {
    "COMPLETED" -> "✅"
    "CANCELLED" -> "⏹"
    else -> "⚠️"
}

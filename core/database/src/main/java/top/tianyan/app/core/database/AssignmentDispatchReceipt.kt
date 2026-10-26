package top.tianyan.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Records successful dispatch calls, including calls which reused another dispatcher's task. */
@Entity(tableName = "assignment_dispatch_receipts", primaryKeys = ["operationId", "assignmentId"],
    foreignKeys = [ForeignKey(entity = BotAssignmentEntity::class, parentColumns = ["id"],
        childColumns = ["assignmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assignmentId"), Index(value = ["groupId", "operationId"]), Index(value = ["groupId", "nativeTaskId"])])
data class AssignmentDispatchReceipt(
    val operationId: String,
    val assignmentId: String,
    val groupId: String,
    val botId: String,
    val nativeTaskId: String? = null,
)

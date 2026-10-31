package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_rounds",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "status", "createdAt"]),
        Index(value = ["status"]),
    ],
)
data class GroupRoundEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val trigger: String,
    @ColumnInfo(defaultValue = "'[]'") val imageUrlsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'") val slotsJson: String = "[]",
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "group_late_replies",
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
            childColumns = ["workerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "status"]),
        Index(value = ["status"]),
    ],
)
data class GroupLateReplyEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val workerId: String,
    val workerName: String,
    val sessionId: String,
    val placeholderId: String,
    val callerBotId: String?,
    val createdAt: Long,
    val status: String,
    val error: String?,
    @ColumnInfo(defaultValue = "NULL") val taskId: String? = null,
)

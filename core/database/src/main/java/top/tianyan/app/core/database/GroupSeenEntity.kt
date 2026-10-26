package top.tianyan.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 每个成员在每个群读到哪条消息，用于轮次只传增量历史。 */
@Entity(
    tableName = "group_seen",
    primaryKeys = ["groupId", "botId"],
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
            childColumns = ["botId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["botId"])],
)
data class GroupSeenEntity(
    val groupId: String,
    val botId: String,
    val lastSeenSeq: Long,
    val updatedAt: Long,
)

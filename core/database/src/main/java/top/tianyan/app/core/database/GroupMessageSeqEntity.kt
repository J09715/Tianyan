package top.tianyan.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** One atomic counter per group so concurrent writers cannot duplicate group message seq. */
@Entity(
    tableName = "group_message_seq",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupMessageSeqEntity(
    @PrimaryKey val groupId: String,
    val nextSeq: Long,
)

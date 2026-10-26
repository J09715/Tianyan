package top.tianyan.app.core.database

import androidx.room.*

@Entity(
    tableName = "group_chats",
    foreignKeys = [
        ForeignKey(
            entity = BotEntity::class,
            parentColumns = ["id"],
            childColumns = ["adminBotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["adminBotId"])],
)
data class GroupChatEntity(
    @PrimaryKey val id: String,
    val name: String,
    val adminBotId: String,
    val description: String,
    val createdAt: Long,
    val background: String = "default",
    @ColumnInfo(defaultValue = "'ADMIN_FIRST'") val replyMode: String = "ADMIN_FIRST",
    @ColumnInfo(defaultValue = "0") val taskBudgetTokens: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val executionLimitsJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val teamGroupId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val teamVersion: Int? = null,
    /** 群统一审批模式（null = 完全访问）；群维度成员会话创建时对齐。 */
    @ColumnInfo(defaultValue = "NULL") val unifiedApprovalMode: String? = null,
)

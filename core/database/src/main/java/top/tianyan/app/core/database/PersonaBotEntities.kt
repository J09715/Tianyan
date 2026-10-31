package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
@Entity(
    tableName = "persona_bots",
    indices = [
        Index(value = ["sessionId"], unique = true),
        Index(value = ["isMainBot"]),
    ],
)
data class BotEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val systemPrompt: String,
    val avatarSeed: String,
    val modelId: String?,
    val providerId: String?,
    val sessionId: String,
    val isMainBot: Boolean,
    val colorHex: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelVariant: String? = null,
    @ColumnInfo(defaultValue = "0") val allowModelSelection: Boolean = false,
    /** Bot 级 MCP 能力白名单（JSON 字符串数组）；null = 不限制。 */
    @ColumnInfo(defaultValue = "NULL") val capabilityServerNames: String? = null,
)

@Entity(
    tableName = "group_members",
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
    indices = [Index(value = ["groupId"]), Index(value = ["botId"])],
)
data class GroupMemberEntity(
    val groupId: String,
    val botId: String,
    val displayOrder: Int,
    val joinedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val supervisorBotId: String? = null,
)

@Entity(
    tableName = "group_messages",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "timestamp"]),
        Index(value = ["groupId", "seq"], unique = true),
        Index(value = ["delegatedTaskId"]),
    ],
)
data class GroupMessageEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderId: String,
    val senderName: String,
    val senderType: String,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean,
    val delegatedTaskId: String?,
    val attachmentsJson: String = "[]",
    val reactionsJson: String = "[]",
    val stepsJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val reasoning: String = "",
    @ColumnInfo(defaultValue = "0") val seq: Long = 0,
)

@Entity(
    tableName = "bot_schedules",
    foreignKeys = [
        ForeignKey(
            entity = BotEntity::class,
            parentColumns = ["id"],
            childColumns = ["botId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["botId"]), Index(value = ["nextRunAt"])],
)
data class BotScheduleEntity(
    @PrimaryKey val id: String,
    val botId: String,
    val groupId: String?,
    val cron: String,
    val instruction: String,
    val enabled: Boolean,
    @ColumnInfo(name = "nextRunAt") val nextRunAt: Long,
    val lastRunAt: Long?,
    val createdAt: Long,
)

/** Bot 会话所属群聊的映射行，用于会话抽屉按群折叠。 */
data class BotGroupSessionRefRow(
    val sessionId: String,
    val groupId: String,
    val groupName: String,
)

@Dao
interface PersonaBotDao {
    @Query("SELECT * FROM persona_bots ORDER BY isMainBot DESC, updatedAt DESC")
    fun observeAll(): Flow<List<BotEntity>>

    @Query("SELECT * FROM persona_bots WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BotEntity?

    @Query("SELECT * FROM persona_bots WHERE sessionId = :sessionId OR id IN " +
        "(SELECT botId FROM bot_group_sessions WHERE sessionId = :sessionId) OR id IN " +
        "(SELECT botId FROM bot_assignments WHERE independent = 1 AND sessionId = :sessionId) LIMIT 1")
    suspend fun findBySessionId(sessionId: String): BotEntity?

    @Query("SELECT * FROM persona_bots WHERE isMainBot = 1 LIMIT 1")
    suspend fun findMainBot(): BotEntity?

    /** Bot 名下全部会话：私有会话、群维度会话与独立委派会话。 */
    @Query(
        "SELECT sessionId FROM persona_bots " +
            "UNION SELECT sessionId FROM bot_group_sessions " +
            "UNION SELECT sessionId FROM bot_assignments WHERE independent = 1 AND sessionId IS NOT NULL",
    )
    fun observeSessionIds(): Flow<List<String>>

    /** 群维度会话与独立委派会话归属的群聊；私有会话不在结果里。 */
    @Query(
        "SELECT bs.sessionId AS sessionId, g.id AS groupId, g.name AS groupName " +
            "FROM bot_group_sessions bs INNER JOIN group_chats g ON g.id = bs.groupId " +
            "UNION " +
            "SELECT a.sessionId AS sessionId, g.id AS groupId, g.name AS groupName " +
            "FROM bot_assignments a INNER JOIN group_chats g ON g.id = a.groupId " +
            "WHERE a.independent = 1 AND a.sessionId IS NOT NULL",
    )
    fun observeGroupSessionRefs(): Flow<List<BotGroupSessionRefRow>>

    @Query("SELECT * FROM persona_bots ORDER BY isMainBot DESC, updatedAt DESC")
    suspend fun listAll(): List<BotEntity>

    @Query("SELECT * FROM persona_bots WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): BotEntity?

    @Upsert
    suspend fun upsert(bot: BotEntity)

    @Query("DELETE FROM persona_bots WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE persona_bots SET isMainBot = 0 WHERE id != :exceptId")
    suspend fun clearMainExcept(exceptId: String)

    @Transaction
    suspend fun upsertWithMain(bot: BotEntity) {
        if (bot.isMainBot) clearMainExcept(bot.id)
        upsert(bot)
    }
}

@Dao
interface GroupChatDao : GroupDeliveryDao, GroupTemplateDao {
    @Query("SELECT * FROM group_chats ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupChatEntity>>

    @Query(
        "SELECT g.* FROM group_chats g INNER JOIN group_members m ON g.id = m.groupId " +
            "WHERE m.botId = :botId ORDER BY g.createdAt DESC",
    )
    fun observeForBot(botId: String): Flow<List<GroupChatEntity>>

    @Query("SELECT * FROM group_chats WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): GroupChatEntity?

    @Query("SELECT COUNT(*) FROM group_chats WHERE adminBotId = :botId")
    suspend fun countAsAdmin(botId: String): Int

    @Upsert
    suspend fun upsertGroup(group: GroupChatEntity)

    @Query("DELETE FROM group_chats WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "SELECT b.* FROM persona_bots b INNER JOIN group_members m ON b.id = m.botId " +
            "WHERE m.groupId = :groupId ORDER BY m.displayOrder ASC",
    )
    fun observeMembers(groupId: String): Flow<List<BotEntity>>

    @Query(
        "SELECT b.* FROM persona_bots b INNER JOIN group_members m ON b.id = m.botId " +
            "WHERE m.groupId = :groupId ORDER BY m.displayOrder ASC",
    )
    suspend fun getMembers(groupId: String): List<BotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMember(member: GroupMemberEntity)

    @Transaction suspend fun addMember(member: GroupMemberEntity) {
        require(linkedTeamId(member.groupId) == null) { "成员请在团队组中修改" }
        insertChatMember(member)
    }

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND botId = :botId")
    suspend fun deleteMember(groupId: String, botId: String)

    @Transaction
    suspend fun removeMember(groupId: String, botId: String) {
        require(linkedTeamId(groupId) == null) { "团队成员请在团队组中修改" }
        require(teamAdmin(groupId) != botId) { "不能移出群主控" }
        cancelGroupDeliveries(groupId, botId)
        detachReports(groupId, botId); deleteMember(groupId, botId)
    }

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY seq ASC, rowid ASC")
    fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM group_messages WHERE groupId = :groupId")
    suspend fun latestSeq(groupId: String): Long

    @Transaction
    suspend fun postMessageWithSeq(message: GroupMessageEntity) {
        upsertMessage(message.copy(seq = nextMessageSeq(message.groupId)))
    }

    @Query("DELETE FROM group_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("SELECT lastSeenSeq FROM group_seen WHERE groupId = :groupId AND botId = :botId")
    suspend fun lastSeenSeq(groupId: String, botId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeen(seen: GroupSeenEntity)

    @Query("DELETE FROM group_seen WHERE groupId = :groupId")
    suspend fun deleteSeen(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRound(round: GroupRoundEntity)

    @Transaction
    suspend fun stageUserMessageWithRound(message: GroupMessageEntity, round: GroupRoundEntity) {
        stageGroupDelivery(message, emptyList())
        upsertRound(round)
    }

    @Query("SELECT * FROM group_rounds WHERE id = :id LIMIT 1")
    suspend fun findRound(id: String): GroupRoundEntity?

    @Query("SELECT * FROM group_rounds WHERE status = 'RUNNING' ORDER BY createdAt ASC")
    suspend fun runningRounds(): List<GroupRoundEntity>

    @Query("UPDATE group_rounds SET slotsJson = :slotsJson, status = :status, updatedAt = :now WHERE id = :id AND status = 'RUNNING'")
    suspend fun updateRoundProgress(id: String, slotsJson: String, status: String, now: Long): Int

    @Query(
        "UPDATE group_rounds SET trigger = :trigger, imageUrlsJson = :imageUrlsJson, updatedAt = :now WHERE id = :id",
    )
    suspend fun updateRoundAttachments(id: String, trigger: String, imageUrlsJson: String, now: Long)

    @Query(
        "UPDATE group_rounds SET slotsJson = :slotsJson, status = :status, " +
            "imageUrlsJson = '[]', updatedAt = :now WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun finishRound(id: String, slotsJson: String, status: String, now: Long): Int

    @Query(
        "UPDATE group_rounds SET status = 'CANCELLED', imageUrlsJson = '[]', updatedAt = :now " +
            "WHERE groupId = :groupId AND status = 'RUNNING'",
    )
    suspend fun cancelRounds(groupId: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLateReply(reply: GroupLateReplyEntity)

    @Query("SELECT * FROM group_late_replies WHERE status = 'WATCHING' ORDER BY createdAt ASC")
    suspend fun watchingLateReplies(): List<GroupLateReplyEntity>

    @Query("UPDATE group_late_replies SET status = :status, error = :error WHERE id = :id")
    suspend fun updateLateReplyStatus(id: String, status: String, error: String?)

    @Query("SELECT * FROM group_late_replies WHERE id = :id LIMIT 1")
    suspend fun findLateReply(id: String): GroupLateReplyEntity?

    @Query("DELETE FROM group_rounds WHERE status != 'RUNNING' AND updatedAt < :before")
    suspend fun pruneFinishedRounds(before: Long)

    @Query("DELETE FROM group_late_replies WHERE status != 'WATCHING' AND createdAt < :before")
    suspend fun pruneFinishedLateReplies(before: Long)

    @Query("SELECT * FROM group_messages WHERE id = :id LIMIT 1")
    suspend fun findMessage(id: String): GroupMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: GroupMessageEntity)

    @Query(
        "UPDATE group_messages SET content = :content, isStreaming = :isStreaming, " +
            "stepsJson = :stepsJson, reasoning = COALESCE(:reasoning, reasoning) WHERE id = :id",
    )
    suspend fun updateMessageContent(id: String, content: String, isStreaming: Boolean, stepsJson: String, reasoning: String?)

    @Query("UPDATE group_messages SET reactionsJson = :reactionsJson WHERE id = :id")
    suspend fun updateMessageReactions(id: String, reactionsJson: String)

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun deleteMessages(groupId: String)

    @Query("DELETE FROM bot_assignments WHERE groupId = :groupId")
    suspend fun deleteAssignments(groupId: String)

    @Transaction
    suspend fun clearMessages(groupId: String) {
        deleteAssignments(groupId)
        deleteMessages(groupId)
        deleteSeen(groupId)
        deleteRounds(groupId)
        deleteLateReplies(groupId)
        deleteSeqRow(groupId)
    }

    @Query("DELETE FROM group_rounds WHERE groupId = :groupId")
    suspend fun deleteRounds(groupId: String)

    @Query("DELETE FROM group_late_replies WHERE groupId = :groupId")
    suspend fun deleteLateReplies(groupId: String)
}

@Dao
interface BotScheduleDao {
    @Query("SELECT * FROM bot_schedules WHERE botId = :botId ORDER BY nextRunAt ASC")
    fun observeForBot(botId: String): Flow<List<BotScheduleEntity>>

    @Query("SELECT * FROM bot_schedules WHERE enabled = 1 AND nextRunAt <= :now ORDER BY nextRunAt ASC")
    suspend fun listDue(now: Long): List<BotScheduleEntity>

    @Query("SELECT * FROM bot_schedules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BotScheduleEntity?

    @Query("SELECT * FROM bot_schedules WHERE enabled = 1 ORDER BY nextRunAt ASC LIMIT 1")
    suspend fun findNext(): BotScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: BotScheduleEntity)

    @Query("DELETE FROM bot_schedules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bot_schedules WHERE botId = :botId")
    suspend fun deleteForBot(botId: String)

    @Query("UPDATE bot_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE bot_schedules SET lastRunAt = :lastRunAt, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun markRun(id: String, lastRunAt: Long, nextRunAt: Long)
}

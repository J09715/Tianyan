package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import top.tianyan.app.core.database.repository.BotGroupSessionRepository

/** Bot × 群聊 → 独立 Harness 会话；同一 Bot 跨群不再共享上下文。 */
@Entity(
    tableName = "bot_group_sessions",
    primaryKeys = ["botId", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = BotEntity::class,
            parentColumns = ["id"],
            childColumns = ["botId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"]), Index(value = ["sessionId"], unique = true)],
)
data class BotGroupSessionEntity(
    val botId: String,
    val groupId: String,
    val sessionId: String,
    val createdAt: Long,
)

@Dao
interface BotGroupSessionDao {
    @Query("SELECT sessionId FROM bot_group_sessions WHERE botId = :botId AND groupId = :groupId LIMIT 1")
    suspend fun findSessionId(botId: String, groupId: String): String?

    @Query("SELECT sessionId FROM bot_group_sessions WHERE botId = :botId")
    suspend fun listSessionIds(botId: String): List<String>

    @Query("SELECT botId FROM bot_group_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findBotIdBySession(sessionId: String): String?

    @Query("SELECT groupId FROM bot_group_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findGroupIdBySession(sessionId: String): String?

    @Query("SELECT sessionId FROM bot_group_sessions WHERE groupId = :groupId")
    suspend fun listSessionIdsForGroup(groupId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BotGroupSessionEntity)
}

class RoomBotGroupSessionRepository(
    private val dao: BotGroupSessionDao,
) : BotGroupSessionRepository {
    override suspend fun sessionIdFor(botId: String, groupId: String): String? =
        dao.findSessionId(botId, groupId)

    override suspend fun groupIdForSession(sessionId: String): String? =
        dao.findGroupIdBySession(sessionId)

    override suspend fun botIdForSession(sessionId: String): String? =
        dao.findBotIdBySession(sessionId)

    override suspend fun listSessionIdsForBot(botId: String): List<String> = dao.listSessionIds(botId)

    override suspend fun listSessionIdsForGroup(groupId: String): List<String> =
        dao.listSessionIdsForGroup(groupId)

    override suspend fun save(botId: String, groupId: String, sessionId: String) {
        dao.upsert(
            BotGroupSessionEntity(
                botId = botId,
                groupId = groupId,
                sessionId = sessionId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}

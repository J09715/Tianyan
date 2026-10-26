package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.bot.GroupDelivery

@Entity(
    tableName = "group_deliveries",
    foreignKeys = [
        ForeignKey(entity = GroupMessageEntity::class, parentColumns = ["id"], childColumns = ["sourceMessageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BotEntity::class, parentColumns = ["id"], childColumns = ["botId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sourceMessageId", "botId"], unique = true), Index(value = ["botId"]), Index(value = ["status", "createdAt"])],
)
data class GroupDeliveryEntity(
    @PrimaryKey val id: String,
    val sourceMessageId: String,
    val groupId: String,
    val botId: String,
    val createdAt: Long,
    val status: String = GroupDelivery.PENDING,
    val sessionId: String? = null,
    val taskId: String? = null,
    val error: String? = null,
) {
    fun toModel() = GroupDelivery(id, sourceMessageId, groupId, botId, createdAt, status, sessionId, taskId, error)
}

/** Shared by the message and assignment owners so staging is inside their existing transactions. */
@Dao
interface GroupDeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeliverySource(message: GroupMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupDelivery(delivery: GroupDeliveryEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId AND botId = :botId)")
    suspend fun deliveryMember(groupId: String, botId: String): Boolean

    @Query(
        "INSERT OR IGNORE INTO group_message_seq(groupId, nextSeq) " +
            "SELECT :groupId, COALESCE(MAX(seq), 0) FROM group_messages WHERE groupId = :groupId",
    )
    suspend fun ensureSeqRow(groupId: String)

    @Query("UPDATE group_message_seq SET nextSeq = nextSeq + 1 WHERE groupId = :groupId")
    suspend fun bumpSeq(groupId: String)

    @Query("SELECT nextSeq FROM group_message_seq WHERE groupId = :groupId")
    suspend fun currentSeq(groupId: String): Long?

    @Query("DELETE FROM group_message_seq WHERE groupId = :groupId")
    suspend fun deleteSeqRow(groupId: String)

    @Transaction
    suspend fun nextMessageSeq(groupId: String): Long {
        ensureSeqRow(groupId)
        bumpSeq(groupId)
        return currentSeq(groupId) ?: 1L
    }

    @Transaction
    suspend fun stageGroupDelivery(message: GroupMessageEntity, botIds: List<String>) {
        // A replay of a staged source must not change its recipients or recreate cleared work.
        val withSeq = message.copy(seq = nextMessageSeq(message.groupId))
        if (insertDeliverySource(withSeq) == -1L) return
        botIds.distinct().forEach { botId ->
            if (deliveryMember(message.groupId, botId)) {
                insertGroupDelivery(GroupDeliveryEntity("${message.id}:$botId", message.id, message.groupId, botId, message.timestamp))
            }
        }
    }

    // Source insertion order also handles equal timestamps and a wall clock moving backwards.
    @Query("SELECT d.* FROM group_deliveries d JOIN group_messages m ON m.id = d.sourceMessageId " +
        "WHERE d.status = 'pending' ORDER BY m.rowid, d.id LIMIT 64")
    fun pendingGroupDeliveries(): Flow<List<GroupDeliveryEntity>>

    @Query("SELECT * FROM group_deliveries WHERE groupId = :groupId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun listGroupDeliveries(groupId: String, limit: Int): List<GroupDeliveryEntity>

    @Query("SELECT * FROM group_deliveries WHERE id = :id")
    suspend fun findGroupDelivery(id: String): GroupDeliveryEntity?

    @Query("SELECT * FROM group_deliveries WHERE status IN ('submitting', 'admitted')")
    suspend fun interruptedGroupDeliveries(): List<GroupDeliveryEntity>

    @Query("UPDATE group_deliveries SET status = 'submitting' WHERE id = :id AND status = 'pending' AND EXISTS " +
        "(SELECT 1 FROM group_members m WHERE m.groupId = group_deliveries.groupId AND m.botId = group_deliveries.botId)")
    suspend fun claimGroupDelivery(id: String): Int

    @Query("UPDATE group_deliveries SET sessionId = :sessionId WHERE id = :id AND status = 'submitting' AND EXISTS " +
        "(SELECT 1 FROM group_members m WHERE m.groupId = group_deliveries.groupId AND m.botId = group_deliveries.botId)")
    suspend fun prepareGroupDelivery(id: String, sessionId: String): Int

    @Query("UPDATE group_deliveries SET status = 'admitted', sessionId = :sessionId, taskId = :taskId WHERE id = :id AND status = 'submitting'")
    suspend fun admitGroupDelivery(id: String, sessionId: String, taskId: String?): Int

    @Query("UPDATE group_deliveries SET status = :status, error = :error WHERE id = :id AND status IN ('pending', 'submitting', 'admitted')")
    suspend fun settleGroupDelivery(id: String, status: String, error: String?): Int

    @Query("UPDATE group_messages SET isStreaming = 0, content = CASE WHEN content = '' THEN :notice ELSE content || char(10) || :notice END " +
        "WHERE id = :id AND isStreaming = 1")
    suspend fun interruptDeliveryReply(id: String, notice: String)

    @Transaction
    suspend fun finishGroupDelivery(id: String, status: String, error: String?) {
        if (settleGroupDelivery(id, status, error) != 1) return
        if (status != GroupDelivery.UNKNOWN && status != GroupDelivery.REJECTED) return
        val receipt = findGroupDelivery(id) ?: return
        val notice = "⚠️ ${error ?: "消息处理结果待核对，请检查会话记录"}"
        interruptDeliveryReply("delivery-reply:$id", notice)
        insertDeliverySource(GroupMessageEntity(
            id = "delivery-status:$id", groupId = receipt.groupId, senderId = "system", senderName = "系统通知",
            senderType = "SYSTEM", content = notice, timestamp = System.currentTimeMillis(), isStreaming = false, delegatedTaskId = null,
            seq = nextMessageSeq(receipt.groupId),
        ))
    }

    @Transaction
    suspend fun recoverGroupDeliveries() {
        interruptedGroupDeliveries().forEach {
            finishGroupDelivery(it.id, GroupDelivery.UNKNOWN, "进程在处理群消息时退出，结果待核对；未自动重新执行")
        }
    }

    @Query("UPDATE group_deliveries SET status = 'cancelled', error = '用户停止或成员已移出' WHERE groupId = :groupId " +
        "AND (:botId IS NULL OR botId = :botId) AND status IN ('pending', 'submitting', 'admitted')")
    suspend fun cancelGroupDeliveries(groupId: String, botId: String? = null)

    @Query(
        "UPDATE group_messages SET isStreaming = 0, " +
            "content = CASE WHEN content = '' THEN :notice ELSE content || char(10) || :notice END " +
            "WHERE isStreaming = 1 AND id NOT LIKE 'delivery-reply:%' " +
            "AND id NOT IN (SELECT placeholderId FROM group_late_replies WHERE status = 'WATCHING')",
    )
    suspend fun settleOrphanStreams(notice: String)
}

val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bot_assignments` ADD COLUMN `reportToBotId` TEXT DEFAULT NULL")
        db.execSQL("UPDATE `agent_approval_settings` SET `mode` = 'full_access'")
        db.execSQL("UPDATE `harness_sessions` SET `approvalMode` = 'full_access'")
        db.execSQL("UPDATE `agent_approval_requests` SET `expiresAt` = MIN(`expiresAt`, `createdAt` + 60000) WHERE `toolName` = 'ask_user' AND `status` = 'pending'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `group_deliveries` (
            `id` TEXT NOT NULL, `sourceMessageId` TEXT NOT NULL, `groupId` TEXT NOT NULL,
            `botId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL,
            `sessionId` TEXT, `taskId` TEXT, `error` TEXT, PRIMARY KEY(`id`),
            FOREIGN KEY(`sourceMessageId`) REFERENCES `group_messages`(`id`) ON DELETE CASCADE,
            FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_group_deliveries_sourceMessageId_botId` ON `group_deliveries` (`sourceMessageId`, `botId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_deliveries_botId` ON `group_deliveries` (`botId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_deliveries_status_createdAt` ON `group_deliveries` (`status`, `createdAt`)")
    }
}

package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow
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
import top.tianyan.app.core.model.bot.BotScheduleDelivery

@Entity(
    tableName = "bot_schedule_deliveries",
    foreignKeys = [ForeignKey(
        entity = BotScheduleEntity::class, parentColumns = ["id"], childColumns = ["scheduleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["scheduleId", "scheduledAt"], unique = true), Index(value = ["status"])],
)
data class BotScheduleDeliveryEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val botId: String,
    val groupId: String?,
    val instruction: String,
    val scheduledAt: Long,
    val createdAt: Long,
    val status: String,
    val error: String?,
) {
    fun toModel() = BotScheduleDelivery(id, scheduleId, botId, groupId, instruction, scheduledAt, createdAt, status, error)
}

@Dao
abstract class BotScheduleDeliveryDao {
    @Query("SELECT * FROM bot_schedules WHERE id = :id AND enabled = 1 AND nextRunAt = :scheduledAt")
    abstract suspend fun occurrence(id: String, scheduledAt: Long): BotScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: BotScheduleDeliveryEntity): Long

    @Query("UPDATE bot_schedules SET lastRunAt = :now, nextRunAt = :next WHERE id = :id")
    abstract suspend fun advance(id: String, now: Long, next: Long)

    /** Persist the occurrence and advance the schedule in the same SQLite transaction. */
    @Transaction
    open suspend fun stage(id: String, scheduledAt: Long, now: Long, next: Long) {
        val schedule = occurrence(id, scheduledAt) ?: return
        insert(BotScheduleDeliveryEntity(
            "$id:$scheduledAt", id, schedule.botId, schedule.groupId, schedule.instruction,
            scheduledAt, now, BotScheduleDelivery.PENDING, null,
        ))
        advance(id, now, next)
    }

    @Query("SELECT * FROM bot_schedule_deliveries WHERE status = 'pending' AND scheduleId IN (SELECT id FROM bot_schedules WHERE enabled = 1) ORDER BY scheduledAt, id LIMIT :limit")
    abstract suspend fun pending(limit: Int): List<BotScheduleDeliveryEntity>

    @Query("SELECT * FROM bot_schedule_deliveries WHERE botId = :botId ORDER BY scheduledAt DESC LIMIT 50")
    abstract fun observeForBot(botId: String): Flow<List<BotScheduleDeliveryEntity>>

    @Query("SELECT MIN(createdAt) FROM bot_schedule_deliveries WHERE status = 'pending' AND scheduleId IN (SELECT id FROM bot_schedules WHERE enabled = 1)")
    abstract suspend fun nextPendingAt(): Long?

    @Query("UPDATE bot_schedule_deliveries SET status = 'admitting' WHERE id = :id AND status = 'pending' AND scheduleId IN (SELECT id FROM bot_schedules WHERE enabled = 1)")
    abstract suspend fun claim(id: String): Int

    @Query("UPDATE bot_schedule_deliveries SET status = :status, error = :error WHERE id = :id AND status = 'admitting'")
    abstract suspend fun finish(id: String, status: String, error: String?)

    // A crash during admission may already have launched effects. Never automatically replay it.
    @Query("UPDATE bot_schedule_deliveries SET status = 'unknown', error = '进程在投递途中退出，请核对会话执行记录' WHERE status = 'admitting'")
    abstract suspend fun recoverInterrupted()
}

val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `bot_schedule_deliveries` (
            `id` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `botId` TEXT NOT NULL,
            `groupId` TEXT, `instruction` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT,
            PRIMARY KEY(`id`), FOREIGN KEY(`scheduleId`) REFERENCES `bot_schedules`(`id`) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_schedule_deliveries_scheduleId_scheduledAt` ON `bot_schedule_deliveries` (`scheduleId`, `scheduledAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_schedule_deliveries_status` ON `bot_schedule_deliveries` (`status`)")
    }
}

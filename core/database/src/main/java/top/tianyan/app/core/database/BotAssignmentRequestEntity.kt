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

@Entity(tableName = "bot_assignment_requests", foreignKeys = [ForeignKey(
    entity = BotAssignmentEntity::class, parentColumns = ["id"], childColumns = ["assignmentId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["assignmentId", "createdAt"]), Index(value = ["status"])])
data class BotAssignmentRequestEntity(
    @PrimaryKey val id: String,
    val assignmentId: String,
    val instruction: String,
    val createdAt: Long,
    val status: String = "pending",
    val nativeTaskId: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val blockedReason: String? = null,
    val artifactsJson: String = "[]",
)

/** Inherited by the assignment owner, keeping native-session creation and task admission atomic. */
@Dao
interface BotAssignmentRequestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIndependentSession(session: HarnessSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIndependentAssignment(assignment: BotAssignmentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssignmentRequest(request: BotAssignmentRequestEntity): Long

    @Query("SELECT * FROM bot_assignment_requests WHERE id = :id")
    suspend fun findAssignmentRequest(id: String): BotAssignmentRequestEntity?

    @Query("SELECT * FROM bot_assignment_requests WHERE id = :id")
    fun observeAssignmentRequest(id: String): Flow<BotAssignmentRequestEntity?>

    @Query("SELECT * FROM bot_assignment_requests WHERE assignmentId = :id ORDER BY rowid DESC LIMIT 50")
    suspend fun assignmentRequests(id: String): List<BotAssignmentRequestEntity>

    @Query("SELECT r.* FROM bot_assignment_requests r JOIN bot_assignments a ON r.assignmentId = a.id " +
        "WHERE r.status = 'pending' AND a.status = 'QUEUED' ORDER BY r.rowid LIMIT 64")
    fun pendingAssignmentRequests(): Flow<List<BotAssignmentRequestEntity>>

    @Query("UPDATE bot_assignments SET status = 'QUEUED', finishedAt = NULL WHERE id = :id AND status != 'RUNNING'")
    suspend fun queueAssignment(id: String)

    @Query("UPDATE bot_assignments SET status = 'RUNNING', startedAt = :now, finishedAt = NULL, revision = revision + 1, " +
        "activeRequestId = :requestId WHERE id = :id AND independent = 1 AND status != 'RUNNING'")
    suspend fun activateAssignment(id: String, requestId: String, now: Long): Int

    @Query("UPDATE bot_assignment_requests SET status = 'submitting' WHERE id = :id AND status = 'pending'")
    suspend fun claimAssignmentRequest(id: String): Int

    @Query("UPDATE bot_assignment_requests SET status = 'admitted', nativeTaskId = :nativeTaskId WHERE id = :id AND status = 'submitting'")
    suspend fun admitAssignmentRequest(id: String, nativeTaskId: String?): Int

    @Query("UPDATE bot_assignment_requests SET blockedReason = :reason, artifactsJson = :artifactsJson " +
        "WHERE id = :id AND status IN ('submitting', 'admitted')")
    suspend fun declareAssignmentBlock(id: String, reason: String?, artifactsJson: String): Int

    @Query("UPDATE bot_assignment_requests SET status = :status, summary = :summary, error = :error " +
        "WHERE id = :id AND status IN ('submitting', 'admitted')")
    suspend fun settleAssignmentRequest(id: String, status: String, summary: String?, error: String?): Int

    @Query("SELECT COUNT(*) FROM bot_assignment_requests WHERE assignmentId = :id AND status = 'pending'")
    suspend fun queuedAssignmentRequests(id: String): Int

    @Query("UPDATE bot_assignment_requests SET status = 'cancelled' WHERE assignmentId = :id AND status IN ('pending','submitting','admitted')")
    suspend fun cancelAssignmentRequests(id: String)

    @Query("UPDATE group_messages SET isStreaming = 0, content = CASE WHEN content = '' THEN :notice ELSE content || char(10) || :notice END " +
        "WHERE delegatedTaskId = :id AND isStreaming = 1")
    suspend fun settleInterruptedAssignmentMessages(id: String, notice: String)

    @Query("SELECT * FROM bot_assignment_requests WHERE status IN ('submitting','admitted')")
    suspend fun interruptedAssignmentRequests(): List<BotAssignmentRequestEntity>
}

val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bot_assignments` ADD COLUMN `independent` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bot_assignments` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bot_assignments` ADD COLUMN `activeRequestId` TEXT DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignments_sessionId` ON `bot_assignments` (`sessionId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `bot_assignment_requests` (
            `id` TEXT NOT NULL, `assignmentId` TEXT NOT NULL, `instruction` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `nativeTaskId` TEXT,
            `summary` TEXT, `error` TEXT, `blockedReason` TEXT, `artifactsJson` TEXT NOT NULL,
            PRIMARY KEY(`id`), FOREIGN KEY(`assignmentId`) REFERENCES `bot_assignments`(`id`) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignment_requests_assignmentId_createdAt` ON `bot_assignment_requests` (`assignmentId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignment_requests_status` ON `bot_assignment_requests` (`status`)")
    }
}

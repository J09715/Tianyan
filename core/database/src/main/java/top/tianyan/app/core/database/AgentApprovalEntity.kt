package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.RunMode

@Entity(tableName = "agent_approval_requests")
data class AgentApprovalRequestEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val workspace: String,
    val riskLevel: String,
    val reason: String,
    val summary: String,
    val status: String = STATUS_PENDING,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** 审批创建时所属的 harness operation；恢复执行前校验归属，防止跨运行重放。 */
    val operationId: String? = null,
    /** argumentsJson 的 SHA-256 摘要；执行前复核，防止“批准的是旧参数、执行的是新参数”。 */
    val argsHash: String = "",
    /** 审批过期时间（epoch ms）；到期未决的请求自动失效。旧数据默认永不过期。 */
    val expiresAt: Long = Long.MAX_VALUE,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXECUTED = "executed"
        const val STATUS_FAILED = "failed"
        const val STATUS_EXPIRED = "expired"
    }
}

@Entity(tableName = "agent_approval_settings")
data class AgentApprovalSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val mode: String = ApprovalMode.FULL_ACCESS.id,
    /** 全局默认运行意图（BUILD / PLAN）：新会话创建时的初始值。 */
    @ColumnInfo(defaultValue = "build") val runMode: String = RunMode.BUILD.id,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface AgentApprovalDao {
    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' ORDER BY createdAt ASC")
    fun observePendingForSession(sessionId: String): Flow<List<AgentApprovalRequestEntity>>

    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' ORDER BY createdAt ASC")
    suspend fun listPendingForSession(sessionId: String): List<AgentApprovalRequestEntity>

    @Query("SELECT * FROM agent_approval_requests WHERE id = :id LIMIT 1")
    suspend fun findRequest(id: String): AgentApprovalRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(request: AgentApprovalRequestEntity)

    @Query("UPDATE agent_approval_requests SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateRequestStatus(id: String, status: String, resolvedAt: Long)

    /** Atomically claims a pending request so duplicate approve taps cannot execute it twice. */
    @Query("UPDATE agent_approval_requests SET status = :status, resolvedAt = :resolvedAt WHERE id = :id AND status = 'pending'")
    suspend fun claimPendingRequest(id: String, status: String, resolvedAt: Long): Int

    /** 将到期未决的 pending 请求整体置为 expired（惰性清扫，读写路径都会触发）。 */
    @Query("UPDATE agent_approval_requests SET status = 'expired', resolvedAt = :now WHERE status = 'pending' AND expiresAt <= :now")
    suspend fun expirePendingApprovals(now: Long): Int



    /** 查询指定会话中有效的未过期 pending 请求。 */
    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' AND expiresAt > :now ORDER BY createdAt ASC")
    suspend fun listUnexpiredPendingForSession(sessionId: String, now: Long): List<AgentApprovalRequestEntity>

    /** 查询指定会话中到期未决的 pending 请求。 */
    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' AND expiresAt <= :now ORDER BY createdAt ASC")
    suspend fun listExpiredPendingForSession(sessionId: String, now: Long): List<AgentApprovalRequestEntity>

    @Query("DELETE FROM agent_approval_requests WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT * FROM agent_approval_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AgentApprovalSettingsEntity?>

    @Query("SELECT * FROM agent_approval_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AgentApprovalSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AgentApprovalSettingsEntity)
}

class AgentApprovalRepository(
    private val dao: AgentApprovalDao,
) {
    val mode: Flow<ApprovalMode> = dao.observeSettings().map { ApprovalMode.fromId(it?.mode) }
    val runMode: Flow<RunMode> = dao.observeSettings().map { RunMode.fromId(it?.runMode) }

    fun pendingForSession(sessionId: String): Flow<List<AgentApprovalRequestEntity>> =
        dao.observePendingForSession(sessionId).map { requests ->
            // 到期的请求先从展示层过滤掉；终态写入由 sweepExpired / resolveApproval 完成。
            val now = System.currentTimeMillis()
            requests.filter { it.expiresAt > now }
        }

    /** 读取当前有效的未过期 pending 请求；终态写入由 sweepExpired / resolveApproval 完成。 */
    suspend fun pendingNow(sessionId: String, now: Long = System.currentTimeMillis()): List<AgentApprovalRequestEntity> =
        dao.listUnexpiredPendingForSession(sessionId, now)

    /**
     * 清扫指定会话中所有到期未决的请求，将其原子性置为 STATUS_EXPIRED，并返回被过期的请求列表。
     */
    suspend fun sweepExpiredForSession(
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): List<AgentApprovalRequestEntity> {
        val expired = dao.listExpiredPendingForSession(sessionId, now)
        if (expired.isEmpty()) return emptyList()
        val claimed = mutableListOf<AgentApprovalRequestEntity>()
        for (request in expired) {
            if (dao.claimPendingRequest(request.id, AgentApprovalRequestEntity.STATUS_EXPIRED, now) > 0) {
                claimed.add(request.copy(status = AgentApprovalRequestEntity.STATUS_EXPIRED, resolvedAt = now))
            }
        }
        return claimed
    }

    suspend fun currentMode(): ApprovalMode = ApprovalMode.fromId(dao.getSettings()?.mode)

    suspend fun currentRunMode(): RunMode = RunMode.fromId(dao.getSettings()?.runMode)

    /** 只改审批模式，保留既有运行意图（upsert 是 REPLACE 语义，必须显式回读另一列）。 */
    suspend fun setMode(mode: ApprovalMode) {
        val existing = dao.getSettings()
        dao.upsertSettings(AgentApprovalSettingsEntity(mode = mode.id, runMode = existing?.runMode ?: RunMode.BUILD.id))
    }

    /** 只改运行意图，保留既有审批模式。 */
    suspend fun setRunMode(mode: RunMode) {
        val existing = dao.getSettings()
        dao.upsertSettings(AgentApprovalSettingsEntity(mode = existing?.mode ?: ApprovalMode.FULL_ACCESS.id, runMode = mode.id))
    }

    suspend fun create(request: AgentApprovalRequestEntity) = dao.upsertRequest(request)

    suspend fun find(id: String): AgentApprovalRequestEntity? = dao.findRequest(id)

    suspend fun mark(id: String, status: String) =
        dao.updateRequestStatus(id, status, System.currentTimeMillis())

    suspend fun claimPending(id: String, status: String): Boolean =
        dao.claimPendingRequest(id, status, System.currentTimeMillis()) > 0

    suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}

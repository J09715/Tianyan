package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 审批审计台账（借鉴 DSH approval/asked/decided 审计对持久化的思路）。
 *
 * 定位：只登记 **agent_approval_requests 表覆盖不到的审批事实**——
 * 「为什么这次没问」的三类决策点：
 * - [KIND_GRANT_WRITTEN]：用户批准时勾选「本会话内记住」，授权类别被写入会话授权表；
 * - [KIND_GRANT_EXEMPTED]：某次调用因会话内授权豁免而免审批直接执行；
 * - [KIND_DEFERRED]：子智能体后台 Lane 不支持暂停审批，调用被结构化交接（approvalDeferred）。
 *
 * asked / 用户批准 / 拒绝 / 过期 / 执行结果已在 agent_approval_requests 的
 * status + resolvedAt 里持久化，不在此重复登记。
 *
 * 安全姿态对齐既有边界：授权本身仍是纯内存、per-session、无永久授权；本表只留
 * 审计痕迹，不参与授权判定。随会话删除一并清理（同 deleteForSession 语义）。
 */
@Entity(
    tableName = "agent_approval_audits",
    indices = [Index(value = ["sessionId", "createdAt"])],
)
data class AgentApprovalAuditEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** grant_written | grant_exempted | deferred */
    val kind: String,
    val toolName: String,
    val toolCallId: String? = null,
    val riskLevel: String = "",
    /** 命中/写入的授权类别键（cmd:/dir:/mcp:/exact: 前缀，见 SessionApprovalGrants）。 */
    val grantKey: String? = null,
    val approvalRequestId: String? = null,
    val detail: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val KIND_GRANT_WRITTEN = "grant_written"
        const val KIND_GRANT_EXEMPTED = "grant_exempted"
        const val KIND_DEFERRED = "deferred"
    }
}

@Dao
interface AgentApprovalAuditDao {
    @Insert
    suspend fun insert(entry: AgentApprovalAuditEntity)

    @Query("SELECT * FROM agent_approval_audits WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<AgentApprovalAuditEntity>>

    @Query("SELECT * FROM agent_approval_audits WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listForSession(sessionId: String, limit: Int): List<AgentApprovalAuditEntity>

    @Query("SELECT COUNT(*) FROM agent_approval_audits WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("DELETE FROM agent_approval_audits WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

class AgentApprovalAuditRepository(private val dao: AgentApprovalAuditDao) {

    suspend fun record(entry: AgentApprovalAuditEntity) = dao.insert(entry)

    fun observeForSession(sessionId: String): Flow<List<AgentApprovalAuditEntity>> =
        dao.observeForSession(sessionId)

    suspend fun listForSession(sessionId: String, limit: Int = 200): List<AgentApprovalAuditEntity> =
        dao.listForSession(sessionId, limit)

    suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}

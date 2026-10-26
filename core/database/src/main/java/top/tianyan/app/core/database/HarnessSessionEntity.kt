package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.RedTeamMode
import top.tianyan.app.core.model.RedTeamPhase
import top.tianyan.app.core.model.RunMode

/** Harness 会话：一条会话聚合一批消息，并记录使用的模型。 */
@Entity(tableName = "harness_sessions", indices = [Index(value = ["updatedAt"])])
data class HarnessSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Bound model profile id. Null keeps legacy fallback to the global default profile. */
    val modelId: String?,
    /** Concrete provider model id selected inside [modelId]'s profile. */
    val modelVariant: String? = null,
    val workspace: String = "",
    /** Explicit type selected for empty/imported workspaces; blank means auto-detect. */
    val projectType: String = "",
    /** Tool approval authority for this session; new sessions inherit the global default. */
    val approvalMode: String = ApprovalMode.FULL_ACCESS.id,
    /** 运行意图（BUILD/PLAN），与 [approvalMode] 正交；新会话继承全局默认。 */
    @ColumnInfo(defaultValue = "build") val runMode: String = RunMode.BUILD.id,
    /** Pre-SSH history binds to the local distro on first use; portable imports require a new session. */
    @ColumnInfo(defaultValue = "'unbound'") val executionTargetJson: String? = null,
    /**
     * 红队能力是**按会话开启**的，不继承全局默认。
     *
     * 默认 OFF：普通会话不该因为存在这个功能就带上红队工具集与提示词。
     * 会话一旦绑定目标，该会话的作战状态（目标/范围/阶段）与事实库都只属于它自己。
     */
    @ColumnInfo(defaultValue = "off") val redTeamMode: String = RedTeamMode.OFF.id,
    /** 用户确认过的目标单元、主机、URL 或 CIDR，仅对本会话生效。 */
    @ColumnInfo(defaultValue = "NULL") val redTeamTarget: String? = null,
    /** 换行分隔的授权范围条目；为空表示仍需用户确认范围。 */
    @ColumnInfo(defaultValue = "") val redTeamScope: String = "",
    @ColumnInfo(defaultValue = "idle") val redTeamPhase: String = RedTeamPhase.IDLE.id,
)

@Dao
interface HarnessSessionDao {
    @Query("UPDATE harness_sessions SET executionTargetJson = :target WHERE id = :id AND (executionTargetJson IS NULL OR executionTargetJson = 'unbound')")
    suspend fun bindExecutionTargetIfAbsent(id: String, target: String)

    @androidx.room.Transaction
    suspend fun bindExecutionTarget(id: String, target: String): String? {
        bindExecutionTargetIfAbsent(id, target)
        return findById(id)?.executionTargetJson
    }

    @Query("SELECT * FROM harness_sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HarnessSessionEntity>>

    @Query("SELECT * FROM harness_sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): HarnessSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: HarnessSessionEntity)

    @androidx.room.Transaction
    suspend fun upsert(session: HarnessSessionEntity) {
        val bound = findById(session.id)?.executionTargetJson
        insert(if (bound == null) session else session.copy(executionTargetJson = bound))
    }

    @Query("UPDATE harness_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE harness_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE harness_sessions SET approvalMode = :approvalMode, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long)

    @Query("UPDATE harness_sessions SET approvalMode = :approvalMode, updatedAt = :updatedAt")
    suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long)

    @Query("UPDATE harness_sessions SET runMode = :runMode, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRunMode(id: String, runMode: String, updatedAt: Long)

    @Query("UPDATE harness_sessions SET modelId = :modelId, modelVariant = :modelVariant, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long)

    /** 红队绑定一次性写入四列：mode/target/scope/phase 必须同时生效，不能分几次改。 */
    @Query("UPDATE harness_sessions SET redTeamMode = :mode, redTeamTarget = :target, redTeamScope = :scope, redTeamPhase = :phase, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRedTeamBinding(id: String, mode: String, target: String?, scope: String, phase: String, updatedAt: Long)

    @Query("DELETE FROM harness_sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: String)

    @Query("DELETE FROM bot_group_sessions WHERE sessionId = :id")
    suspend fun deleteBotGroupSession(id: String)

    @Query("DELETE FROM bot_assignments WHERE sessionId = :id")
    suspend fun deleteBotAssignments(id: String)

    @androidx.room.Transaction
    suspend fun deleteSession(id: String) {
        deleteBotAssignments(id)
        deleteBotGroupSession(id)
        deleteSessionRow(id)
    }

    @Query("SELECT COUNT(*) FROM harness_sessions WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end)")
    suspend fun countInRange(start: Long?, end: Long?): Int

    @Query("SELECT * FROM harness_sessions")
    suspend fun listAll(): List<HarnessSessionEntity>
}

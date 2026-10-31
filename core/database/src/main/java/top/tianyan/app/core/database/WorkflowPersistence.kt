package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.workflow.BuiltinWorkflows
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.core.model.workflow.WorkflowTrigger
import top.tianyan.app.core.model.workflow.WorkflowValidator

@Entity(
    tableName = "workflows",
    indices = [Index(value = ["slashCommand"])],
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isBuiltin: Boolean,
    val slashCommand: String?,
    val jsonContent: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflow_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workflowId"), Index("startTime")],
)
data class WorkflowExecutionLogEntity(
    @PrimaryKey val executionId: String,
    val workflowId: String,
    val startTime: Long,
    val endTime: Long?,
    val status: String,
    val finalContextJson: String,
    // 触发来源（MANUAL/SCHEDULE）与定时计划回链；workflowName 冗余存储，
    // 供定时计划列表不经 JSON 解码即可展示上次结果
    @ColumnInfo(defaultValue = "MANUAL") val triggerSource: String = "MANUAL",
    @ColumnInfo(defaultValue = "") val workflowName: String = "",
    val scheduleId: String? = null,
)

@Entity(
    tableName = "workflow_schedules",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workflowId"), Index("nextRunAt")],
)
data class WorkflowScheduleEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val name: String,
    val enabled: Boolean,
    // WorkflowScheduleRepeat.name：DAILY / INTERVAL / ONCE
    val repeatType: String,
    // DAILY 用：每天 hour:minute 触发（设备本地时区）
    val hour: Int?,
    val minute: Int?,
    // INTERVAL 用：间隔分钟数（WorkManager 周期下限 15）
    val intervalMinutes: Int?,
    // ONCE 用：一次性触发的目标时刻
    val onceAtEpochMillis: Long?,
    // JSON 对象：{"KEY":"VALUE"}
    val variablesJson: String,
    val workspacePath: String,
    val modelId: String?,
    val modelVariant: String?,
    val lastExecutionId: String?,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
    val createdAt: Long,
)

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY isBuiltin DESC, updatedAt DESC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflows WHERE slashCommand = :command LIMIT 1")
    suspend fun findBySlashCommand(command: String): WorkflowEntity?

    @Upsert
    suspend fun upsert(entity: WorkflowEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String): Int

    @Query("SELECT * FROM workflow_execution_logs ORDER BY startTime DESC LIMIT :limit")
    fun observeRecentExecutions(limit: Int): Flow<List<WorkflowExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExecution(entity: WorkflowExecutionLogEntity)

    @Transaction
    suspend fun saveRun(parent: WorkflowEntity, log: WorkflowExecutionLogEntity) {
        insertIfMissing(parent)
        upsertExecution(log)
    }

    @Transaction
    suspend fun upsertAll(entities: List<WorkflowEntity>) {
        entities.forEach { upsert(it) }
    }

    /** 启动对账：找出进程死亡时遗留的非终态运行（Room 现在随运行推进持续 upsert RUNNING 行）。 */
    @Query("SELECT * FROM workflow_execution_logs WHERE status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED')")
    suspend fun findUnfinishedExecutions(): List<WorkflowExecutionLogEntity>

    @Query("SELECT * FROM workflow_execution_logs WHERE executionId = :id LIMIT 1")
    suspend fun findExecutionById(id: String): WorkflowExecutionLogEntity?
}

@Dao
interface WorkflowScheduleDao {
    @Query("SELECT * FROM workflow_schedules ORDER BY enabled DESC, nextRunAt IS NULL, nextRunAt")
    fun observeSchedules(): Flow<List<WorkflowScheduleEntity>>

    @Query("SELECT * FROM workflow_schedules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WorkflowScheduleEntity?

    @Upsert
    suspend fun upsert(entity: WorkflowScheduleEntity)

    @Query("DELETE FROM workflow_schedules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE workflow_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        "UPDATE workflow_schedules SET lastExecutionId = :executionId, lastRunAt = :runAt, nextRunAt = :nextRunAt WHERE id = :id",
    )
    suspend fun updateRunInfo(id: String, executionId: String?, runAt: Long, nextRunAt: Long?)
}

/** 定时计划存储端口：harness 侧业务依赖本接口而非 Room DAO（架构铁律）。 */
interface WorkflowScheduleStore {
    fun observeSchedules(): Flow<List<WorkflowScheduleEntity>>
    suspend fun findSchedule(id: String): WorkflowScheduleEntity?
    suspend fun upsert(entity: WorkflowScheduleEntity)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun updateRunInfo(id: String, executionId: String?, runAt: Long, nextRunAt: Long?)
}

class RoomWorkflowScheduleStore(
    private val dao: WorkflowScheduleDao,
) : WorkflowScheduleStore {
    override fun observeSchedules(): Flow<List<WorkflowScheduleEntity>> = dao.observeSchedules()
    override suspend fun findSchedule(id: String): WorkflowScheduleEntity? = dao.findById(id)
    override suspend fun upsert(entity: WorkflowScheduleEntity) = dao.upsert(entity)
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
    override suspend fun updateRunInfo(id: String, executionId: String?, runAt: Long, nextRunAt: Long?) =
        dao.updateRunInfo(id, executionId, runAt, nextRunAt)
}

interface WorkflowRepository {
    fun observeDefinitions(): Flow<List<WorkflowDefinition>>
    fun observeRecentExecutions(limit: Int = 5): Flow<List<WorkflowExecutionLogEntity>>
    fun observeHistory(): Flow<List<WorkflowRuntimeState>> = observeRecentExecutions().map { logs ->
        val decoder = Json { ignoreUnknownKeys = true }
        logs.mapNotNull { runCatching { decoder.decodeFromString<WorkflowRuntimeState>(it.finalContextJson) }.getOrNull() }
    }
    suspend fun findById(id: String): WorkflowDefinition?
    suspend fun findBySlashCommand(command: String): WorkflowDefinition?
    suspend fun upsert(definition: WorkflowDefinition)
    suspend fun deleteCustom(id: String): Boolean
    suspend fun ensureBuiltins()
    suspend fun saveExecution(
        state: WorkflowRuntimeState,
        triggerSource: String = "MANUAL",
        scheduleId: String? = null,
    )
    suspend fun findUnfinishedExecutions(): List<WorkflowExecutionLogEntity>
    suspend fun findExecutionById(id: String): WorkflowExecutionLogEntity?
}

class RoomWorkflowRepository(
    private val dao: WorkflowDao,
    private val json: Json,
) : WorkflowRepository {
    override fun observeDefinitions(): Flow<List<WorkflowDefinition>> = dao.observeAll().map { entities ->
        entities.mapNotNull(::decode)
    }

    override fun observeRecentExecutions(limit: Int) = dao.observeRecentExecutions(limit.coerceIn(1, 200))

    override suspend fun findById(id: String) = dao.findById(id)?.let(::decode)

    override suspend fun findBySlashCommand(command: String) = dao.findBySlashCommand(command.trim())?.let(::decode)

    override suspend fun upsert(definition: WorkflowDefinition) {
        val issues = WorkflowValidator.validate(definition)
        require(issues.isEmpty()) { issues.joinToString(separator = "; ") { it.message } }
        dao.upsert(definition.toEntity())
    }

    override suspend fun deleteCustom(id: String): Boolean = dao.deleteCustom(id) > 0

    override suspend fun ensureBuiltins() {
        dao.upsertAll(BuiltinWorkflows.all.map { it.toEntity() })
    }

    override suspend fun saveExecution(
        state: WorkflowRuntimeState,
        triggerSource: String,
        scheduleId: String?,
    ) {
        // The catalog exposes built-ins optimistically before Room initialization finishes.
        // Ensure the parent exists so a very fast run cannot violate the execution FK.
        dao.saveRun(
            (state.baseDefinition ?: state.definition).toEntity(),
            WorkflowExecutionLogEntity(
                executionId = state.executionId,
                workflowId = state.definition.id,
                startTime = state.startedAt ?: System.currentTimeMillis(),
                endTime = state.finishedAt,
                status = state.status.name,
                finalContextJson = json.encodeToString(state),
                triggerSource = triggerSource,
                workflowName = state.definition.name,
                scheduleId = scheduleId,
            ),
        )
    }

    override suspend fun findUnfinishedExecutions() = dao.findUnfinishedExecutions()

    override suspend fun findExecutionById(id: String) = dao.findExecutionById(id)

    private fun WorkflowDefinition.toEntity(): WorkflowEntity {
        val now = System.currentTimeMillis()
        val normalized = copy(
            createdAt = createdAt.takeIf { it > 0 } ?: now,
            updatedAt = updatedAt.takeIf { it > 0 } ?: now,
        )
        return WorkflowEntity(
            id = id,
            name = name,
            description = description,
            category = category,
            isBuiltin = isBuiltin,
            slashCommand = (trigger as? WorkflowTrigger.Manual)?.slashCommand,
            jsonContent = json.encodeToString(normalized),
            updatedAt = normalized.updatedAt,
        )
    }

    private fun decode(entity: WorkflowEntity): WorkflowDefinition? =
        runCatching { json.decodeFromString<WorkflowDefinition>(entity.jsonContent) }.getOrNull()
}

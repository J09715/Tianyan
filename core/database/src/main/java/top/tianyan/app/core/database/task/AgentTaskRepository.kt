package top.tianyan.app.core.database.task

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stable persistence port for durable Agent runs. Harness never talks to the Room DAO directly. */
interface AgentTaskRepository {
    fun observeAll(): Flow<List<AgentTaskEntity>>
    fun observeTask(id: String): Flow<AgentTaskEntity?> = observeAll().map { tasks -> tasks.find { it.id == id } }
    suspend fun find(id: String): AgentTaskEntity?
    suspend fun listByStatus(statuses: List<String>): List<AgentTaskEntity>
    suspend fun listForSession(sessionId: String, statuses: List<String>): List<AgentTaskEntity>
    suspend fun upsert(task: AgentTaskEntity)
    suspend fun transition(transition: AgentTaskTransition): Boolean
    suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean
    suspend fun deleteForSession(sessionId: String)
    suspend fun delete(id: String)
    suspend fun updateGroupProgress(id: String, progress: String) {}
}

data class AgentTaskTransition(
    val id: String,
    val expectedStatuses: List<String>,
    val nextStatus: String,
    val errorMessage: String? = null,
    val statusDetail: String? = null,
    val operationId: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val nextRunAt: Long? = null,
    val attemptIncrement: Int = 0,
    val updatedAt: Long,
)

data class AgentTaskCheckpoint(
    val id: String,
    val operationId: String?,
    val lastRound: Int,
    val maxRounds: Int,
    val progress: Float,
    val statusDetail: String?,
    val updatedAt: Long,
)

class RoomAgentTaskRepository(
    private val dao: AgentTaskDao,
) : AgentTaskRepository {
    override fun observeAll() = dao.observeAllTasks()
    override fun observeTask(id: String) = dao.observeTask(id)
    override suspend fun find(id: String) = dao.getTaskById(id)
    override suspend fun listByStatus(statuses: List<String>) = dao.listTasksByStatus(statuses)
    override suspend fun listForSession(sessionId: String, statuses: List<String>) =
        dao.listSessionTasksByStatus(sessionId, statuses)

    override suspend fun upsert(task: AgentTaskEntity) = dao.upsertTask(task)

    override suspend fun transition(transition: AgentTaskTransition): Boolean =
        dao.transition(
            id = transition.id,
            expectedStatuses = transition.expectedStatuses,
            nextStatus = transition.nextStatus,
            errorMessage = transition.errorMessage,
            statusDetail = transition.statusDetail,
            operationId = transition.operationId,
            startedAt = transition.startedAt,
            completedAt = transition.completedAt,
            nextRunAt = transition.nextRunAt,
            attemptIncrement = transition.attemptIncrement,
            updatedAt = transition.updatedAt,
        ) == 1

    override suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean =
        dao.checkpoint(
            id = checkpoint.id,
            operationId = checkpoint.operationId,
            lastRound = checkpoint.lastRound,
            maxRounds = checkpoint.maxRounds,
            progress = checkpoint.progress.coerceIn(0f, 1f),
            statusDetail = checkpoint.statusDetail,
            updatedAt = checkpoint.updatedAt,
        ) == 1

    override suspend fun deleteForSession(sessionId: String) = dao.deleteTasksForSession(sessionId)
    override suspend fun delete(id: String) = dao.deleteTask(id)
    override suspend fun updateGroupProgress(id: String, progress: String) = dao.updateGroupProgress(id, progress)
}

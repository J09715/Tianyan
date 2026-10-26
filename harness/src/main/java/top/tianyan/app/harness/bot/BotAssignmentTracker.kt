package top.tianyan.app.harness.bot

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotWorkBatch

data class ActiveBotAssignment(
    val taskId: String,
    val groupId: String?,
    val botName: String,
    val startedAt: Long,
)

/** 持久化 + 进程内 Job 句柄：群聊据此显示执行态、停止任务并查询历史。 */
class BotAssignmentTracker(
    private val repository: BotAssignmentRepository,
) {
    private val recoveryLock = Mutex()
    private var recovered = false
    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobGroups = ConcurrentHashMap<String, String>()
    private val _active = MutableStateFlow<List<ActiveBotAssignment>>(emptyList())
    val active: StateFlow<List<ActiveBotAssignment>> = _active.asStateFlow()

    suspend fun start(assignment: BotAssignment, job: Job?) {
        recoverOrphaned()
        repository.save(assignment)
        track(assignment, job)
    }

    fun track(assignment: BotAssignment, job: Job?) {
        if (job != null) {
            jobs[assignment.id] = job
            assignment.groupId?.let { jobGroups[assignment.id] = it }
            job.invokeOnCompletion {
                jobs.remove(assignment.id, job)
                jobGroups.remove(assignment.id)
            }
        }
        _active.update { current ->
            current.filterNot { it.taskId == assignment.id } + ActiveBotAssignment(
                taskId = assignment.id,
                groupId = assignment.groupId,
                botName = assignment.botName,
                startedAt = assignment.startedAt,
            )
        }
    }

    suspend fun finish(
        taskId: String,
        status: BotAssignmentStatus,
        summary: String? = null,
        error: String? = null,
    ) {
        repository.finish(taskId, status, summary, error)
        _active.update { current -> current.filterNot { it.taskId == taskId } }
    }

    suspend fun cancel(taskId: String) {
        val job = jobs[taskId]
        try { repository.cancelAssignment(taskId) } finally { job?.cancel() }
        val task = repository.get(taskId)
        val descendants = task?.groupId?.let { listForGroup(it) }.orEmpty()
        val ids = mutableSetOf(taskId)
        repeat(3) { descendants.filter { it.parentAssignmentId in ids }.forEach { ids += it.id } }
        ids.forEach { jobs[it]?.cancel() }
        _active.update { current -> current.filterNot { it.taskId in ids } }
    }

    suspend fun cancelGroup(groupId: String) {
        val tasks = listForGroup(groupId).filter { it.status.isActive }
        val pending = jobGroups.filterValues { it == groupId }.keys.mapNotNull { jobs[it] }
        tasks.forEach { assignment ->
            cancel(assignment.id)
        }
        // 清空会话前排空 finally 中的结果通知，防止删除后又写回旧消息。
        pending.joinAll()
        _active.update { current -> current.filterNot { it.groupId == groupId } }
    }

    fun untrack(taskId: String) {
        _active.update { current -> current.filterNot { it.taskId == taskId } }
    }

    fun observeForGroup(groupId: String): Flow<List<BotAssignment>> = repository.observeForGroup(groupId)

    suspend fun listForGroup(groupId: String): List<BotAssignment> =
        repository.observeForGroup(groupId).first()

    suspend fun listRecent(limit: Int = 20): List<BotAssignment> = repository.listRecent(limit)

    suspend fun get(taskId: String): BotAssignment? = repository.get(taskId)

    suspend fun getBatch(batchId: String): BotWorkBatch? = repository.getBatch(batchId)

    suspend fun batchAssignments(batchId: String): List<BotAssignment> = repository.batchAssignments(batchId)

    suspend fun listBatches(groupId: String, limit: Int = 20): List<BotWorkBatch> = repository.listBatches(groupId, limit)

    suspend fun recoverOrphaned() = recoveryLock.withLock {
        if (!recovered) {
            repository.failRunning()
            recovered = true
        }
    }
}

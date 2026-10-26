package top.tianyan.app.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState

/** Failed checkpoints remain observable; terminal snapshots get bounded retries. */
internal class WorkflowHistoryWriter(
    private val repository: WorkflowRepository,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = failures.asStateFlow()
    private val writeLock = Mutex()
    private data class Version(val resume: Int, val sequence: Int, val terminal: Boolean)
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Version>()

    suspend fun save(state: WorkflowRuntimeState, source: String, scheduleId: String?): Boolean = writeLock.withLock {
        val version = Version(state.resumeCount, state.journal.size, state.status in WorkflowRunManager.TERMINAL)
        val previous = versions[state.executionId]
        if (previous != null && (previous.resume > version.resume || previous.resume == version.resume &&
                (previous.sequence > version.sequence || previous.sequence == version.sequence && previous.terminal && !version.terminal))) return@withLock true
        val attempts = if (state.status in WorkflowRunManager.TERMINAL) 3 else 1
        repeat(attempts) { attempt ->
            try {
                repository.saveExecution(state, source, scheduleId)
                versions[state.executionId] = version
                failures.update { it - state.executionId }
                return@withLock true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures.update { current ->
                    (current - state.executionId + (state.executionId to (error.message ?: "工作流历史保存失败")))
                        .entries.toList().takeLast(64).associate { it.key to it.value }
                }
                reportFailure("保存工作流历史失败：${state.executionId} / ${state.status}（${attempt + 1}/$attempts）", error)
                if (attempt + 1 < attempts) delay(500L * (attempt + 1))
            }
        }
        false
    }

    fun forget(executionId: String) { failures.update { it - executionId }; versions.remove(executionId) }
}

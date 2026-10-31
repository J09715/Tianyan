package top.tianyan.app.harness.operation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.HarnessOperationEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.session.SessionTreeStore

/** User inspection/deletion shares the tool settlement lock; it never changes the recovery journal. */
class FailureMemoryAccess internal constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val settlementMutex: Mutex,
) {
    private val revision = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): Flow<FailureMemoryState?> =
        combine(repository.observeLanes(sessionId), revision) { _, version -> version }
            .transformLatest {
                // Lane commits refresh tool/provider changes; the timer removes expired rows even while idle.
                while (true) {
                    val state = inspect(sessionId, laneName)
                    emit(state)
                    val expiry = state?.records?.minOfOrNull { it.expiresAt } ?: break
                    delay((expiry - System.currentTimeMillis()).coerceAtLeast(1L))
                }
            }.distinctUntilChanged()

    suspend fun inspect(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): FailureMemoryState? =
        settlementMutex.withLock {
            val operation = current(sessionId, laneName) ?: return@withLock null
            val snapshot = json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)
            FailureMemoryState(operation.id, FailureMemory.valid(snapshot.failureMemory, operation.id, System.currentTimeMillis()))
        }

    /** Delete only reviewed source results, preserving failures that arrived since the user opened the panel. */
    suspend fun delete(sessionId: String, reviewed: FailureMemoryState,
        laneName: String = SessionTreeStore.MAIN_LANE): Boolean = settlementMutex.withLock {
        val operation = current(sessionId, laneName) ?: return@withLock false
        if (operation.id != reviewed.operationId) return@withLock false
        val snapshot = json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)
        val records = FailureMemory.valid(snapshot.failureMemory, operation.id, System.currentTimeMillis())
        val sources = reviewed.records.map { it.signature to it.resultEntryId }.toSet()
        val remaining = records.filterNot { (it.signature to it.resultEntryId) in sources }
        repository.saveOperation(operation.copy(stateJson = json.encodeToString(OperationSnapshot.serializer(),
            snapshot.copy(failureMemory = FailureMemoryState(operation.id, remaining)))))
        revision.update { it + 1 }
        true
    }

    private suspend fun current(sessionId: String, laneName: String): HarnessOperationEntity? {
        val lane = repository.findLane(sessionId, laneName) ?: return null
        val operation = lane.currentOperationId?.let { repository.findOperation(it) } ?: return null
        check(operation.sessionId == sessionId && operation.laneName == laneName) { "Failure memory operation ownership mismatch" }
        return operation.takeIf { repository.findLane(sessionId, laneName)?.currentOperationId == it.id }
    }
}

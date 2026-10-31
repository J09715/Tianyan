package top.tianyan.app.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowExecutionLogEntity
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.core.model.workflow.WorkflowJournalEntry
import top.tianyan.app.core.model.workflow.WorkflowJournalKind

class WorkflowHistoryWriterTest {
    private fun state(status: WorkflowRunStatus) = WorkflowRuntimeState.initial(
        "exec_test", WorkflowDefinition(id = "wf_test", name = "test", description = "", category = "test", nodes = emptyList(), edges = emptyList()),
    ).copy(status = status)

    @Test fun terminalSnapshotRetriesAndPreservesTriggerProvenance() = runTest {
        val repository = RecordingRepository().apply { failuresRemaining = 2 }
        val reports = mutableListOf<String>()
        val writer = WorkflowHistoryWriter(repository) { message, _ -> reports += message }
        assertTrue(writer.save(state(WorkflowRunStatus.SUCCESS), "SCHEDULE", "schedule_1"))
        assertEquals(3, repository.attempts)
        assertEquals(2, reports.size)
        assertEquals("SCHEDULE", repository.savedSource)
        assertEquals("schedule_1", repository.savedSchedule)
        assertEquals(WorkflowRunStatus.SUCCESS, repository.savedState?.status)
        assertTrue(writer.errors.value.isEmpty())
    }

    @Test fun persistentFailureRemainsObservableAfterBoundedRetries() = runTest {
        val repository = RecordingRepository().apply { failuresRemaining = 10 }
        val writer = WorkflowHistoryWriter(repository) { _, _ -> }
        assertFalse(writer.save(state(WorkflowRunStatus.FAILED), "MANUAL", null))
        assertEquals(3, repository.attempts)
        assertEquals("storage unavailable", writer.errors.value["exec_test"])
        assertNull(repository.savedState)
    }

    @Test fun runningCheckpointFailureCanBeRetriedAndClearsErrorOnSuccess() = runTest {
        val repository = RecordingRepository().apply { failuresRemaining = 1 }
        val writer = WorkflowHistoryWriter(repository) { _, _ -> }
        assertFalse(writer.save(state(WorkflowRunStatus.RUNNING), "MANUAL", null))
        assertEquals(1, repository.attempts)
        assertFalse(writer.errors.value.isEmpty())
        assertTrue(writer.save(state(WorkflowRunStatus.RUNNING), "MANUAL", null))
        assertTrue(writer.errors.value.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutRetryOrPersistenceError() = runTest {
        val repository = RecordingRepository().apply { cancel = true }
        val writer = WorkflowHistoryWriter(repository) { _, _ -> error("Cancellation should not be logged as a storage failure") }
        val result = runCatching { writer.save(state(WorkflowRunStatus.SUCCESS), "MANUAL", null) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, repository.attempts)
        assertTrue(writer.errors.value.isEmpty())
    }

    @Test fun olderSnapshotsCannotOverwriteCommittedJournalOrNewContinuation() = runTest {
        val repository = RecordingRepository()
        val writer = WorkflowHistoryWriter(repository) { _, _ -> }
        val started = WorkflowJournalEntry(1, "a", 1, WorkflowJournalKind.STARTED, 1L)
        val newer = state(WorkflowRunStatus.RUNNING).copy(journalVersion = 1, journal = listOf(started))
        assertTrue(writer.save(newer, "MANUAL", null))
        assertTrue(writer.save(state(WorkflowRunStatus.RUNNING), "MANUAL", null))
        assertEquals(newer, repository.savedState)
        val terminal = newer.copy(status = WorkflowRunStatus.CANCELLED)
        assertTrue(writer.save(terminal, "MANUAL", null))
        assertTrue(writer.save(newer, "MANUAL", null))
        assertEquals(terminal, repository.savedState)
        val resumed = newer.copy(resumeCount = 1)
        assertTrue(writer.save(resumed, "MANUAL", null))
        assertTrue(writer.save(terminal, "MANUAL", null))
        assertEquals(resumed, repository.savedState)
    }

    private class RecordingRepository : WorkflowRepository {
        var failuresRemaining = 0
        var attempts = 0
        var cancel = false
        var savedState: WorkflowRuntimeState? = null
        var savedSource: String? = null
        var savedSchedule: String? = null
        override suspend fun saveExecution(state: WorkflowRuntimeState, triggerSource: String, scheduleId: String?) {
            attempts++
            if (cancel) throw CancellationException("cancelled")
            if (failuresRemaining-- > 0) error("storage unavailable")
            savedState = state; savedSource = triggerSource; savedSchedule = scheduleId
        }
        override fun observeDefinitions(): Flow<List<WorkflowDefinition>> = error("unused")
        override fun observeRecentExecutions(limit: Int): Flow<List<WorkflowExecutionLogEntity>> = error("unused")
        override suspend fun findById(id: String): WorkflowDefinition? = error("unused")
        override suspend fun findBySlashCommand(command: String): WorkflowDefinition? = error("unused")
        override suspend fun upsert(definition: WorkflowDefinition): Unit = error("unused")
        override suspend fun deleteCustom(id: String): Boolean = error("unused")
        override suspend fun ensureBuiltins(): Unit = error("unused")
        override suspend fun findUnfinishedExecutions(): List<WorkflowExecutionLogEntity> = error("unused")
        override suspend fun findExecutionById(id: String): WorkflowExecutionLogEntity? = error("unused")
    }
}

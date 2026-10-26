package top.tianyan.app.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.task.RoomAgentTaskRepository
import top.tianyan.app.harness.*
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.queue.*
import top.tianyan.app.harness.task.AgentStateMachine
import top.tianyan.app.harness.recovery.RecoveryManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionInputPersistenceTest {
    private class Fixture(context: Context) : SessionInputRuntime {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sessions = RoomHarnessSessionRepository(db.harnessSessionDao())
        val repository = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
        val store = SessionTreeStore(repository, Json, AppLogger(context, SensitiveDataRedactor { it }))
        val queues = PromptQueueManager(repository, Json, store)
        val taskRepository = RoomAgentTaskRepository(db.agentTaskDao())
        val tasks = AgentStateMachine(taskRepository)
        val operations = OperationCoordinator(repository, Json, HarnessEventBus())
        val recorder = QueuedInstructionRecorder(tasks, sessions, { _, _, _ -> }) { false }
        var busy = false
        val controller = SessionInputController(SessionTurnCoordinatorImpl(), this)
        override suspend fun exists(sessionId: String) = sessions.findById(sessionId) != null
        override fun busy(sessionId: String) = busy
        override suspend fun settleExpiredApprovals(sessionId: String) = Unit
        override suspend fun hasNextRun(sessionId: String) = queues.first(sessionId, PromptQueue.NEXT_RUN) != null
        override suspend fun record(sessionId: String, input: PendingMessage) = recorder.record(sessionId, input)
        override suspend fun enqueue(sessionId: String, queue: PromptQueue, input: PendingMessage) = queues.enqueue(sessionId, queue, input)
        override suspend fun start(sessionId: String, input: PendingMessage): Boolean {
            operations.acceptRun(sessionId, UserMessage("user-${input.taskId}", 1, input.text, input.imageUrls), taskId = input.taskId)
            busy = true
            return true
        }
        override suspend fun startNext(sessionId: String) = Unit
        override suspend fun refresh(sessionId: String) = Unit
    }
    private fun contract(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        try {
            fixture.sessions.upsert(HarnessSessionEntity("s", "新会话", 1, 1, modelId = null))
            block(fixture)
        } finally { fixture.db.close() }
    }

    @Test fun receiptNamesCommittedTaskAndUserEntryIncludingImages() = contract { f ->
        val receipt = f.controller.submit("s", "describe", listOf("data:image/png;base64,test"), PromptQueue.NEXT_RUN)
            as PromptSubmission.Accepted
        val task = f.taskRepository.find(requireNotNull(receipt.taskId))!!
        assertEquals("describe", task.description)
        val message = f.store.load("s").single() as UserMessage
        assertEquals(listOf("data:image/png;base64,test"), message.imageUrls)
        assertNotNull(task.operationId)
    }

    @Test fun queuedReceiptCanBeReloadedThroughFreshQueueManager() = contract { f ->
        f.busy = true
        val receipt = f.controller.submit("s", "later", listOf("image"), PromptQueue.NEXT_RUN) as PromptSubmission.Accepted
        val restored = PromptQueueManager(f.repository, Json, f.store).list("s", PromptQueue.NEXT_RUN).single()
        assertEquals(receipt.queueItemId, restored.first)
        assertEquals(receipt.taskId, restored.second.taskId)
        assertEquals(listOf("image"), restored.second.imageUrls)
        assertNotNull(f.taskRepository.find(requireNotNull(receipt.taskId)))
        assertTrue(f.store.load("s").isEmpty())
    }

    @Test fun steeringAndFollowUpPersistDistinctQueuesWithoutCreatingTasks() = contract { f ->
        f.busy = true
        for (queue in listOf(PromptQueue.STEER, PromptQueue.FOLLOW_UP)) {
            val receipt = f.controller.submit("s", queue.id, emptyList(), queue) as PromptSubmission.Accepted
            assertNull(receipt.taskId)
            assertEquals(receipt.queueItemId, f.queues.list("s", queue).single().first)
        }
        assertTrue(f.tasks.queued().isEmpty())
    }

    @Test fun imageOnlyInputHasNonBlankTaskDescriptionAndOriginalEmptyText() = contract { f ->
        val receipt = f.controller.submit("s", "", listOf("image"), PromptQueue.NEXT_RUN) as PromptSubmission.Accepted
        assertFalse(f.taskRepository.find(requireNotNull(receipt.taskId))!!.description.isBlank())
        assertEquals("", (f.store.load("s").single() as UserMessage).text)
    }

    @Test fun missingSessionCannotLeaveTaskOrLaneInRoom() = contract { f ->
        val result = f.controller.submit("deleted", "hello", emptyList(), PromptQueue.NEXT_RUN)
        assertEquals(PromptSubmission.Rejected(PromptSubmission.Rejection.SESSION_NOT_FOUND), result)
        assertTrue(f.tasks.queued().isEmpty())
        assertNull(f.repository.findLane("deleted", "main"))
    }

    @Test fun committedAdmissionBeforeJobLaunchSurvivesRuntimeRecreation() = contract { f ->
        val input = PendingMessage("", listOf("original-image"), taskId = "admitted")
        f.recorder.record("s", input)
        val operationId = f.operations.acceptRun("s", UserMessage("accepted", 1, input.text, input.imageUrls), taskId = input.taskId)
        // Process death here: no Job or provider request has been started.
        val freshTasks = AgentStateMachine(RoomAgentTaskRepository(f.db.agentTaskDao()))
        val freshOperations = OperationCoordinator(f.repository, Json, HarnessEventBus())
        val recovery = RecoveryManager(
            f.repository, freshOperations, json = Json, eventBus = HarnessEventBus())
        recovery.recoverSession("s")
        val recoverable = freshTasks.recoverable().single()
        assertEquals("admitted", recoverable.id)
        assertEquals(operationId, recoverable.operationId)
        assertEquals(1, recoverable.attemptCount)
        assertEquals(operationId, freshOperations.active("s")!!.id)
        assertTrue(f.queues.list("s", PromptQueue.NEXT_RUN).isEmpty())
        val persisted = f.store.loadStrict("s").single() as UserMessage
        assertEquals("", persisted.text)
        assertEquals(listOf("original-image"), persisted.imageUrls)
        assertTrue(freshTasks.markRecovering(recoverable.id, "restart"))
        assertTrue(freshTasks.markRunning(recoverable.id, operationId))
    }

    @Test fun queuedAdmissionCannotLoseRecoveryStateAfterQueueConsumption() = contract { f ->
        f.busy = true
        val receipt = f.controller.submit("s", "queued", listOf("image"), PromptQueue.NEXT_RUN) as PromptSubmission.Accepted
        val (_, pending) = f.queues.first("s", PromptQueue.NEXT_RUN)!!
        val operationId = f.operations.acceptQueuedRun("s", receipt.queueItemId!!,
            UserMessage("queued-user", 1, pending.text, pending.imageUrls), receipt.taskId)
        assertTrue(f.queues.list("s", PromptQueue.NEXT_RUN).isEmpty())
        assertEquals(operationId, f.tasks.recoverable().single().operationId)
        assertEquals(listOf("image"), (f.store.loadStrict("s").single() as UserMessage).imageUrls)
    }
}

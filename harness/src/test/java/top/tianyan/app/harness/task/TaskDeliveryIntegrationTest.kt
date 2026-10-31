package top.tianyan.app.harness.task

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.task.AgentTaskEntity
import top.tianyan.app.harness.*
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.operation.ReplayPolicy
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.security.SecretRedactor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskDeliveryIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private val json = Json { ignoreUnknownKeys = true }
    @Before fun prepare() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
    }
    @After fun close() { db.close() }
    private suspend fun start(coordinator: OperationCoordinator): String {
        db.agentTaskDao().upsertTask(AgentTaskEntity("task", "s", "goal", "goal", "QUEUED", 1, 1))
        return coordinator.acceptRun("s", UserMessage("input", 1, "goal"), taskId = "task")
    }

    @Test fun actualCommittedRunRestoresItsReportAfterProcessRestart() = runBlocking {
        val coordinator = OperationCoordinator(repository, json, HarnessEventBus())
        val operation = start(coordinator)
        val call = ToolCall("call", 2, HarnessTool.BASE, buildJsonObject { })
        coordinator.toolIntent(operation, call, "{}", ReplayPolicy.NEVER, 1)
        coordinator.toolSettled(operation, ToolResult("result", 3, call.id, true, "success"), 1)
        coordinator.providerSettled(operation, AssistantText("final", 4, "done"), round = 2)
        val delivery = coordinator.completeTask("s", "task")
        val fresh = SessionTreeStore(RoomHarnessRuntimeRepository(db.harnessRuntimeDao()), json,
            AppLogger(ApplicationProvider.getApplicationContext<Context>(), SecretRedactor()))
        val restored = fresh.loadStrict("s").filterIsInstance<TaskDelivery>().single()
        assertEquals(delivery, restored)
        assertEquals("final", restored.report.sourceLeafId)
        assertEquals("final", restored.report.assistantClaimEntryId)
        assertEquals("result", restored.report.observations.single().resultEntryId)
        assertEquals(listOf(DeliveryGap.GOAL_NOT_VERIFIED), restored.report.gaps)
        assertEquals("COMPLETED", db.agentTaskDao().getTaskById("task")!!.status)
        assertEquals(delivery.id, repository.findLane("s", "main")!!.leafId)
        assertNull(repository.findOperation(operation))
        // Rewinding removes the report from the selected branch while keeping the immutable record.
        fresh.moveTo("s", "input")
        assertTrue(fresh.loadStrict("s").none { it is TaskDelivery })
        assertNotNull(repository.findEntry("s", delivery.id))
    }

    @Test fun readFailureBeforeCompletionCannotPublishPhantomSuccess() = runBlocking {
        val coordinator = OperationCoordinator(repository, json, HarnessEventBus())
        val operation = start(coordinator)
        coordinator.providerSettled(operation, AssistantText("final", 2, "done"), round = 1)
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> = error("storage unavailable")
        }
        assertNotNull(runCatching { OperationCoordinator(failing, json, HarnessEventBus()).completeTask("s", "task") }.exceptionOrNull())
        assertEquals("RUNNING", db.agentTaskDao().getTaskById("task")!!.status)
        assertNotNull(repository.findOperation(operation))
        assertEquals("final", repository.findLane("s", "main")!!.leafId)
        assertTrue(repository.listEntries("s").none { it.customType == "task_delivery" })
    }

    @Test fun taskCancellationDuringEvidenceReadRollsBackCompletion() = runBlocking {
        val coordinator = OperationCoordinator(repository, json, HarnessEventBus())
        val operation = start(coordinator)
        coordinator.providerSettled(operation, AssistantText("final", 2, "done"), round = 1)
        val cancelOnRead = object : HarnessRuntimeRepository by repository {
            override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> {
                val entries = repository.branchTail(sessionId, leafId, limit)
                val task = db.agentTaskDao().getTaskById("task")!!
                db.agentTaskDao().upsertTask(task.copy(status = "CANCELLED"))
                return entries
            }
        }
        assertNotNull(runCatching { OperationCoordinator(cancelOnRead, json, HarnessEventBus()).completeTask("s", "task") }.exceptionOrNull())
        assertEquals("CANCELLED", db.agentTaskDao().getTaskById("task")!!.status)
        assertNotNull(repository.findOperation(operation))
        assertTrue(repository.listEntries("s").none { it.customType == "task_delivery" })
    }
}

package top.tianyan.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.task.AgentTaskEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskDeliveryTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    @Before fun prepare() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
    }
    @After fun close() { db.close() }
    private val lane = HarnessLaneEntity("s", "main", "source", "op", updatedAt = 1)
    private val result = HarnessLaneResultEntity("s", "main", "op", "completed", "source", completedAt = 2)
    private fun report(payload: String = "report") = HarnessEntryEntity(id = "delivery", sessionId = "s",
        parentId = "source", createdAt = 2, entryType = "message", customType = "task_delivery", payloadJson = payload)
    private suspend fun seed(status: String = "RUNNING", session: String = "s", taskOperation: String = "op") {
        db.agentTaskDao().upsertTask(AgentTaskEntity("task", session, "goal", "input", status, 1, 1, operationId = taskOperation))
        if (repository.findEntry("s", "source") == null) repository.appendToLane("s", "main", HarnessEntryEntity(id = "source", sessionId = "s", parentId = null,
            createdAt = 1, entryType = "message", customType = "assistant", payloadJson = "{}"))
        repository.beginOperation(lane, HarnessOperationEntity("op", "s", "main", "run", "running", "provider_settled", 1, 1, null, "{}"))
    }
    private suspend fun assertNotCompleted(expectedStatus: String = "RUNNING") {
        assertEquals(expectedStatus, db.agentTaskDao().getTaskById("task")!!.status)
        assertEquals(lane, repository.findLane("s", "main"))
        assertNotNull(repository.findOperation("op"))
    }

    @Test fun completionPublishesReportAndTaskTerminalStateTogether() = runBlocking {
        seed()
        repository.enqueue(HarnessQueueItemEntity("next", "s", "main", "op", "next_run", 1, "input"))
        repository.completeTaskOperation("task", result, lane, report())
        val task = db.agentTaskDao().getTaskById("task")!!
        assertEquals("COMPLETED", task.status)
        assertEquals(1f, task.progress, 0f)
        assertEquals(2L, task.completedAt)
        assertEquals("delivery", repository.findLane("s", "main")!!.leafId)
        assertNull(repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findOperation("op"))
        assertEquals("report", repository.findEntry("s", "delivery")!!.payloadJson)
        assertNull(repository.listQueue("s", "main", "next_run").single().operationId)
    }

    @Test fun taskCancellationRollsBackInsertedReportAndPreservesOperation() = runBlocking {
        seed(status = "CANCELLED")
        assertNotNull(runCatching { repository.completeTaskOperation("task", result, lane, report()) }.exceptionOrNull())
        assertNotCompleted("CANCELLED")
        assertNull(repository.findEntry("s", "delivery"))
    }

    @Test fun reportInsertConflictCannotMarkTaskComplete() = runBlocking {
        seed()
        db.harnessRuntimeDao().insertEntry(report("original"))
        assertNotNull(runCatching { repository.completeTaskOperation("task", result, lane, report("new")) }.exceptionOrNull())
        assertNotCompleted()
        assertEquals("original", repository.findEntry("s", "delivery")!!.payloadJson)
    }

    @Test fun staleLaneSnapshotCannotAttachReportToAnotherBranch() = runBlocking {
        seed()
        repository.appendToLane("s", "main", HarnessEntryEntity(id = "new-leaf", sessionId = "s", parentId = "source",
            createdAt = 2, entryType = "message", customType = "assistant", payloadJson = "{}"))
        assertNotNull(runCatching { repository.completeTaskOperation("task", result, lane, report()) }.exceptionOrNull())
        assertEquals("RUNNING", db.agentTaskDao().getTaskById("task")!!.status)
        assertEquals("new-leaf", repository.findLane("s", "main")!!.leafId)
        assertNotNull(repository.findOperation("op"))
        assertNull(repository.findEntry("s", "delivery"))
    }

    @Test fun taskOwnerOrOperationMismatchRollsBackReport() = runBlocking {
        for ((owner, operation) in listOf("other" to "op", "s" to "different")) {
            seed(session = owner, taskOperation = operation)
            assertNotNull(runCatching { repository.completeTaskOperation("task", result, lane, report()) }.exceptionOrNull())
            assertNotCompleted()
            assertNull(repository.findEntry("s", "delivery"))
        }
    }
}

package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.model.bot.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignmentRecoveryPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "lead", "dev").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", "", null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
        listOf("chief", "lead", "dev").forEachIndexed { i, id -> groups.addMember("g", id, i) }
        groups.saveTeam("g", mapOf("dev" to "lead"))
        tasks.createIndependent(BotAssignment("parent", "g", "lead", "lead", "parent", "work", BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "parent-session", independent = true, reportToBotId = "chief", batchId = "outer"),
            HarnessSessionEntity("parent-session", "parent", 1, 1, null, workspace = "/workspace/project", executionTargetJson = "frozen-target"),
            BotAssignmentRequest("initial:parent", "parent", "work", 1),
            BotWorkBatch("outer", "g", "chief", "group-chief", null, 1, status = BotWorkBatchStatus.WAITING_CHILDREN, createdAt = 1, updatedAt = 1))
        tasks.claimRequest("initial:parent", 2)
        tasks.createIndependent(BotAssignment("child", "g", "dev", "dev", "child", "implement", BotAssignmentStatus.QUEUED,
            createdAt = 3, startedAt = 3, sessionId = "child-session", independent = true, reportToBotId = "lead", parentAssignmentId = "parent"),
            HarnessSessionEntity("child-session", "child", 3, 3, null), BotAssignmentRequest("initial:child", "child", "implement", 3))
        tasks.claimRequest("initial:child", 4)
    }

    private fun recreated() = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)

    @Test fun `received child receipts remain distinct from cancelled work after recovery`() = runBlocking {
        seed()
        tasks.finishRequest("initial:child", true, false, "verified artifact", null)
        tasks.acknowledgeSynchronousResult("child", 1, "COMPLETED", "parent-session")
        val receiptId = childResultReceiptId("child", 1, "COMPLETED")
        val receipt = tasks.getRequest(receiptId)!!
        assertEquals(BotAssignmentRequest.RECEIVED, receipt.status)
        assertTrue(receipt.summary!!.contains("仍待原任务验收"))
        assertNull(receipt.nativeTaskId)
        val restored = recreated()
        restored.recoverRequests()
        assertEquals(BotAssignmentStatus.INTERRUPTED, restored.get("parent")!!.status)
        assertTrue(restored.pendingRequests().first().isEmpty())
        restored.enqueueRequest(BotAssignmentRequest("manual", "parent", "先只读核验已有产物", 10))
        assertEquals(BotAssignmentRequest.RECEIVED, restored.getRequest(receiptId)!!.status)
        assertEquals(listOf("manual"), restored.pendingRequests().first().map { it.id })
    }

    @Test fun `unreceived results survive interruption while old execution instructions are not replayed`() = runBlocking {
        seed()
        tasks.finishRequest("initial:child", true, false, "verified artifact", null)
        tasks.enqueueRequest(BotAssignmentRequest("old-followup", "parent", "old execution instruction", 5))
        val original = tasks.get("parent")!!
        val receiptId = childResultReceiptId("child", 1, "COMPLETED")
        val restored = recreated()
        restored.recoverRequests()
        restored.recoverRequests()
        assertEquals(BotAssignmentRequest.UNKNOWN, restored.getRequest("initial:parent")!!.status)
        assertEquals(BotAssignmentStatus.INTERRUPTED, restored.get("parent")!!.status)
        assertTrue(restored.pendingRequests().first().isEmpty())
        restored.enqueueRequest(BotAssignmentRequest("manual", "parent", "先只读核验已有产物", 10))
        assertEquals(BotAssignmentRequest.CANCELLED, restored.getRequest("old-followup")!!.status)
        assertTrue(restored.getRequest(receiptId)!!.instruction.contains("verified artifact"))
        assertEquals(listOf("manual"), restored.pendingRequests().first().map { it.id })
        assertTrue(restored.claimRequest("manual", 11))
        assertEquals(original.sessionId, restored.get("parent")!!.sessionId)
        assertEquals(original.budgetId, restored.get("parent")!!.budgetId)
        val session = db.harnessSessionDao().findById("parent-session")!!
        assertEquals("/workspace/project", session.workspace)
        assertEquals("frozen-target", session.executionTargetJson)
    }

    @Test fun `child interruption settling before its parent cannot revive the parent after recovery`() = runBlocking {
        seed()
        tasks.finishRequest("initial:parent", true, false, "waiting", null)
        tasks.enqueueRequest(BotAssignmentRequest("parent-followup", "parent", "checking child", 5))
        tasks.claimRequest("parent-followup", 6)
        // Exercise the adversarial recovery order explicitly, independent of SQLite row order.
        tasks.finishRequest("initial:child", false, true, null, "child result unknown")
        val receiptId = childResultReceiptId("child", 1, "INTERRUPTED")
        assertEquals(BotAssignmentRequest.PENDING, tasks.getRequest(receiptId)!!.status)
        val restored = recreated()
        restored.recoverRequests()
        restored.reconcileWaitingBatches(7)
        assertEquals(BotAssignmentStatus.INTERRUPTED, restored.get("parent")!!.status)
        assertEquals(BotAssignmentRequest.UNKNOWN, restored.getRequest("parent-followup")!!.status)
        assertTrue(restored.pendingRequests().first().isEmpty())
        assertTrue(restored.getRequest(receiptId)!!.instruction.contains("待核对"))
    }

    @Test fun `never started result delivery resumes once in the original session after repository recreation`() = runBlocking {
        seed()
        tasks.finishRequest("initial:parent", true, false, "waiting", null)
        tasks.finishRequest("initial:child", true, false, "verified artifact", null)
        val receiptId = childResultReceiptId("child", 1, "COMPLETED")
        val restored = recreated()
        restored.recoverRequests()
        assertEquals(listOf(receiptId), restored.pendingRequests().first().map { it.id })
        assertTrue(restored.claimRequest(receiptId, 6))
        assertFalse(restored.claimRequest(receiptId, 7))
        assertEquals("parent-session", restored.get("parent")!!.sessionId)
        restored.finishRequest(receiptId, true, false, "accepted artifact", null)
        restored.reconcileWaitingBatches(8)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
        assertEquals(BotAssignmentStatus.COMPLETED, restored.get("parent")!!.status)
    }
}

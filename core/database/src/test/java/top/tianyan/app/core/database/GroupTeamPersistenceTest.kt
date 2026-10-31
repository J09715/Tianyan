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
class GroupTeamPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = db.botAssignmentDao()
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "lead", "dev", "peer").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
        listOf("chief", "lead", "dev", "peer").forEachIndexed { i, id -> groups.addMember("g", id, i) }
    }

    private suspend fun create(id: String, worker: String, requester: String, batchId: String? = null,
        parentId: String? = null, source: String = "chief-session") {
        val task = BotAssignment(id, "g", worker, worker, id, "implement $id", BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "$id-session", reportToBotId = requester,
            independent = true, batchId = batchId, parentAssignmentId = parentId)
        val batch = batchId?.let { BotWorkBatch(it, "g", requester, source, null, revision = 1,
            status = BotWorkBatchStatus.WAITING_CHILDREN, createdAt = 1, updatedAt = 1) }
        tasks.createIndependent(task.toEntity(Json), HarnessSessionEntity("$id-session", id, 1, 1, null),
            BotAssignmentRequestEntity("initial:$id", id, task.task, 1), batch?.toEntity())
    }

    @Test fun `default team remains flat and relationships are group local`() = runBlocking {
        seed()
        assertEquals(setOf("lead", "dev", "peer"), groups.getTeam("g").directReports("chief"))
        groups.saveTeam("g", mapOf("dev" to "lead"))
        assertEquals("lead", groups.getTeam("g").supervisorOf("dev"))
        assertFalse(groups.getTeam("g").canAssign("chief", "dev"))
        assertTrue(groups.getTeam("g").canInspect("chief", "dev"))
        groups.saveGroup(GroupChat("other", "Other", "chief", createdAt = 1))
        listOf("chief", "lead", "dev").forEachIndexed { i, id -> groups.addMember("other", id, i) }
        assertEquals("chief", groups.getTeam("other").supervisorOf("dev"))
    }

    @Test fun `invalid organization edits are atomic`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        listOf(mapOf("lead" to "dev", "dev" to "lead"), mapOf("dev" to "missing"),
            mapOf("chief" to "lead"), mapOf("lead" to "chief", "dev" to "lead", "peer" to "dev")).forEach { invalid ->
            assertTrue(runCatching { groups.saveTeam("g", invalid) }.isFailure)
            assertEquals(mapOf("dev" to "lead"), groups.getTeam("g").supervisors)
        }
    }

    @Test fun `removing supervisor restores direct reports without resurrecting old relationships`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        groups.removeMember("g", "lead")
        assertEquals("chief", groups.getTeam("g").supervisorOf("dev"))
        groups.addMember("g", "lead", 1)
        assertEquals("chief", groups.getTeam("g").supervisorOf("dev"))
        assertTrue(runCatching { groups.removeMember("g", "chief") }.isFailure)
    }

    @Test fun `database rejects skipping a level and team changes preserve existing report ownership`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        assertTrue(runCatching { create("bad", "dev", "chief") }.isFailure)
        assertNull(db.harnessSessionDao().findById("bad-session"))
        create("ok", "dev", "lead")
        groups.saveTeam("g", emptyMap())
        assertEquals("lead", tasks.findById("ok")!!.reportToBotId)
        assertTrue(tasks.claimRequest("initial:ok", 2))
        tasks.finishRequest("initial:ok", true, false, "done", null, 3)
        assertEquals("lead", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `nested completion resumes the original parent session before reporting upwards`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        assertTrue(tasks.claimRequest("initial:parent", 2))
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.finishRequest("initial:parent", true, false, "delegated", null, 3)
        assertEquals("WAITING_CHILDREN", tasks.findById("parent")!!.status)
        tasks.reconcileWaitingBatches(4)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
        assertTrue(tasks.claimRequest("initial:child", 5))
        tasks.finishRequest("initial:child", true, false, "verified artifact", null, 6)
        val resume = tasks.findAssignmentRequest("child-batch:inner:1")!!
        assertEquals("parent", resume.assignmentId)
        assertEquals("parent-session", tasks.findById(resume.assignmentId)!!.sessionId)
        assertEquals("QUEUED", tasks.findById("parent")!!.status)
        tasks.reconcileWaitingBatches(7)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
        assertTrue(tasks.claimRequest(resume.id, 8))
        tasks.finishRequest(resume.id, true, false, "accepted", null, 9)
        assertEquals("COMPLETED", tasks.findById("parent")!!.status)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
        tasks.reconcileWaitingBatches(10)
        assertEquals(1, groups.pendingDeliveries().first().size)
    }

    @Test fun `synchronous child results stay in the parent turn without a second group delivery`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        assertTrue(tasks.claimRequest("initial:parent", 2))
        listOf("success", "failure", "uncertain", "cancel").forEachIndexed { i, id ->
            create(id, "dev", "lead", parentId = "parent", source = "parent-session")
            assertTrue(tasks.claimRequest("initial:$id", 3L + i))
            if (id == "cancel") tasks.cancelAssignment(id, 10)
            else tasks.finishRequest("initial:$id", id == "success", id == "uncertain", id, null, 10)
            val child = tasks.findById(id)!!
            tasks.acknowledgeSynchronousResult(id, child.revision, child.status, "another-session")
            assertEquals(1, tasks.queuedAssignmentRequests("parent"))
            tasks.acknowledgeSynchronousResult(id, child.revision, child.status, "parent-session")
            assertTrue(groups.pendingDeliveries().first().isEmpty())
            assertEquals("RUNNING", tasks.findById("parent")!!.status)
            assertEquals(0, tasks.queuedAssignmentRequests("parent"))
        }
        tasks.finishRequest("initial:parent", true, false, "checked all tool results", null, 20)
        assertEquals("COMPLETED", tasks.findById("parent")!!.status)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `asynchronous followup to a synchronous child waits and resumes the original parent`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", parentId = "parent", source = "parent-session")
        tasks.claimRequest("initial:child", 3)
        tasks.finishRequest("initial:child", true, false, "first result", null, 4)
        tasks.acknowledgeSynchronousResult("child", 1, "COMPLETED", "parent-session")
        tasks.enqueueRequest(BotAssignmentRequestEntity("supplement", "child", "fix and verify", 5))
        tasks.claimRequest("supplement", 6)
        tasks.finishRequest("initial:parent", true, false, "waiting for supplement", null, 7)
        assertEquals("WAITING_CHILDREN", tasks.findById("parent")!!.status)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
        tasks.finishRequest("supplement", true, false, "verified second result", null, 8)
        val receipt = childResultReceiptId("child", 2, "COMPLETED")
        assertEquals("parent", tasks.findAssignmentRequest(receipt)!!.assignmentId)
        assertTrue(tasks.findAssignmentRequest(receipt)!!.instruction.contains("verified second result"))
        assertEquals("QUEUED", tasks.findById("parent")!!.status)
        assertEquals("parent-session", tasks.findById("parent")!!.sessionId)
        tasks.recoverRequests(9)
        assertEquals(1, tasks.queuedAssignmentRequests("parent"))
        assertTrue(tasks.claimRequest(receipt, 10))
        tasks.finishRequest(receipt, true, false, "accepted supplement", null, 11)
        assertEquals("COMPLETED", tasks.findById("parent")!!.status)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `late followup reopens a completed parent once without a group session delivery`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", parentId = "parent", source = "parent-session")
        tasks.claimRequest("initial:child", 3)
        tasks.finishRequest("initial:child", true, false, "first", null, 4)
        tasks.acknowledgeSynchronousResult("child", 1, "COMPLETED", "parent-session")
        tasks.finishRequest("initial:parent", true, false, "accepted first", null, 5)
        tasks.enqueueRequest(BotAssignmentRequestEntity("supplement", "child", "extra verification", 6))
        tasks.claimRequest("supplement", 7)
        tasks.finishRequest("supplement", false, false, "late result", null, 8)
        assertEquals("QUEUED", tasks.findById("parent")!!.status)
        val revision = tasks.findBatch("outer")!!.revision
        tasks.stageAssignmentReport(tasks.findById("child")!!, "FAILED", 9, "late result", null)
        assertEquals(revision, tasks.findBatch("outer")!!.revision)
        assertEquals(1, tasks.queuedAssignmentRequests("parent"))
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
        val receipt = childResultReceiptId("child", 2, "FAILED")
        assertTrue(tasks.claimRequest(receipt, 10))
        tasks.finishRequest(receipt, true, false, "handled failure", null, 11)
        assertEquals(2, groups.pendingDeliveries().first().size)
        assertTrue(groups.pendingDeliveries().first().all { it.botId == "chief" })
    }

    @Test fun `an initial child result survives the parent synchronous wait ending early`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", parentId = "parent", source = "parent-session")
        tasks.claimRequest("initial:child", 3)
        tasks.finishRequest("initial:parent", true, false, "yielded before child returned", null, 4)
        assertEquals("WAITING_CHILDREN", tasks.findById("parent")!!.status)
        tasks.finishRequest("initial:child", true, false, "late child result", null, 5)
        val receipt = childResultReceiptId("child", 1, "COMPLETED")
        tasks.acknowledgeSynchronousResult("child", 1, "COMPLETED", "parent-session")
        assertEquals("pending", tasks.findAssignmentRequest(receipt)!!.status)
        assertTrue(tasks.claimRequest(receipt, 6))
        tasks.finishRequest(receipt, true, false, "verified late result", null, 7)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `cancelling an unbatched child wakes its waiting parent to handle cancellation`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", parentId = "parent", source = "parent-session")
        tasks.claimRequest("initial:child", 3)
        tasks.finishRequest("initial:parent", true, false, "waiting", null, 4)
        tasks.cancelAssignment("child", 5)
        val receipt = childResultReceiptId("child", 1, "CANCELLED")
        assertTrue(tasks.findAssignmentRequest(receipt)!!.instruction.contains("已取消"))
        assertTrue(tasks.claimRequest(receipt, 6))
        tasks.finishRequest(receipt, true, false, "handled cancellation", null, 7)
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `cancelled ancestry rejects new child followups until the original parent is explicitly restored`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.cancelAssignment("parent", 3)
        val revision = tasks.findBatch("inner")!!.revision
        val resume = BotAssignmentRequestEntity("resume-child", "child", "verify before continuing", 4)
        assertTrue(runCatching { tasks.enqueueRequest(resume) }.isFailure)
        assertNull(tasks.findAssignmentRequest(resume.id))
        assertEquals(revision, tasks.findBatch("inner")!!.revision)
        assertEquals("CANCELLED", tasks.findById("child")!!.status)
        tasks.enqueueRequest(BotAssignmentRequestEntity("restore-parent", "parent", "verify and restore", 5))
        tasks.enqueueRequest(resume)
        assertEquals("QUEUED", tasks.findById("child")!!.status)
        assertTrue(tasks.claimRequest(resume.id, 6))
        assertEquals("RUNNING", tasks.findById("child")!!.status)
    }

    @Test fun `legacy pending requests under cancelled parents are fenced at claim time`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.cancelAssignment("parent", 3)
        tasks.queueAssignment("child")
        tasks.insertAssignmentRequest(BotAssignmentRequestEntity("legacy-late", "child", "late followup", 4))
        assertFalse(tasks.claimRequest("legacy-late", 5))
        assertEquals("cancelled", tasks.findAssignmentRequest("legacy-late")!!.status)
        assertEquals("CANCELLED", tasks.findById("child")!!.status)
        assertEquals("CANCELLED", tasks.findById("parent")!!.status)
    }

    @Test fun `cancellation traverses a failed intermediate task to stop running grandchildren`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.claimRequest("initial:child", 3)
        // Later organization edits do not change the original task chain.
        groups.saveTeam("g", mapOf("peer" to "dev"))
        create("grandchild", "peer", "dev", "deep", "child", "child-session")
        tasks.claimRequest("initial:grandchild", 4)
        tasks.finishRequest("initial:child", false, false, "failed after dispatch", "failed", 5)
        assertEquals("FAILED", tasks.findById("child")!!.status)
        tasks.cancelAssignment("parent", 6)
        assertEquals("CANCELLED", tasks.findById("grandchild")!!.status)
        assertEquals("cancelled", tasks.findAssignmentRequest("initial:grandchild")!!.status)
        assertEquals("FAILED", tasks.findById("child")!!.status)
        assertTrue(runCatching { tasks.enqueueRequest(BotAssignmentRequestEntity("deep-resume", "grandchild", "continue", 7)) }.isFailure)
        tasks.finishRequest("initial:grandchild", true, false, "late", null, 8)
        assertEquals("CANCELLED", tasks.findById("grandchild")!!.status)
        assertEquals("CANCELLED", tasks.findById("parent")!!.status)
    }

    @Test fun `cancelling descendants does not reopen a completed intermediate task`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.claimRequest("initial:child", 3)
        groups.saveTeam("g", mapOf("peer" to "dev"))
        create("grandchild", "peer", "dev", "deep", "child", "child-session")
        tasks.claimRequest("initial:grandchild", 4)
        // A legacy completed intermediate may still have a live descendant.
        tasks.settleAssignment("child", "COMPLETED", 5, "historical completion", null)
        tasks.cancelAssignment("parent", 6)
        assertEquals("CANCELLED", tasks.findById("parent")!!.status)
        assertEquals("CANCELLED", tasks.findById("grandchild")!!.status)
        assertEquals("COMPLETED", tasks.findById("child")!!.status)
        assertNull(tasks.findAssignmentRequest("child-batch:deep:1"))
        assertTrue(tasks.pendingAssignmentRequests().first().isEmpty())
    }

    @Test fun `cancelling a parent cancels descendants and rejects late resurrection`() = runBlocking {
        seed()
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "outer")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent", "parent-session")
        tasks.claimRequest("initial:child", 3)
        tasks.cancelAssignment("parent", 4)
        tasks.finishRequest("initial:child", true, false, "late", null, 5)
        assertEquals("CANCELLED", tasks.findById("parent")!!.status)
        assertEquals("CANCELLED", tasks.findById("child")!!.status)
        assertTrue(tasks.pendingAssignmentRequests().first().isEmpty())
        assertTrue(runCatching { create("late-child", "dev", "lead", "late", "parent", "parent-session") }.isFailure)
    }
}

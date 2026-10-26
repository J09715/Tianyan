package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentRequest
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotWorkBatch
import top.tianyan.app.core.model.bot.BotWorkBatchStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotWorkBatchPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = db.groupChatDao()

    @After fun close() = db.close()

    private suspend fun seed() {
        val chief = BotEntity("chief", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1)
        db.personaBotDao().upsert(chief)
        db.personaBotDao().upsert(chief.copy(id = "member", name = "Member", sessionId = "member-dm", isMainBot = false))
        db.personaBotDao().upsert(chief.copy(id = "member2", name = "Member2", sessionId = "member2-dm", isMainBot = false))
        groups.upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        groups.addMember(GroupMemberEntity("group", "chief", 0, 1))
        groups.addMember(GroupMemberEntity("group", "member", 1, 1))
        groups.addMember(GroupMemberEntity("group", "member2", 2, 1))
    }

    private suspend fun create(
        id: String,
        botId: String = "member",
        batchId: String? = "work",
        sessionId: String = "session-$id",
        reportTo: String = "chief",
    ) {
        val batch = batchId?.let {
            BotWorkBatch(
                id = it,
                groupId = "group",
                requesterBotId = reportTo,
                requesterSessionId = "parent",
                originOperationId = it,
                revision = 1,
                status = BotWorkBatchStatus.WAITING_CHILDREN,
                createdAt = 1,
                updatedAt = 1,
            )
        }
        val task = BotAssignment(
            id = id,
            groupId = "group",
            botId = botId,
            botName = botId,
            title = "Task $id",
            task = "do $id",
            status = BotAssignmentStatus.QUEUED,
            createdAt = 1,
            startedAt = 1,
            sessionId = sessionId,
            reportToBotId = reportTo,
            independent = true,
            batchId = batchId,
        )
        tasks.createIndependent(
            task,
            HarnessSessionEntity(sessionId, "Task", 1, 1, null, workspace = "/workspace/project"),
            BotAssignmentRequest("initial:$id", id, "do $id", 1),
            batch,
        )
    }

    @Test fun `batch reports once after the last child settles`() = runBlocking {
        seed()
        create("a")
        create("b")

        assertTrue(tasks.claimRequest("initial:a", 2))
        tasks.finishRequest("initial:a", true, false, "result a", null)
        assertEquals(BotWorkBatchStatus.WAITING_CHILDREN, tasks.getBatch("work")!!.status)
        assertTrue(groups.observeMessages("group").first().isEmpty())
        assertTrue(groups.pendingGroupDeliveries().first().isEmpty())

        assertTrue(tasks.claimRequest("initial:b", 3))
        tasks.finishRequest("initial:b", true, false, "result b", null)
        val batch = tasks.getBatch("work")!!
        assertEquals(BotWorkBatchStatus.SETTLED, batch.status)
        assertEquals(2, batch.revision)
        assertEquals(2, batch.reportRevision)
        val message = groups.observeMessages("group").first().single()
        assertTrue(message.content.contains("result a"))
        assertTrue(message.content.contains("result b"))
        assertTrue(message.content.indexOf("result a") < message.content.indexOf("result b"))
        assertEquals(1, groups.pendingGroupDeliveries().first().size)
    }

    @Test fun `continue reopens a settled batch and reports a new revision`() = runBlocking {
        seed()
        create("a")
        assertTrue(tasks.claimRequest("initial:a", 2))
        tasks.finishRequest("initial:a", true, false, "first", null)
        assertEquals(BotWorkBatchStatus.SETTLED, tasks.getBatch("work")!!.status)

        tasks.enqueueRequest(BotAssignmentRequest("next:a", "a", "more", 5))
        val reopened = tasks.getBatch("work")!!
        assertEquals(BotWorkBatchStatus.WAITING_CHILDREN, reopened.status)
        assertEquals(2, reopened.revision)

        assertTrue(tasks.claimRequest("next:a", 6))
        tasks.finishRequest("next:a", true, false, "second", null)
        val settled = tasks.getBatch("work")!!
        assertEquals(BotWorkBatchStatus.SETTLED, settled.status)
        assertEquals(2, settled.reportRevision)
        assertEquals(2, groups.observeMessages("group").first().size)
    }

    @Test fun `batch waits while the requester turn is still running`() = runBlocking {
        seed()
        val now = System.currentTimeMillis()
        db.harnessRuntimeDao().upsertLane(
            HarnessLaneEntity("parent", "main", null, currentOperationId = "work", updatedAt = now),
        )
        create("a")
        assertTrue(tasks.claimRequest("initial:a", 2))
        tasks.finishRequest("initial:a", true, false, "done", null)
        assertEquals(BotWorkBatchStatus.WAITING_TURN, tasks.getBatch("work")!!.status)
        assertTrue(groups.observeMessages("group").first().isEmpty())

        db.harnessRuntimeDao().upsertLane(
            HarnessLaneEntity("parent", "main", null, currentOperationId = null, updatedAt = System.currentTimeMillis()),
        )
        tasks.reconcileWaitingBatches(System.currentTimeMillis())
        assertEquals(BotWorkBatchStatus.SETTLED, tasks.getBatch("work")!!.status)
        assertEquals(1, groups.observeMessages("group").first().size)
    }

    @Test fun `cancelling every child closes the batch as cancelled`() = runBlocking {
        seed()
        create("a")
        create("b")
        tasks.cancelAssignment("a")
        assertEquals(BotWorkBatchStatus.WAITING_CHILDREN, tasks.getBatch("work")!!.status)
        tasks.cancelAssignment("b")
        assertEquals(BotWorkBatchStatus.CANCELLED, tasks.getBatch("work")!!.status)
        val message = groups.observeMessages("group").first().single()
        assertTrue(message.content.contains("已取消"))
    }

    @Test fun `batch is cancelled without delivery when the requester left`() = runBlocking {
        seed()
        create("a")
        groups.deleteMember("group", "chief")
        assertFalse(tasks.claimRequest("initial:a", 2))
        assertEquals(BotWorkBatchStatus.CANCELLED, tasks.getBatch("work")!!.status)
        assertTrue(groups.observeMessages("group").first().isEmpty())
    }

    @Test fun `batch rejects more than the task cap`() = runBlocking {
        seed()
        repeat(BotWorkBatch.MAX_TASKS) { create("t$it", sessionId = "s$it") }
        val error = runCatching { create("overflow", sessionId = "s-overflow") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("上限"))
        assertEquals(BotWorkBatch.MAX_TASKS, tasks.getBatch("work")!!.revision)
        assertNull(tasks.get("overflow"))
    }
}

package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class BatchOwnershipPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "lead", "dev").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", "", null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        listOf("g", "other").forEach { group ->
            groups.saveGroup(GroupChat(group, group, "chief", createdAt = 1))
            listOf("chief", "lead", "dev").forEachIndexed { i, id -> groups.addMember(group, id, i) }
        }
    }

    private fun batch(id: String = "work", group: String = "g", owner: String = "chief", source: String = "group-chief") =
        BotWorkBatch(id, group, owner, source, id, 1, status = BotWorkBatchStatus.WAITING_CHILDREN, createdAt = 1, updatedAt = 1)

    private suspend fun create(id: String, batch: BotWorkBatch = batch(), worker: String = "dev",
        owner: String = batch.requesterBotId, group: String = batch.groupId, parent: String? = null) {
        tasks.createIndependent(BotAssignment(id, group, worker, worker, id, id, BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "session-$id", independent = true, reportToBotId = owner,
            batchId = batch.id, parentAssignmentId = parent), HarnessSessionEntity("session-$id", id, 1, 1, null),
            BotAssignmentRequest("initial:$id", id, id, 1), batch)
    }

    private suspend fun assertAbsent(id: String) {
        assertNull(tasks.get(id))
        assertNull(tasks.getRequest("initial:$id"))
        assertNull(db.harnessSessionDao().findById("session-$id"))
    }

    @Test fun `an existing batch cannot be reused across group owner session or operation`() = runBlocking {
        seed()
        create("first", worker = "lead")
        val original = tasks.getBatch("work")!!
        groups.saveTeam("g", mapOf("dev" to "lead"))
        val alternatives = listOf(batch(group = "other"), batch(owner = "lead", source = "group-lead"),
            batch(source = "another-chief-session"), batch().copy(originOperationId = "another-operation"))
        alternatives.forEachIndexed { index, candidate ->
            val id = "rejected-$index"
            assertTrue(runCatching { create(id, candidate, worker = if (candidate.requesterBotId == "chief") "lead" else "dev") }.isFailure)
            assertAbsent(id)
            assertEquals(original, tasks.getBatch("work"))
            assertEquals(1, tasks.batchTaskCount("work"))
        }
        assertTrue(db.botAssignmentDao().observeBudgets("other").first().isEmpty())
    }

    @Test fun `a new batch must match the assignment group and report owner`() = runBlocking {
        seed()
        listOf(batch("wrong-group", group = "other"), batch("wrong-owner", owner = "lead")).forEachIndexed { i, candidate ->
            val id = "bad-$i"
            assertTrue(runCatching { create(id, candidate, group = "g", owner = "chief") }.isFailure)
            assertAbsent(id)
            assertNull(tasks.getBatch(candidate.id))
        }
        assertTrue(db.botAssignmentDao().observeBudgets("g").first().isEmpty())
    }

    @Test fun `nested batch source must name the actual parent session`() = runBlocking {
        seed()
        create("parent", worker = "lead")
        tasks.claimRequest("initial:parent", 2)
        groups.saveTeam("g", mapOf("dev" to "lead"))
        val nested = batch("inner", owner = "lead", source = "session-parent")
        assertTrue(runCatching { create("missing-parent", nested) }.isFailure)
        assertAbsent("missing-parent")
        assertTrue(runCatching { create("wrong-source", nested.copy(requesterSessionId = "group-lead"), parent = "parent") }.isFailure)
        assertAbsent("wrong-source")
        assertNull(tasks.getBatch("inner"))
        create("valid-child", nested, parent = "parent")
        assertEquals("parent", tasks.get("valid-child")!!.parentAssignmentId)
        assertEquals(tasks.get("parent")!!.budgetId, tasks.get("valid-child")!!.budgetId)
    }

    @Test fun `concurrent conflicting batch ownership admits only the first owner atomically`() = runBlocking {
        seed()
        val outcomes = listOf("first-source", "second-source").mapIndexed { index, source -> async(Dispatchers.Default) {
            runCatching { create("race-$index", batch(source = source)) }
        } }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is IllegalArgumentException })
        val winner = tasks.batchAssignments("work").single()
        assertEquals(1, tasks.getBatch("work")!!.revision)
        assertAbsent(if (winner.id == "race-0") "race-1" else "race-0")
        assertEquals(1, db.botAssignmentDao().observeBudgets("g").first().size)
    }

    @Test fun `concurrent tasks with the same owner share one batch budget and final report`() = runBlocking {
        seed()
        repeat(8) { i ->
            db.personaBotDao().upsert(BotEntity("worker-$i", "Worker $i", "dev", "", "", null, null, "dm-worker-$i", false, "#123456", 1, 1))
            groups.addMember("g", "worker-$i", 3 + i)
        }
        (0 until 8).map { i -> async(Dispatchers.Default) { create("parallel-$i", worker = "worker-$i") } }.awaitAll()
        val children = tasks.batchAssignments("work")
        assertEquals(8, children.size)
        assertEquals(8, children.map { it.botId }.toSet().size)
        assertEquals(8, tasks.getBatch("work")!!.revision)
        assertEquals(1, children.map { it.budgetId }.toSet().size)
        assertNotNull(children.first().budgetId)
        children.forEachIndexed { index, child ->
            assertTrue(tasks.claimRequest("initial:${child.id}", 2L + index))
            tasks.finishRequest("initial:${child.id}", true, false, "done ${child.id}", null)
            assertEquals(if (index == children.lastIndex) 1 else 0, groups.pendingDeliveries().first().size)
        }
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }
}

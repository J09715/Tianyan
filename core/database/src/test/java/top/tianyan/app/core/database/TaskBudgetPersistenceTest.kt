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
import top.tianyan.app.core.database.repository.RoomTaskBudgetRepository
import top.tianyan.app.core.model.bot.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskBudgetPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val budgets = RoomTaskBudgetRepository(db.botAssignmentDao())
    @After fun close() = db.close()

    private suspend fun seed(limit: Long = 200) {
        listOf("chief", "lead", "dev", "peer").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1, taskBudgetTokens = limit))
        listOf("chief", "lead", "dev", "peer").forEachIndexed { i, id -> groups.addMember("g", id, i) }
        groups.saveTeam("g", mapOf("dev" to "lead"))
        create("parent", "lead", "chief", "work")
        tasks.claimRequest("initial:parent", 2)
        create("child", "dev", "lead", "inner", "parent")
        tasks.claimRequest("initial:child", 2)
        create("sibling", "peer", "chief", "work")
        tasks.claimRequest("initial:sibling", 2)
    }

    private suspend fun create(id: String, worker: String, requester: String, batch: String, parent: String? = null) {
        tasks.createIndependent(BotAssignment(id, "g", worker, worker, id, id, BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "$id-session", independent = true, reportToBotId = requester,
            batchId = batch, parentAssignmentId = parent), HarnessSessionEntity("$id-session", id, 1, 1, null),
            BotAssignmentRequest("initial:$id", id, id, 1),
            BotWorkBatch(batch, "g", requester, parent?.let { "$it-session" } ?: "group-chief", null,
                1, status = BotWorkBatchStatus.WAITING_CHILDREN, createdAt = 1, updatedAt = 1))
    }

    @Test fun `siblings and descendants share one quota and concurrent admission cannot spend the same balance`() = runBlocking {
        seed()
        assertEquals(tasks.get("parent")!!.budgetId, tasks.get("child")!!.budgetId)
        assertEquals(tasks.get("parent")!!.budgetId, tasks.get("sibling")!!.budgetId)
        val results = listOf("parent", "child").map { id -> async(Dispatchers.Default) {
            runCatching { budgets.reserve("$id-session", "request-$id", 120) }
        } }.awaitAll()
        assertEquals(1, results.count { it.getOrNull() == true })
        assertEquals(1, results.count { it.exceptionOrNull() is TaskBudgetExceededException })
        val state = budgets.forSession("parent-session")!!
        assertEquals(120L, state.heldTokens)
        assertEquals(80L, state.availableTokens)
    }

    @Test fun `known usage settles while unknown and interrupted reservations survive repository recreation`() = runBlocking {
        seed()
        budgets.reserve("parent-session", "known", 120)
        budgets.settle("known", 40, true)
        budgets.reserve("child-session", "unknown", 100)
        budgets.settle("unknown", 100, false)
        val recreated = RoomTaskBudgetRepository(db.botAssignmentDao())
        assertEquals(40L, recreated.forSession("child-session")!!.usedTokens)
        assertEquals(100L, recreated.forSession("child-session")!!.heldTokens)
        assertEquals(1, recreated.forSession("child-session")!!.unknownRequests)
        assertTrue(runCatching { recreated.reserve("child-session", "over", 61) }.exceptionOrNull() is TaskBudgetExceededException)
        recreated.reserve("child-session", "interrupted", 60)
        assertEquals(160L, RoomTaskBudgetRepository(db.botAssignmentDao()).forSession("parent-session")!!.heldTokens)
    }

    @Test fun `user changes preserve usage and only new budgets take the new default`() = runBlocking {
        seed()
        budgets.reserve("parent-session", "spent", 180)
        budgets.settle("spent", 180, true)
        budgets.setDefault("g", 999)
        assertEquals(200L, budgets.forSession("parent-session")!!.limitTokens)
        val id = tasks.get("parent")!!.budgetId!!
        budgets.setLimit("g", id, 300)
        budgets.reserve("child-session", "continued", 120)
        assertEquals(180L, budgets.forSession("child-session")!!.usedTokens)
        assertEquals(120L, budgets.forSession("child-session")!!.heldTokens)
        assertTrue(runCatching { budgets.setLimit("other", id, 0) }.isFailure)
        create("new", "peer", "chief", "new-work")
        assertEquals(999L, budgets.forSession("new-session")!!.limitTokens)
    }

    @Test fun `budget denial persists a blocked task and cannot be bypassed by spawning another descendant`() = runBlocking {
        seed()
        budgets.reserve("parent-session", "all", 200)
        budgets.block("parent-session", "额度不足")
        tasks.finishRequest("initial:parent", false, false, "request denied", "failed")
        assertEquals(BotAssignmentStatus.BLOCKED, tasks.get("parent")!!.status)
        assertTrue(tasks.get("parent")!!.error!!.contains("任务预算"))
        assertTrue(runCatching { create("another", "dev", "lead", "another-batch", "parent") }.isFailure)
        tasks.enqueueRequest(BotAssignmentRequest("resume", "parent", "verify and continue", 3))
        tasks.claimRequest("resume", 4)
        assertTrue(runCatching { create("another", "dev", "lead", "another-batch", "parent") }.exceptionOrNull() is TaskBudgetExceededException)
    }

    @Test fun `older actionable budgets stay visible beyond the recent history window`() = runBlocking {
        seed()
        budgets.reserve("parent-session", "spent", 100)
        budgets.settle("spent", 40, true)
        budgets.reserve("child-session", "unknown", 30)
        budgets.settle("unknown", 30, false)
        val original = tasks.get("parent")!!
        listOf("child", "sibling").forEach { tasks.save(tasks.get(it)!!.copy(status = BotAssignmentStatus.COMPLETED)) }
        repeat(35) { i -> db.botAssignmentDao().insertBudget(TaskBudgetEntity("history-$i", "g", "history", 100,
            System.currentTimeMillis() + 10_000 + i)) }
        groups.saveGroup(GroupChat("other", "Other", "chief", createdAt = 1))
        db.botAssignmentDao().insertBudget(TaskBudgetEntity("foreign", "other", "Foreign", 100, Long.MAX_VALUE))
        listOf(BotAssignmentStatus.BLOCKED, BotAssignmentStatus.RUNNING, BotAssignmentStatus.WAITING_CHILDREN,
            BotAssignmentStatus.QUEUED, BotAssignmentStatus.FAILED, BotAssignmentStatus.INTERRUPTED).forEach { status ->
            tasks.save(original.copy(status = status))
            val rows = budgets.observe("g").first()
            assertEquals(31, rows.size)
            assertFalse(rows.any { it.id == "foreign" })
            val old = rows.single { it.id == original.budgetId }
            assertEquals(40L, old.usedTokens)
            assertEquals(30L, old.heldTokens)
            assertEquals(1, old.unknownRequests)
        }
        budgets.setLimit("g", original.budgetId!!, 300)
        assertEquals(300L, budgets.observe("g").first().single { it.id == original.budgetId }.limitTokens)
        listOf(BotAssignmentStatus.COMPLETED, BotAssignmentStatus.CANCELLED).forEach { status ->
            tasks.save(original.copy(status = status))
            val rows = budgets.observe("g").first()
            assertEquals(30, rows.size)
            assertFalse(rows.any { it.id == original.budgetId })
        }
    }

    @Test fun `legacy tasks bind lazily and include prior ledger usage without double counting`() = runBlocking {
        seed()
        val old = tasks.get("sibling")!!.copy(id = "legacy", budgetId = null, batchId = null, sessionId = "legacy-session")
        tasks.save(old)
        db.harnessRuntimeDao().insertUsage(HarnessUsageEntity(id = "old-usage", sessionId = "legacy-session",
            operationId = null, entryId = null, provider = "mock", modelId = "fake", inputTokens = 30, outputTokens = 10, createdAt = 1))
        assertEquals(40L, budgets.forSession("legacy-session")!!.usedTokens)
        assertEquals(40L, budgets.forSession("legacy-session")!!.usedTokens)
    }
}

package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.core.database.repository.RoomGroupChatRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupWorkAdmissionTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "author", "reviewer", "executor", "worker").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
        listOf("chief", "author", "reviewer", "executor", "worker").forEachIndexed { index, id -> groups.addMember("g", id, index) }
        groups.saveTeam("g", mapOf("worker" to "executor"))
        groups.saveGovernance("g", GroupGovernance(listOf(GroupReviewRule("review", "Review", "author", "reviewer", "executor"))))
        groups.submitProposal("g", "author", "review", "p", 0, "approved modules", rootWorkId = "work")
        groups.decideProposal("g", "reviewer", "p", 1, ProposalDecision.APPROVED, "checked")
    }

    private fun task(id: String, owner: String = "author", item: String = "executor", work: String = "work") =
        BotAssignment(id, "g", "executor", "executor", id, "deliver", BotAssignmentStatus.QUEUED,
            writePaths = listOf("/workspace/$item"), createdAt = 1, startedAt = 1,
            sessionId = "s-$id", reportToBotId = owner, independent = true, proposalId = "p", proposalVersion = 1,
            rootWorkId = work, stageId = "execute:p:1", workItemId = item)

    private suspend fun create(task: BotAssignment, operation: String = task.id): String = tasks.createIndependentOnce(task,
        HarnessSessionEntity(requireNotNull(task.sessionId), task.id, 1, 1, null),
        BotAssignmentRequest("initial:${task.id}", task.id, task.task, 1), operationId = operation)

    @Test fun `chief and author racing one stage create exactly one session and preserve original owner`() = runBlocking {
        seed()
        val result = coroutineScope {
            listOf(task("a"), task("b", "chief")).map { task -> async(Dispatchers.Default) { create(task) } }.awaitAll()
        }
        assertEquals(1, result.distinct().size)
        val rows = tasks.observeForGroup("g").first()
        assertEquals(1, rows.size)
        val original = rows.single()
        assertEquals(result.first(), original.id)
        val other = if (original.id == "a") "b" else "a"
        assertNull(db.harnessSessionDao().findById("s-$other"))
        assertNull(tasks.getRequest("initial:$other"))
        assertEquals(setOf("executor"), tasks.dispatchedBots("g", "a"))
        assertEquals(setOf("executor"), tasks.dispatchedBots("g", "b"))
    }

    @Test fun `distinct work items under one proposal remain distinct and share business budget`() = runBlocking {
        seed()
        val a = create(task("a", item = "engine"))
        val b = create(task("b", item = "tests"))
        assertNotEquals(a, b)
        assertEquals(2, tasks.observeForGroup("g").first().size)
        assertEquals(tasks.get(a)!!.budgetId, tasks.get(b)!!.budgetId)
        assertEquals("g:work", tasks.get(a)!!.budgetId)
        assertEquals("work", groups.getProposal("p")!!.rootWorkId)
    }

    @Test fun `stage lease rejects a second dispatcher opening another work item`() = runBlocking {
        seed()
        create(task("a"))
        assertTrue(runCatching { create(task("b", "chief", "other")) }.isFailure)
        assertNull(tasks.get("b"))
        assertNull(db.harnessSessionDao().findById("s-b"))
        assertTrue(tasks.dispatchedBots("g", "b").isEmpty())
    }

    @Test fun `new business work is not mistaken for previous work and a correction stays on original session`() = runBlocking {
        seed()
        create(task("a"))
        groups.submitProposal("g", "author", "review", "p2", 0, "new work", rootWorkId = "new-work")
        groups.decideProposal("g", "reviewer", "p2", 1, ProposalDecision.APPROVED, "checked")
        create(task("b", work = "new-work").copy(proposalId = "p2", stageId = "execute:p2:1"))
        assertEquals(2, tasks.observeForGroup("g").first().size)
        tasks.enqueueRequest(BotAssignmentRequest("correction", "a", "change the controls", 2))
        assertEquals("s-a", tasks.get("a")!!.sessionId)
        assertEquals("work", tasks.get("a")!!.rootWorkId)
        assertEquals(2, tasks.requestsFor("a").size)
        assertEquals("a", create(task("duplicate")))
        assertEquals(2, tasks.requestsFor("a").size)
    }

    @Test fun `group execution progress limits survive storage and invalid values are rejected`() = runBlocking {
        seed()
        val group = groups.getGroup("g")!!
        groups.saveGroup(group.copy(executionLimits = GroupExecutionLimits(60_000, 600_000)))
        assertEquals(GroupExecutionLimits(60_000, 600_000), groups.getGroup("g")!!.executionLimits)
        assertTrue(runCatching { GroupExecutionLimits(300_000, 120_000) }.isFailure)
    }
}

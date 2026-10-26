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
class GroupGovernancePersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = db.botAssignmentDao()
    private val rule = GroupReviewRule("review", "审议", "author", "reviewer", "executor", 2)
    private val ids = listOf("chief", "author", "reviewer", "executor", "worker")
    @After fun close() = db.close()

    private suspend fun seed(policy: Boolean = true) {
        ids.forEach { id -> db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null,
            "dm-$id", id == "chief", "#123456", 1, 1)) }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
        ids.forEachIndexed { i, id -> groups.addMember("g", id, i) }
        groups.saveTeam("g", mapOf("worker" to "executor"))
        if (policy) groups.saveGovernance("g", GroupGovernance(listOf(rule)))
    }
    private suspend fun propose(version: Int = 0, content: String = "plan") =
        groups.submitProposal("g", "author", "review", "proposal", version, content)
    private suspend fun decide(version: Int = 1, decision: ProposalDecision = ProposalDecision.APPROVED, caller: String = "reviewer") =
        groups.decideProposal("g", caller, "proposal", version, decision, "evidence")
    private suspend fun create(id: String = "task", bot: String = "executor", caller: String = "author",
        proposalId: String? = "proposal", version: Int? = 1) {
        val task = BotAssignment(id, "g", bot, bot, id, "work", BotAssignmentStatus.QUEUED, createdAt = 1,
            startedAt = 1, reportToBotId = caller, independent = true, sessionId = "session-$id",
            proposalId = proposalId, proposalVersion = version)
        tasks.createIndependent(task.toEntity(Json), HarnessSessionEntity("session-$id", id, 1, 1, null),
            BotAssignmentRequestEntity("initial:$id", id, "work", 1))
    }

    @Test fun `peer review does not grant management authority and rejects execution before approval`() = runBlocking {
        seed(); propose()
        assertFalse(groups.getTeam("g").canAssign("author", "reviewer"))
        create("review-task", "reviewer")
        assertEquals("author", tasks.findById("review-task")!!.reportToBotId)
        assertTrue(runCatching { create() }.isFailure)
        assertNull(tasks.findById("task"))
        assertNull(db.harnessSessionDao().findById("session-task"))
        decide(); create()
        assertTrue(tasks.claimRequest("initial:task", 2))
    }

    @Test fun `completed review with rejection never unlocks execution`() = runBlocking {
        seed(); propose(); create("review-task", "reviewer")
        assertTrue(tasks.claimRequest("initial:review-task", 2))
        decide(decision = ProposalDecision.REJECTED)
        tasks.finishRequest("initial:review-task", true, false, "rejected", null, 3)
        assertEquals("COMPLETED", tasks.findById("review-task")!!.status)
        assertTrue(runCatching { create() }.isFailure)
    }

    @Test fun `new proposal version invalidates queued work and retains old audit record`() = runBlocking {
        seed(); propose(); decide(); create()
        propose(1, "changed plan")
        assertFalse(tasks.claimRequest("initial:task", 3))
        assertEquals("BLOCKED", tasks.findById("task")!!.status)
        assertEquals(listOf(2, 1), groups.observeProposals("g").first().map { it.version })
        assertEquals(ProposalDecision.APPROVED, groups.observeProposals("g").first().last().decision)
        assertTrue(runCatching { decide(1) }.isFailure)
    }

    @Test fun `author cannot self approve and rule or member changes block queued work`() = runBlocking {
        seed(); propose()
        assertTrue(runCatching { decide(caller = "author") }.isFailure)
        assertTrue(runCatching { decide(caller = "chief") }.isFailure)
        decide(); create()
        groups.removeMember("g", "reviewer")
        assertFalse(tasks.claimRequest("initial:task", 3))
        assertEquals("BLOCKED", tasks.findById("task")!!.status)
    }

    @Test fun `revisions are bounded and only root arbitrates escalation`() = runBlocking {
        seed(); propose(); decide(decision = ProposalDecision.REJECTED)
        propose(1); decide(2, ProposalDecision.REJECTED)
        assertEquals(ProposalDecision.ESCALATED, propose(2).decision)
        assertTrue(runCatching { decide(3) }.isFailure)
        decide(3, caller = "chief")
        create(version = 3)
        assertTrue(tasks.claimRequest("initial:task", 4))
    }

    @Test fun `rejection cannot be overwritten and cross group proposal cannot be submitted`() = runBlocking {
        seed(); propose(); decide(decision = ProposalDecision.REJECTED)
        assertTrue(runCatching { decide() }.isFailure)
        groups.saveGroup(GroupChat("other", "Other", "chief", createdAt = 1))
        ids.forEachIndexed { i, id -> groups.addMember("other", id, i) }
        groups.saveGovernance("other", GroupGovernance(listOf(rule)))
        assertTrue(runCatching { groups.submitProposal("other", "author", "review", "proposal", 1, "other") }.isFailure)
        assertEquals("g", groups.getProposal("proposal")!!.groupId)
    }

    @Test fun `executor descendants require approved proposal and stale rule snapshots fail`() = runBlocking {
        seed(); propose(); decide()
        assertTrue(runCatching { create(bot = "worker", caller = "executor", proposalId = null, version = null) }.isFailure)
        create(bot = "worker", caller = "executor")
        groups.saveGovernance("g", GroupGovernance(listOf(rule.copy(name = "changed"))))
        assertFalse(tasks.claimRequest("initial:task", 3))
    }

    @Test fun `team template publication rolls back all group records when rule is invalid`() = runBlocking {
        seed(false)
        val group = GroupChat("new", "Template", "chief", createdAt = 1)
        assertTrue(runCatching { groups.installTeamTemplate(group, ids, emptyMap(),
            GroupGovernance(listOf(rule.copy(reviewerId = "missing"))), true) }.isFailure)
        assertNull(groups.getGroup("new"))
        assertTrue(groups.getMembers("new").isEmpty())
        assertTrue(groups.getGovernance("new").rules.isEmpty())
    }

    @Test fun `import into existing group preserves members and repeated publication is rejected atomically`() = runBlocking {
        seed(false)
        val group = requireNotNull(groups.getGroup("g"))
        groups.installTeamTemplate(group, emptyList(), mapOf("worker" to "executor"), GroupGovernance(listOf(rule)), false)
        assertEquals(ids.toSet(), groups.getTeam("g").memberIds)
        assertEquals("chief", groups.getTeam("g").adminBotId)
        assertTrue(runCatching { groups.installTeamTemplate(group, emptyList(), emptyMap(), GroupGovernance(listOf(rule)), false) }.isFailure)
        assertEquals(1, groups.getGovernance("g").rules.size)
    }

    @Test fun `group deletion cascades all review versions and policy`() = runBlocking {
        seed(); propose(); decide(decision = ProposalDecision.REJECTED); propose(1)
        groups.deleteGroup("g")
        assertNull(groups.getProposal("proposal"))
        assertTrue(groups.observeProposals("g").first().isEmpty())
        assertTrue(groups.getGovernance("g").rules.isEmpty())
    }
}

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
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.bot.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamDefinitionPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val chats = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val teams = RoomBotMemberGroupRepository(db.botMemberGroupDao())
    private val members = listOf("chief", "author", "reviewer", "executor", "worker", "extra")
    private val rule = GroupReviewRule("review", "方案审议", "author", "reviewer", "executor")
    private val definition = BotTeamDefinition("chief", mapOf("worker" to "executor"), GroupGovernance(listOf(rule)))
    private val team = BotMemberGroup("team", "创作团队", 1, members.dropLast(1), definition)
    @After fun close() = db.close()
    private suspend fun seed() {
        members.forEach { id -> db.personaBotDao().upsert(BotEntity(id,id,"role","",id,null,null,"dm-$id",id=="chief","#123456",1,1)) }
        chats.saveIndependentTeam(team)
    }
    private suspend fun room(id: String = "g") = chats.createReferencedGroup(GroupChat(id, id, "chief", createdAt = 1), "team")

    @Test fun `team exists without a chat and two chats share one authored definition`() = runBlocking {
        seed()
        assertTrue(chats.observeGroups().first().isEmpty())
        assertEquals(team.definition, teams.observeGroups().first().single().publishedSnapshot!!.definition)
        room(); room("other")
        assertEquals("team", chats.getGroup("g")!!.teamGroupId)
        assertEquals(definition.team(team.memberIds), chats.getTeam("g"))
        assertEquals(chats.getTeam("g"), chats.getTeam("other"))
        assertEquals(definition.governance, chats.getGovernance("g"))
        assertTrue(db.groupChatDao().teamMembers("g").all { it.supervisorBotId == null })
        assertNull(db.groupChatDao().governanceRow("g"))
    }

    @Test fun `draft changes leave rooms pinned until the selected room upgrades`() = runBlocking {
        seed(); room(); room("other")
        val next = definition.copy(supervisors = mapOf("worker" to "chief"), governance = GroupGovernance(listOf(rule.copy(maxRevisions = 5))))
        teams.saveGroup(teams.observeGroups().first().single().copy(memberIds = members, definition = next))
        assertEquals(definition.governance, chats.getGovernance("g"))
        val draft = teams.observeGroups().first().single()
        teams.publish(draft.id, draft.draftRevision, setOf("g"))
        assertEquals(1, chats.getGroup("other")!!.teamVersion)
        assertEquals(definition.governance, chats.getGovernance("other"))
        listOf("g").forEach { id ->
            assertEquals(members.toSet(), chats.getTeam(id).memberIds)
            assertEquals("chief", chats.getTeam(id).supervisorOf("worker"))
            assertEquals(5, chats.getGovernance(id).rules.single().maxRevisions)
            assertEquals(next.governance, chats.observeGovernance(id).first())
            assertEquals(next.team(members), chats.observeTeam(id).first())
        }
    }

    @Test fun `chat cannot mutate an authored teams relationships review policy or membership`() = runBlocking {
        seed(); room()
        assertTrue(runCatching { chats.saveTeam("g", emptyMap()) }.isFailure)
        assertTrue(runCatching { chats.saveGovernance("g", GroupGovernance()) }.isFailure)
        assertTrue(runCatching { chats.addMember("g", "extra", 9) }.isFailure)
        assertTrue(runCatching { chats.removeMember("g", "worker") }.isFailure)
        assertEquals(definition.team(team.memberIds), chats.getTeam("g"))
        val current = chats.getGroup("g")!!
        chats.saveGroup(current.copy(name = "renamed", teamGroupId = null, adminBotId = "author"))
        assertEquals("team", chats.getGroup("g")!!.teamGroupId)
        assertEquals("chief", chats.getGroup("g")!!.adminBotId)
    }

    @Test fun `invalid team edit rolls back definition and all linked room projections`() = runBlocking {
        seed(); room(); room("other")
        assertTrue(runCatching { teams.saveGroup(team.copy(memberIds = team.memberIds - "reviewer")) }.isFailure)
        assertEquals(team.definition, teams.observeGroups().first().single().publishedSnapshot!!.definition)
        assertEquals(team.memberIds.toSet(), chats.getTeam("g").memberIds)
        assertEquals(team.memberIds.toSet(), chats.getTeam("other").memberIds)
    }

    @Test fun `deleting a chat preserves its reusable team while referenced team deletion is rejected`() = runBlocking {
        seed(); room(); room("other")
        assertTrue(runCatching { teams.deleteGroup("team") }.isFailure)
        chats.deleteGroup("g")
        assertEquals(team.definition, teams.observeGroups().first().single().publishedSnapshot!!.definition)
        chats.deleteGroup("other")
        teams.deleteGroup("team")
        assertTrue(teams.observeGroups().first().isEmpty())
        assertNotNull(db.personaBotDao().findById("author"))
    }

    @Test fun `shared review rules do not share proposal approval between chats or between team identities`() = runBlocking {
        seed(); room(); room("other")
        chats.submitProposal("g", "author", rule.id, "p", 0, "plan")
        chats.decideProposal("g", "reviewer", "p", 1, ProposalDecision.APPROVED, "verified")
        assertEquals("team", chats.getProposal("p")!!.teamGroupId)
        assertTrue(chats.observeProposals("other").first().isEmpty())
        assertTrue(runCatching { chats.decideProposal("other", "reviewer", "p", 1, ProposalDecision.APPROVED, "wrong room") }.isFailure)
        teams.saveGroup(team.copy(id = "copy", name = "copied team"))
        teams.publish("copy", 1, emptySet())
        chats.attachTeam("g", "copy")
        val task = BotAssignment("task", "g", "executor", "executor", "work", "work", BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, reportToBotId = "author", independent = true, sessionId = "s", proposalId = "p", proposalVersion = 1)
        assertFalse(db.botAssignmentDao().governanceAllows(task.toEntity(Json)))
    }

    @Test fun `failed team reference creates no partial chat`() = runBlocking {
        seed()
        assertTrue(runCatching { chats.createReferencedGroup(GroupChat("bad", "bad", "chief", createdAt = 1), "missing") }.isFailure)
        assertNull(chats.getGroup("bad"))
        assertTrue(chats.getMembers("bad").isEmpty())
    }
}

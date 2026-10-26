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
class TeamPublicationPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val teams = RoomBotMemberGroupRepository(db.botMemberGroupDao())
    private val chats = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val rule = GroupReviewRule("review", "review", "author", "reviewer", "executor")
    private val definition = BotTeamDefinition("chief", emptyMap(), GroupGovernance(listOf(rule)))
    @After fun close() = db.close()
    private suspend fun seed() {
        val ids = listOf("chief", "author", "reviewer", "executor")
        ids.forEach { db.personaBotDao().upsert(BotEntity(it,it,"role","",it,null,null,"dm-$it",false,"#123456",1,1)) }
        chats.saveIndependentTeam(BotMemberGroup("team", "team", 1, ids, definition))
        listOf("g", "other").forEach { chats.createReferencedGroup(GroupChat(it,it,"chief",createdAt=1),"team") }
    }
    private suspend fun draft() = teams.observeGroups().first().single()
    private suspend fun change() {
        teams.saveGroup(draft().copy(name = "changed", definition = definition.copy(governance = GroupGovernance(listOf(rule.copy(maxRevisions=5))))))
    }
    private suspend fun task(status: BotAssignmentStatus) = tasks.save(BotAssignment("task", "g", "author", "author", "task", "work", status,
        createdAt=1, startedAt=1, independent=true, sessionId="task-session", reportToBotId="chief", teamBinding="team@1"))

    @Test fun immutableHistoryAndExplicitUpgrades() = runBlocking {
        seed(); change()
        assertEquals(3,chats.getGovernance("g").rules.single().maxRevisions)
        assertEquals(2,teams.publish("team",draft().draftRevision,emptySet()))
        assertEquals(1,chats.getGroup("g")!!.teamVersion)
        teams.upgrade("team",2,setOf("g"))
        assertEquals(5,chats.getGovernance("g").rules.single().maxRevisions)
        assertEquals(3,chats.getGovernance("other").rules.single().maxRevisions)
        val history=teams.observeVersions("team").first()
        assertEquals(listOf(2,1),history.map { it.version })
        assertEquals(definition,history.last().definition)
        assertEquals(1,chats.getGroup("other")!!.teamVersion)
    }

    @Test fun busyRoomRejectsWholePublicationAndPreservesOldRules() = runBlocking {
        seed(); change()
        listOf(BotAssignmentStatus.QUEUED,BotAssignmentStatus.RUNNING,BotAssignmentStatus.WAITING_CHILDREN,BotAssignmentStatus.BLOCKED,BotAssignmentStatus.INTERRUPTED).forEach { status ->
            task(status)
            assertFalse(teams.publicationImpact("team").first { it.groupId=="g" }.canUpgrade)
            assertTrue(runCatching { teams.publish("team",draft().draftRevision,setOf("other","g")) }.isFailure)
            assertEquals(1,teams.observeVersions("team").first().size)
            assertEquals(1,chats.getGroup("other")!!.teamVersion)
            assertEquals("team@1",tasks.get("task")!!.teamBinding)
        }
        // Publishing without upgrading a busy room is safe.
        teams.publish("team",draft().draftRevision,emptySet())
        assertEquals(1,chats.getGroup("g")!!.teamVersion)
    }

    @Test fun groupRoundAndHarnessApprovalBlockUpgrade() = runBlocking {
        seed(); change(); teams.publish("team",draft().draftRevision,emptySet())
        db.openHelper.writableDatabase.execSQL("INSERT INTO group_rounds(id,groupId,trigger,status,createdAt,updatedAt) VALUES ('r','g','test','RUNNING',1,1)")
        assertTrue(runCatching { teams.upgrade("team",2,setOf("g")) }.isFailure)
        db.openHelper.writableDatabase.execSQL("UPDATE group_rounds SET status='DONE'")
        db.harnessSessionDao().insert(HarnessSessionEntity("group-session","session",1,1,null))
        db.botGroupSessionDao().upsert(BotGroupSessionEntity("chief","g","group-session",1))
        db.openHelper.writableDatabase.execSQL("INSERT INTO harness_operations(id,sessionId,laneName,kind,status,phase,startedAt,updatedAt,stateJson,attempt) VALUES ('op','group-session','main','run','waiting_approval','test',1,1,'{}',0)")
        assertFalse(teams.publicationImpact("team").first { it.groupId=="g" }.canUpgrade)
        assertTrue(runCatching { chats.attachTeam("g","team") }.isFailure)
    }

    @Test fun staleDraftAndPublishPreviewCannotOverwriteNewerWork() = runBlocking {
        seed()
        val stale=draft(); change()
        assertTrue(runCatching { teams.saveGroup(stale.copy(name="stale")) }.isFailure)
        assertTrue(runCatching { teams.publish("team",stale.draftRevision,emptySet()) }.isFailure)
        assertEquals("changed",draft().name)
        assertEquals(1,teams.observeVersions("team").first().size)
    }

    @Test fun approvalCannotAuthorizeAnotherPublishedVersionEvenWithIdenticalRule() = runBlocking {
        seed()
        chats.submitProposal("g","author","review","p",0,"plan")
        chats.decideProposal("g","reviewer","p",1,ProposalDecision.APPROVED,"verified")
        assertEquals(1,chats.getProposal("p")!!.teamVersion)
        teams.saveGroup(draft().copy(name="renamed"))
        teams.publish("team",draft().draftRevision,setOf("g"))
        val execution=BotAssignment("exec","g","executor","executor","exec","work",BotAssignmentStatus.QUEUED,
            createdAt=1,startedAt=1,proposalId="p",proposalVersion=1,teamBinding="team@2")
        assertFalse(db.botAssignmentDao().governanceAllows(execution.toEntity(Json)))
        assertTrue(runCatching { chats.submitProposal("g","author","review","p",1,"changed") }.isFailure)
        assertEquals(ProposalDecision.APPROVED,chats.getProposal("p")!!.decision)
    }

    @Test fun completedOldTaskCannotResumeUnderNewVersion() = runBlocking {
        seed()
        val assignment=BotAssignment("task","g","author","author","task","work",BotAssignmentStatus.QUEUED,
            createdAt=1,startedAt=1,independent=true,sessionId="task-session",reportToBotId="chief")
        tasks.createIndependent(assignment,HarnessSessionEntity("task-session","task",1,1,null),BotAssignmentRequest("initial","task","work",1))
        assertEquals("team@1",tasks.get("task")!!.teamBinding)
        assertTrue(tasks.claimRequest("initial",2))
        tasks.finishRequest("initial",true,false,"done",null)
        // Clear the ordinary delivery report before switching the whole room's version.
        db.openHelper.writableDatabase.execSQL("UPDATE group_deliveries SET status='completed'")
        change(); teams.publish("team",draft().draftRevision,setOf("g"))
        assertTrue(runCatching { tasks.enqueueRequest(BotAssignmentRequest("resume","task","continue",3)) }.isFailure)
        assertEquals(BotAssignmentStatus.COMPLETED,tasks.get("task")!!.status)
    }
}

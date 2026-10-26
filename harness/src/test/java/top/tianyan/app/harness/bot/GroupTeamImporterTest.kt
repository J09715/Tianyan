package top.tianyan.app.harness.bot

import java.util.UUID
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
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.bot.market.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupTeamImporterTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val removed = mutableListOf<String>()
    private var prepared = 0
    private var failAt = Int.MAX_VALUE
    private var pauseAt = Int.MAX_VALUE
    private val entered = CompletableDeferred<Unit>()
    @After fun close() = db.close()

    private fun importer(repository: GroupChatRepository = groups) = GroupTeamImporter(
        catalog = { GroupTeamTemplates.all.flatMap { it.members }.map { seat ->
            MarketBotItem(seat.marketId, seat.key, "role", "description", "persona", "test")
        }.distinctBy { it.id } },
        createMember = { _, name, role, persona ->
            prepared++
            if (prepared == pauseAt) { entered.complete(Unit); awaitCancellation() }
            if (prepared == failAt) error("fake member creation failure")
            val id = UUID.randomUUID().toString()
            val bot = Bot(id, name, role, persona, name, sessionId = "dm-$id", createdAt = 1, updatedAt = 1)
            bots.saveBot(bot)
            bot
        },
        removeMember = { removed += it; bots.deleteBot(it) },
        groups = repository,
    )

    @Test fun `template import creates an independent authored team without creating a chat`() = runBlocking {
        val team = importer().importDefinition("sansheng", "三省六部团队")
        assertEquals(10, team.memberIds.size)
        assertNotNull(team.definition)
        assertEquals(6, requireNotNull(team.definition).supervisors.size)
        assertTrue(groups.observeGroups().first().isEmpty())
        val stored = RoomBotMemberGroupRepository(db.botMemberGroupDao()).observeGroups().first().single()
        assertEquals(team.definition, stored.publishedSnapshot!!.definition)
        assertEquals(1, stored.publishedVersion)
        groups.createReferencedGroup(GroupChat("chat", "引用团队", team.definition!!.adminBotId, createdAt = 1), team.id)
        assertEquals(team.id, groups.getGroup("chat")!!.teamGroupId)
    }

    @Test fun `one import publishes all members reporting lines and remapped rule`() = runBlocking {
        val group = importer().import("sansheng", "朝廷")
        val members = groups.getMembers(group.id)
        assertEquals(10, members.size)
        assertEquals("朝廷", group.name)
        val team = groups.getTeam(group.id)
        val rule = groups.getGovernance(group.id).rules.single()
        assertEquals(6, team.directReports(rule.executorId).size)
        assertNotEquals(rule.authorId, rule.reviewerId)
        assertTrue(listOf(rule.authorId, rule.reviewerId, rule.executorId).all { id -> members.any { it.id == id } })
        assertEquals(0, removed.size)
        assertTrue(groups.observeProposals(group.id).first().isEmpty())
    }

    @Test fun `imports are independent and organization tweaks do not change template or other group`() = runBlocking {
        val first = importer().import("sansheng", "First")
        val second = importer().import("sansheng", "Second")
        val firstTeam = groups.getTeam(first.id)
        val secondTeam = groups.getTeam(second.id)
        assertTrue(firstTeam.memberIds.intersect(secondTeam.memberIds).isEmpty())
        val teamRepo = RoomBotMemberGroupRepository(db.botMemberGroupDao())
        val authored = teamRepo.observeGroups().first().single { it.id == first.teamGroupId }
        teamRepo.saveGroup(authored.copy(definition = requireNotNull(authored.definition).copy(supervisors = emptyMap())))
        assertEquals(secondTeam, groups.getTeam(second.id))
        assertEquals(6, GroupTeamTemplates.all.single { it.id == "sansheng" }.members.count { it.supervisorKey == "shangshu" })
        val rule = groups.getGovernance(first.id).rules.single()
        val current = teamRepo.observeGroups().first().single { it.id == first.teamGroupId }
        teamRepo.saveGroup(current.copy(definition = requireNotNull(current.definition).copy(governance = GroupGovernance(listOf(rule.copy(maxRevisions = 5))))))
        assertEquals(3, groups.getGovernance(second.id).rules.single().maxRevisions)
    }

    @Test fun `existing group keeps its root and imports fresh members with unique names`() = runBlocking {
        val original = importer().import("sansheng", "Existing")
        val originalIds = groups.getTeam(original.id).memberIds
        importer().import("sansheng", "Ignored name", original.id)
        val updated = groups.getGroup(original.id)!!
        val members = groups.getMembers(original.id)
        assertEquals(original.adminBotId, updated.adminBotId)
        assertEquals(original.name, updated.name)
        assertEquals(19, members.size)
        assertTrue(members.map { it.id }.containsAll(originalIds))
        assertEquals(19, members.map { it.name }.distinct().size)
        assertEquals(2, groups.getGovernance(original.id).rules.size)
    }

    @Test fun `member creation failure cleans prepared members and publishes no group`() = runBlocking {
        failAt = 4
        assertTrue(runCatching { importer().import("sansheng", "Failed") }.isFailure)
        assertEquals(3, removed.size)
        assertTrue(bots.listBots().isEmpty())
        assertTrue(groups.observeGroups().first().isEmpty())
    }

    @Test fun `publication failure cleans every member while preserving existing group`() = runBlocking {
        val original = importer().import("software", "Original")
        val originalIds = bots.listBots().map { it.id }.toSet()
        val rejecting = object : GroupChatRepository by groups {
            override suspend fun installTeamTemplate(group: GroupChat, members: List<String>, supervisors: Map<String, String>,
                policy: GroupGovernance, createNew: Boolean) { error("fake publication failure") }
        }
        assertTrue(runCatching { importer(rejecting).import("sansheng", "Failure", original.id) }.isFailure)
        assertEquals(9, removed.size)
        assertEquals(originalIds, bots.listBots().map { it.id }.toSet())
        assertEquals(6, groups.getMembers(original.id).size)
        assertEquals(1, groups.getGovernance(original.id).rules.size)
    }

    @Test fun `cancellation during preparation cleans only this import`() = runBlocking {
        val original = importer().import("software", "Original")
        val originalIds = bots.listBots().map { it.id }.toSet()
        pauseAt = prepared + 4
        val job = launch { importer().import("sansheng", "Cancelled") }
        entered.await()
        job.cancelAndJoin()
        assertEquals(3, removed.size)
        assertEquals(originalIds, bots.listBots().map { it.id }.toSet())
        assertEquals(original, groups.getGroup(original.id))
    }
}

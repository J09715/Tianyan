package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val bots = db.personaBotDao()
    private val groups = db.groupChatDao()
    private val deliveries = db.botScheduleDeliveryDao()
    private val bot = BotEntity("bot", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1)
    private val group = GroupChatEntity("group", "Team", "bot", "", 1)
    private val schedule = BotScheduleEntity("schedule", "bot", "group", "* * * * *", "work", true, 100, null, 1)

    @After fun close() = db.close()

    private fun assignment(id: String, session: String) = BotAssignmentEntity(
        id, "group", "bot", "Chief", "task", "work", "COMPLETED", "[]", 1, 1,
        2, "old result", null, session, "subagent:$id",
    )

    @Test fun `deleting session removes its bot mapping and assignments without touching other sessions`() = runBlocking {
        seed()
        db.harnessSessionDao().insert(HarnessSessionEntity("group-session", "old", 1, 1, null))
        db.botAssignmentDao().upsert(assignment("old-task", "group-session"))
        db.botAssignmentDao().upsert(assignment("other-task", "other-session"))
        db.harnessSessionDao().deleteSession("group-session")
        assertNull(db.harnessSessionDao().findById("group-session"))
        assertNull(db.botGroupSessionDao().findSessionId("bot", "group"))
        assertNull(db.botAssignmentDao().findById("old-task"))
        assertNotNull(db.botAssignmentDao().findById("other-task"))
        assertNotNull(bots.findById("bot"))
    }

    @Test fun `clearing group removes completed assignment history along with bubbles`() = runBlocking {
        seed()
        db.botAssignmentDao().upsert(assignment("old-task", "group-session"))
        groups.clearMessages("group")
        assertTrue(groups.observeMessages("group").first().isEmpty())
        assertTrue(db.botAssignmentDao().observeForGroup("group").first().isEmpty())
        assertNotNull(groups.findById("group"))
        assertEquals(1, groups.getMembers("group").size)
    }

    private suspend fun seed() {
        bots.upsert(bot)
        groups.upsertGroup(group)
        groups.addMember(GroupMemberEntity("group", "bot", 0, 1))
        groups.upsertMessage(GroupMessageEntity("message", "group", "bot", "Chief", "BOT", "answer", 1, false, null))
        db.botGroupSessionDao().upsert(BotGroupSessionEntity("bot", "group", "group-session", 1))
        db.botScheduleDao().upsert(schedule)
    }

    @Test fun `editing an admin bot preserves all relationships`() = runBlocking {
        seed()
        bots.upsertWithMain(bot.copy(name = "Renamed", modelId = "new-model"))
        assertEquals("Renamed", bots.findById("bot")?.name)
        assertEquals(1, groups.getMembers("group").size)
        assertNotNull(db.botScheduleDao().findById("schedule"))
        assertEquals("group-session", db.botGroupSessionDao().findSessionId("bot", "group"))
    }

    @Test fun `model self selection policy round trips through repositories and group members`() = runBlocking {
        seed()
        val repository = RoomPersonaBotRepository(bots)
        val member = repository.getBot("bot")!!
        assertFalse(member.allowModelSelection)
        repository.saveBot(member.copy(allowModelSelection = true))
        assertTrue(repository.getBotBySessionId("group-session")!!.allowModelSelection)
        assertTrue(groups.getMembers("group").single().allowModelSelection)
        repository.saveBot(repository.getBot("bot")!!.copy(allowModelSelection = false))
        assertFalse(repository.getBot("bot")!!.allowModelSelection)
    }

    @Test fun `editing group metadata preserves messages members and sessions`() = runBlocking {
        seed()
        groups.upsertGroup(group.copy(name = "Renamed", background = "night"))
        assertEquals("Renamed", groups.findById("group")?.name)
        assertEquals("answer", groups.observeMessages("group").first().single().content)
        assertEquals(1, groups.getMembers("group").size)
        assertEquals("group-session", db.botGroupSessionDao().findSessionId("bot", "group"))
    }

    @Test fun `identity resolves dm and isolated group sessions and rejects unknown ids`() = runBlocking {
        seed()
        assertEquals("bot", bots.findBySessionId("dm")?.id)
        assertEquals("bot", bots.findBySessionId("group-session")?.id)
        assertNull(bots.findBySessionId("another-session"))
    }

    @Test fun `bot session ids cover private group and independent assignment sessions`() = runBlocking {
        seed()
        db.botAssignmentDao().upsert(assignment("solo-task", "solo-session").copy(independent = true))
        db.botAssignmentDao().upsert(assignment("shared-task", "shared-session"))
        val ids = bots.observeSessionIds().first()
        assertTrue(ids.containsAll(setOf("dm", "group-session", "solo-session")))
        assertFalse("shared-session" in ids)
    }

    @Test fun `group session refs map group and independent sessions to their chat`() = runBlocking {
        seed()
        db.botAssignmentDao().upsert(assignment("solo-task", "solo-session").copy(independent = true))
        db.botAssignmentDao().upsert(assignment("shared-task", "shared-session"))
        val refs = RoomPersonaBotRepository(bots).observeGroupSessionRefs().first()
        assertEquals("group", refs["group-session"]?.groupId)
        assertEquals("Team", refs["group-session"]?.groupName)
        assertEquals("group", refs["solo-session"]?.groupId)
        assertNull(refs["shared-session"])
        assertNull(refs["dm"])
    }

    @Test fun `staging an occurrence advances atomically and cannot duplicate it`() = runBlocking {
        seed()
        deliveries.stage("schedule", 100, 101, 200)
        deliveries.stage("schedule", 100, 102, 200)
        assertEquals(200L, db.botScheduleDao().findById("schedule")?.nextRunAt)
        assertEquals(1, deliveries.pending(10).size)
        val receipt = deliveries.pending(10).single()
        assertEquals("work", receipt.instruction)
        assertEquals(1, deliveries.claim(receipt.id))
        assertEquals(0, deliveries.claim(receipt.id))
    }

    @Test fun `recovery keeps pending work but fences uncertain admission`() = runBlocking {
        seed()
        deliveries.stage("schedule", 100, 101, 200)
        val receipt = deliveries.pending(10).single()
        deliveries.recoverInterrupted()
        assertEquals(receipt, deliveries.pending(10).single())
        deliveries.claim(receipt.id)
        deliveries.recoverInterrupted()
        assertTrue(deliveries.pending(10).isEmpty())
        db.openHelper.readableDatabase.query("SELECT status FROM bot_schedule_deliveries").use {
            assertTrue(it.moveToFirst())
            assertEquals("unknown", it.getString(0))
        }
    }

    @Test fun `deleting a schedule revokes a staged occurrence`() = runBlocking {
        seed()
        deliveries.stage("schedule", 100, 101, 200)
        db.botScheduleDao().deleteById("schedule")
        assertTrue(deliveries.pending(10).isEmpty())
    }
}

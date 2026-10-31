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
class GroupDeliveryPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.groupChatDao()
    private val groups = RoomGroupChatRepository(dao, Json)

    @After fun close() = db.close()

    private suspend fun seed() {
        val bot = BotEntity("chief", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1)
        db.personaBotDao().upsert(bot)
        db.personaBotDao().upsert(bot.copy(id = "member", sessionId = "member-dm", isMainBot = false))
        dao.upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        dao.addMember(GroupMemberEntity("group", "chief", 0, 1))
        dao.addMember(GroupMemberEntity("group", "member", 1, 1))
    }

    private fun message(id: String = "source") = GroupMessage(id, "group", "user", "我", GroupSenderType.USER, "work", 1)

    @Test fun `pending recipients follow source insertion order even with equal timestamps and reversed ids`() = runBlocking {
        seed()
        groups.stageMessage(message("z-first"), listOf("chief"))
        groups.stageMessage(message("a-second"), listOf("chief"))
        assertEquals(listOf("z-first", "a-second"), groups.pendingDeliveries().first().map { it.sourceMessageId })
    }

    @Test fun `staging is idempotent and retains one source with independent recipient receipts`() = runBlocking {
        seed()
        groups.stageMessage(message(), listOf("chief", "member", "member", "missing"))
        groups.stageMessage(message().copy(content = "changed"), listOf("chief"))
        assertEquals("work", groups.deliverySource("source")!!.content)
        assertEquals(2, groups.pendingDeliveries().first().size)
        assertTrue(groups.claimDelivery("source:member"))
        assertFalse(groups.claimDelivery("source:member"))
        assertTrue(groups.prepareDelivery("source:member", "native-session"))
        assertEquals("native-session", dao.findGroupDelivery("source:member")!!.sessionId)
        assertEquals(listOf("chief"), groups.pendingDeliveries().first().map { it.botId })
    }

    @Test fun `claim and admission survive repository recreation but are never replayed after restart`() = runBlocking {
        seed()
        groups.stageMessage(message(), listOf("chief", "member"))
        assertTrue(groups.claimDelivery("source:member"))
        assertTrue(groups.admitDelivery("source:member", "session", "native-task"))
        val restarted = RoomGroupChatRepository(dao, Json)
        restarted.recoverDeliveries()
        restarted.recoverDeliveries()
        val receipt = dao.findGroupDelivery("source:member")!!
        assertEquals(GroupDelivery.UNKNOWN, receipt.status)
        assertEquals("native-task", receipt.taskId)
        assertFalse(restarted.claimDelivery(receipt.id))
        assertEquals(listOf("chief"), restarted.pendingDeliveries().first().map { it.botId })
        assertEquals(1, groups.observeMessages("group").first().count { it.id.startsWith("delivery-status:") })
    }

    @Test fun `restart settles streaming projection without erasing partial output or reasoning`() = runBlocking {
        seed()
        groups.stageMessage(message(), listOf("chief"))
        groups.claimDelivery("source:chief")
        groups.postMessage(message("delivery-reply:source:chief").copy(
            senderType = GroupSenderType.BOT, content = "partial", isStreaming = true, reasoning = "reasoning",
        ))
        groups.recoverDeliveries()
        val reply = groups.observeMessages("group").first().single { it.id.startsWith("delivery-reply:") }
        assertFalse(reply.isStreaming)
        assertTrue(reply.content.startsWith("partial"))
        assertTrue(reply.content.contains("待核对"))
        assertEquals("reasoning", reply.reasoning)
    }

    @Test fun `removing then readding a member cannot revive old delivery`() = runBlocking {
        seed()
        groups.stageMessage(message(), listOf("member"))
        groups.removeMember("group", "member")
        groups.addMember("group", "member", 1)
        assertFalse(groups.claimDelivery("source:member"))
        assertFalse(groups.prepareDelivery("source:member", "native-session"))
        assertEquals(GroupDelivery.CANCELLED, dao.findGroupDelivery("source:member")!!.status)
    }

    @Test fun `clear cascades receipts and late outcomes cannot recreate cleared messages`() = runBlocking {
        seed()
        groups.stageMessage(message(), listOf("chief"))
        groups.claimDelivery("source:chief")
        groups.clearMessages("group")
        groups.finishDelivery("source:chief", GroupDelivery.UNKNOWN, "late")
        assertNull(dao.findGroupDelivery("source:chief"))
        assertTrue(groups.observeMessages("group").first().isEmpty())
    }

    @Test fun `terminal assignment and its report are atomic and repeated settlement cannot duplicate report`() = runBlocking {
        seed()
        val assignments = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
        assignments.save(BotAssignment("task", "group", "member", "Member", "Build", "work", BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, reportToBotId = "chief"))
        assignments.finish("task", BotAssignmentStatus.COMPLETED, "artifact ready")
        assignments.finish("task", BotAssignmentStatus.FAILED, error = "late callback")
        assertEquals(BotAssignmentStatus.COMPLETED, assignments.get("task")!!.status)
        val receipt = groups.pendingDeliveries().first().single()
        assertEquals("chief", receipt.botId)
        assertEquals("assignment-report:task", receipt.sourceMessageId)
        assertTrue(groups.deliverySource(receipt.sourceMessageId)!!.content.contains("artifact ready"))
        assertEquals(1, groups.observeMessages("group").first().size)
    }

    @Test fun `interrupted assignment produces one durable report while cancellation never wakes chief`() = runBlocking {
        seed()
        val assignments = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
        val task = BotAssignment("task", "group", "member", "Member", "Build", "work", BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, reportToBotId = "chief")
        assignments.save(task)
        assignments.save(task.copy(id = "cancelled"))
        assignments.finish("cancelled", BotAssignmentStatus.CANCELLED)
        assignments.failRunning()
        assignments.failRunning()
        assertEquals(listOf("assignment-report:task"), groups.pendingDeliveries().first().map { it.sourceMessageId })
        assertEquals(2, groups.observeMessages("group").first().size)
    }

    @Test fun `report failure rolls back task settlement`() = runBlocking {
        seed()
        val assignments = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
        assignments.save(BotAssignment("task", "group", "member", "Member", "Build", "work", BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, reportToBotId = "chief"))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_report BEFORE INSERT ON group_messages
            BEGIN SELECT RAISE(ABORT, 'disk failure'); END""")
        assertTrue(runCatching { assignments.finish("task", BotAssignmentStatus.COMPLETED, "done") }.isFailure)
        assertEquals(BotAssignmentStatus.RUNNING, assignments.get("task")!!.status)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
    }
}

package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupLateReplyStatus
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupRound
import top.tianyan.app.core.model.bot.GroupRoundSlot
import top.tianyan.app.core.model.bot.GroupRoundStatus
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.core.model.bot.GroupSlotStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupRoundPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)

    @After fun close() = db.close()

    private suspend fun seed() {
        db.personaBotDao().upsert(
            BotEntity("bot", "Bot", "role", "persona", "seed", null, null, "dm", false, "#123456", 1, 1),
        )
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "bot", "", 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "bot", 0, 1))
    }

    @Test fun `round slots and late reply cursors survive the repository`() = runBlocking {
        seed()
        val round = GroupRound(
            id = "round",
            groupId = "group",
            trigger = "hello",
            imageUrls = listOf("data:image/png;base64,abc"),
            slots = listOf(
                GroupRoundSlot("bot", "Bot", GroupSlotStatus.POSTED),
                GroupRoundSlot("bot", "Bot", GroupSlotStatus.WAITING, trigger = "caller"),
            ),
            status = GroupRoundStatus.RUNNING,
            createdAt = 1,
            updatedAt = 1,
        )
        groups.insertRound(round)
        val loaded = groups.findRound("round")!!
        assertEquals(round.slots, loaded.slots)
        assertEquals(round.imageUrls, loaded.imageUrls)
        assertTrue(groups.runningRounds().any { it.id == round.id })

        val progressed = loaded.copy(
            slots = listOf(GroupRoundSlot("bot", "Bot", GroupSlotStatus.POSTED)),
            updatedAt = 2,
        )
        groups.updateRoundProgress(progressed)
        val stillRunning = groups.findRound("round")!!
        assertEquals(round.imageUrls, stillRunning.imageUrls)
        assertEquals(listOf(GroupRoundSlot("bot", "Bot", GroupSlotStatus.POSTED)), stillRunning.slots)

        groups.finishRound(stillRunning.copy(status = GroupRoundStatus.DONE, updatedAt = 3))
        val finished = groups.findRound("round")!!
        assertTrue(finished.imageUrls.isEmpty())
        assertEquals(GroupRoundStatus.DONE, finished.status)
        assertTrue(groups.runningRounds().isEmpty())

        val late = GroupLateReply(
            id = "late",
            groupId = "group",
            workerId = "bot",
            workerName = "Bot",
            sessionId = "session",
            placeholderId = "placeholder",
            callerBotId = "bot",
            createdAt = 1,
            status = GroupLateReplyStatus.WATCHING,
            taskId = "native-task",
        )
        groups.insertLateReply(late)
        assertEquals("native-task", groups.findLateReply("late")!!.taskId)
        assertEquals(listOf("late"), groups.watchingLateReplies().map { it.id })
        groups.finishLateReply("late", GroupLateReplyStatus.DONE)
        assertTrue(groups.watchingLateReplies().isEmpty())
        assertEquals(GroupLateReplyStatus.DONE, groups.findLateReply("late")!!.status)
    }

    @Test fun `user message and round row are staged in one call`() = runBlocking {
        seed()
        val round = GroupRound(
            id = "round-atomic",
            groupId = "group",
            trigger = "hello",
            slots = listOf(GroupRoundSlot("bot", "Bot")),
            status = GroupRoundStatus.RUNNING,
            createdAt = 1,
            updatedAt = 1,
        )
        groups.stageUserMessageWithRound(
            GroupMessage(
                id = "user-atomic",
                groupId = "group",
                senderId = "user",
                senderName = "我",
                senderType = GroupSenderType.USER,
                content = "hello",
                timestamp = 1,
            ),
            round,
        )

        assertEquals("hello", groups.observeMessages("group").first().single().content)
        assertEquals(GroupRoundStatus.RUNNING, groups.findRound("round-atomic")!!.status)
    }

    @Test fun `finished rows are pruned while live work stays`() = runBlocking {
        seed()
        val slots = listOf(GroupRoundSlot("bot", "Bot"))
        groups.insertRound(
            GroupRound("old", "group", "t", slots = slots, status = GroupRoundStatus.DONE, createdAt = 1, updatedAt = 1),
        )
        groups.insertRound(
            GroupRound("live", "group", "t", slots = slots, status = GroupRoundStatus.RUNNING, createdAt = 1, updatedAt = 1),
        )
        groups.insertLateReply(
            GroupLateReply("done", "group", "bot", "Bot", "s", "p", createdAt = 1, status = GroupLateReplyStatus.DONE),
        )
        groups.insertLateReply(
            GroupLateReply("watching", "group", "bot", "Bot", "s", "p2", createdAt = 1, status = GroupLateReplyStatus.WATCHING),
        )

        groups.pruneFinished(roundBefore = 100, lateBefore = 100)

        assertNull(groups.findRound("old"))
        assertNotNull(groups.findRound("live"))
        assertNull(groups.findLateReply("done"))
        assertNotNull(groups.findLateReply("watching"))
    }
}

package top.tianyan.app.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.BotEntity
import top.tianyan.app.core.database.GroupChatEntity
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.core.model.bot.GroupRound

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupRoundAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AppLogger(context) { it }
    private val received = CompletableDeferred<Pair<String, List<String>>>()
    private val responder = GroupRoundResponder { _, _, trigger, _, _, images, _, _, _ ->
        received.complete(trigger to images)
        GroupTurnOutcome("done", failed = false)
    }

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun seed() {
        db.personaBotDao().upsert(BotEntity("bot", "Bot", "role", "persona", "seed", null, null, "dm", true, "#123456", 1, 1))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "bot", "", 1))
        groups.addMember("group", "bot", 0)
    }

    private fun coordinator(repository: GroupChatRepository = groups) =
        GroupRoundCoordinator(repository, GroupReplyQueue(scope), responder, scope, logger)

    @Test fun `message is visible during preparation and recovery only sees prepared rounds`() = runBlocking {
        seed()
        val preparing = CompletableDeferred<Unit>()
        val copied = CompletableDeferred<Unit>()
        val stopped = object : GroupChatRepository by groups {
            override suspend fun insertRound(round: GroupRound) {
                groups.insertRound(round)
                throw CancellationException("process stopped after round commit and before launch")
            }
        }
        val image = "data:image/png;base64,AQID"
        val description = "x".repeat(1_200) + " /attachments/copied/image.png"
        val admission = GroupRoundAdmission(stopped, coordinator(stopped), prepare = {
            preparing.complete(Unit)
            copied.await()
            PreparedGroupAttachments(description, listOf(image))
        }, notify = { _, _ -> fail("Unexpected notification") })
        val send = async(Dispatchers.Default) {
            admission.send("group", "inspect image", listOf(GroupAttachment("image.png", "content://fixture/image")))
        }
        withTimeout(5_000) { preparing.await() }
        assertEquals("inspect image", groups.observeMessages("group").first().single().content)
        assertTrue(groups.runningRounds().isEmpty())
        coordinator().recover()
        assertFalse(received.isCompleted)
        copied.complete(Unit)
        try { withTimeout(5_000) { send.await() }; fail("Simulated stop must propagate") }
        catch (_: CancellationException) { }
        assertEquals(1, groups.observeMessages("group").first().size)
        val saved = groups.runningRounds().single()
        assertEquals(listOf(image), saved.imageUrls)
        assertTrue(saved.trigger.contains(description))
        coordinator().recover()
        val (trigger, images) = withTimeout(5_000) { received.await() }
        assertTrue(trigger.contains(description))
        assertEquals(listOf(image), images)
    }

    @Test fun `attachment failure preserves user message without creating runnable work`() = runBlocking {
        seed()
        val notices = mutableListOf<String>()
        val admission = GroupRoundAdmission(groups, coordinator(),
            prepare = { throw java.io.IOException("permission lost") }, notify = { _, text -> notices += text })
        admission.send("group", "read file", listOf(GroupAttachment("file", "content://fixture/file")))
        assertTrue(groups.runningRounds().isEmpty())
        assertFalse(received.isCompleted)
        assertEquals("read file", groups.observeMessages("group").first().single().content)
        assertTrue(notices.single().contains("permission lost"))
    }
}

package top.tianyan.app.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupDeliveryPumpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replies = GroupReplyQueue(scope)
    private val logger = AppLogger(context) { it }

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun stage(id: String) {
        groups.stageMessage(GroupMessage(id, "group", "user", "我", GroupSenderType.USER, id, 1), listOf("bot"))
    }

    private suspend fun seed() {
        db.personaBotDao().upsert(BotEntity("bot", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1))
        db.personaBotDao().upsert(BotEntity("admin", "Admin", "lead", "", "seed", null, null, "dm-admin", false, "#123456", 1, 1))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "admin", "", 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "bot", 0, 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "admin", 1, 1))
    }

    private suspend fun awaitStatus(id: String, status: String) = withTimeout(10_000) {
        while (db.groupChatDao().findGroupDelivery("$id:bot")?.status != status) delay(10)
    }

    @Test fun `restart delivers only pending sources once and preserves uncertain native admission`() = runBlocking {
        seed()
        stage("before-crash")
        groups.claimDelivery("before-crash:bot")
        groups.admitDelivery("before-crash:bot", "session", "native-task")
        stage("pending")
        val executions = mutableListOf<String>()
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            synchronized(executions) { executions += receipt.sourceMessageId }
            groups.admitDelivery(receipt.id, "session", "new-task")
            GroupTurnOutcome("done", false)
        }
        pump.start()
        pump.start()
        awaitStatus("pending", GroupDelivery.COMPLETED)
        assertEquals(GroupDelivery.UNKNOWN, db.groupChatDao().findGroupDelivery("before-crash:bot")!!.status)
        assertEquals(listOf("pending"), synchronized(executions) { executions.toList() })
        assertTrue(groups.pendingDeliveries().first().isEmpty())
    }

    @Test fun `submission that commits then throws is retained as unknown and never retried`() = runBlocking {
        seed()
        stage("source")
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            groups.admitDelivery(receipt.id, "session", "accepted-task")
            error("crash after native admission")
        }
        pump.start()
        awaitStatus("source", GroupDelivery.UNKNOWN)
        assertEquals("accepted-task", db.groupChatDao().findGroupDelivery("source:bot")!!.taskId)
        assertFalse(groups.claimDelivery("source:bot"))
    }

    @Test fun `stop cancels running and queued durable work without affecting future messages`() = runBlocking {
        seed()
        stage("first")
        stage("second")
        val entered = CompletableDeferred<Unit>()
        val executions = mutableListOf<String>()
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            synchronized(executions) { executions += receipt.sourceMessageId }
            if (receipt.sourceMessageId == "first") {
                entered.complete(Unit)
                awaitCancellation()
            }
            GroupTurnOutcome("done", false)
        }
        pump.start()
        withTimeout(10_000) { entered.await() }
        groups.cancelDeliveries("group")
        replies.cancel("group")
        awaitStatus("first", GroupDelivery.CANCELLED)
        awaitStatus("second", GroupDelivery.CANCELLED)
        stage("third")
        awaitStatus("third", GroupDelivery.COMPLETED)
        assertEquals(listOf("first", "third"), synchronized(executions) { executions.toList() })
    }

    @Test fun `native admission then approval and response settles the original group receipt and bubble`() = runBlocking {
        seed()
        stage("source")
        val bot = RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
        val control = FakeControl()
        val responder = GroupBotResponder(groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()))
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            responder.respond(groups.getGroup("group")!!, bot, "source", "", listOf(bot), emptyList(), null,
                deliveryId = receipt.id)
        }
        pump.start()
        awaitStatus("source", GroupDelivery.ADMITTED)
        assertEquals("native-task", db.groupChatDao().findGroupDelivery("source:bot")!!.taskId)
        assertTrue(groups.observeMessages("group").first().any { it.isStreaming })
        control.resolveApproval("approval", true)
        awaitStatus("source", GroupDelivery.COMPLETED)
        val reply = groups.observeMessages("group").first().single { it.senderType == GroupSenderType.BOT }
        assertEquals("done after approval", reply.content)
        assertFalse(reply.isStreaming)
        assertEquals(1, control.submissions)
    }

    @Test fun `native submit throwing after acceptance leaves a visible uncertain result without resubmission`() = runBlocking {
        seed()
        stage("source")
        val bot = RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
        val control = FakeControl(throwOnSubmit = true)
        val responder = GroupBotResponder(groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()))
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            responder.respond(groups.getGroup("group")!!, bot, "source", "", listOf(bot), emptyList(), null,
                deliveryId = receipt.id)
        }
        pump.start()
        awaitStatus("source", GroupDelivery.UNKNOWN)
        assertFalse(groups.claimDelivery("source:bot"))
        assertEquals(1, control.submissions)
        assertTrue(groups.observeMessages("group").first().any { it.content.contains("待核对") })
    }

    @Test fun `pre-admission membership rejection does not cancel an already running native session`() = runBlocking {
        seed()
        val group = groups.getGroup("group")!!
        val bot = RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
        val control = FakeControl()
        control.sessionRunStates.value = mapOf("session" to SessionRunState.RUNNING)
        val port = object : top.tianyan.app.harness.projection.LiveMessagePort {
            override suspend fun append(sessionId: String, message: HarnessMessage) = error("not submitted")
            override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) = error("not submitted")
            override fun snapshot(sessionId: String): List<HarnessMessage> = emptyList()
        }
        val responder = GroupBotResponder(groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
            GroupReplyFreshness(groups, port))
        groups.removeMember("group", "bot")
        val result = responder.respond(group, bot, "instruction", "", listOf(bot), emptyList(), null)
        assertTrue(result.failed)
        assertFalse(result.uncertain)
        assertEquals(0, control.submissions)
        assertTrue(control.cancellations.isEmpty())
        assertEquals(SessionRunState.RUNNING, control.sessionRunStates.value["session"])
    }

    @Test fun `uncertain timeout outcome keeps receipt unknown with its reason and is not replayed`() = runBlocking {
        seed()
        stage("timed-out")
        var executions = 0
        val pump = GroupDeliveryPump(groups, replies, scope, logger) { receipt ->
            executions++
            groups.admitDelivery(receipt.id, "session", "accepted-task")
            GroupTurnOutcome("模型等待超时，结果待核对", failed = true, uncertain = true)
        }
        pump.start()
        awaitStatus("timed-out", GroupDelivery.UNKNOWN)
        assertTrue(db.groupChatDao().findGroupDelivery("timed-out:bot")!!.error!!.contains("待核对"))
        assertEquals(1, executions)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
    }

    private class FakeControl(private val throwOnSubmit: Boolean = false) : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        private val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        @Volatile var submissions = 0
        val cancellations = mutableListOf<String?>()
        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue, attachments: List<top.tianyan.app.harness.MessageAttachment>): PromptSubmission {
            submissions++
            sessionRunStates.value = mapOf(sessionId to SessionRunState.WAITING_APPROVAL)
            if (throwOnSubmit) error("native receipt lost after acceptance")
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
        }
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) {
            messages.value = listOf(AssistantText("answer", System.currentTimeMillis(), "done after approval", totalMs = 1))
            sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        }
        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun cancel(targetSessionId: String?) { cancellations += targetSessionId; sessionRunStates.value = mapOf("session" to SessionRunState.IDLE) }
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}

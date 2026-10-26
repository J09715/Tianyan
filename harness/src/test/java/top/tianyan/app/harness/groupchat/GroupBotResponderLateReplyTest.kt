package top.tianyan.app.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.BotEntity
import top.tianyan.app.core.database.GroupChatEntity
import top.tianyan.app.core.database.GroupMemberEntity
import top.tianyan.app.core.database.RoomBotAssignmentRepository
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupLateReplyStatus
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupBotResponderLateReplyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AppLogger(context) { it }
    private val control = FakeControl()
    private val liveReplies = MutableStateFlow<Map<String, GroupReplyProgress>>(emptyMap())

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun seed(): Bot {
        db.personaBotDao().upsert(
            BotEntity("bot", "Bot", "role", "persona", "seed", null, null, "dm", false, "#123456", 1, 1),
        )
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "bot", "", 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "bot", 0, 1))
        return RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
    }

    @Test fun `a working turn yields and posts its late result`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, liveReplies,
            lateReplyScope = scope, workGraceMs = 80,
        )
        val late = CompletableDeferred<String>()
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null,
            onLateReply = { late.complete(it) },
        )
        assertTrue(result.working)

        control.finish("final text")
        withTimeout(5_000) { late.await() }

        val message = withTimeout(5_000) {
            groups.observeMessages("group").first { rows ->
                rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
            }
        }.single { it.senderType == GroupSenderType.BOT }
        assertEquals("final text", message.content)
        assertFalse(message.isStreaming)
    }

    @Test fun `waiting for approval yields and posts the result later`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        control.approvalOnSubmit = true
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
            lateReplyScope = scope, workGraceMs = 50,
        )
        val late = CompletableDeferred<String>()
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null,
            onLateReply = { late.complete(it) },
        )
        assertTrue(result.working)

        control.finish("approved result")
        withTimeout(5_000) { late.await() }

        val message = withTimeout(5_000) {
            groups.observeMessages("group").first { rows ->
                rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
            }
        }.single { it.senderType == GroupSenderType.BOT }
        assertEquals("approved result", message.content)
    }

    @Test fun `a silent pass removes the placeholder and reports passed`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        control.replyOnSubmit = "[PASS]"
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
        )
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null, silentPass = true,
        )
        assertTrue(result.passed)
        assertFalse(result.working)
        assertTrue(groups.observeMessages("group").first().none { it.senderType == GroupSenderType.BOT })
    }

    @Test fun `a watching late reply is recovered after restart`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = GroupBotResponder(
                groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
                lateReplyScope = firstScope, workGraceMs = 500,
            )
            val result = withTimeout(5_000) {
                first.respond(
                    group, bot, "instruction", "", listOf(bot), emptyList(), null,
                    lateReplyCallerId = "caller", onLateReply = {},
                )
            }
            assertTrue(result.working)
        } finally {
            firstScope.cancel()
        }
        val watching = groups.watchingLateReplies()
        assertEquals(1, watching.size)

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recovered = CompletableDeferred<String>()
            val second = GroupBotResponder(
                groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
                lateReplyScope = secondScope, workGraceMs = 50,
            )
            second.recoverLateReplies { _, text -> recovered.complete(text) }
            control.finish("recovered text")
            withTimeout(5_000) { recovered.await() }

            val message = withTimeout(5_000) {
                groups.observeMessages("group").first { rows ->
                    rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
                }
            }.single { it.senderType == GroupSenderType.BOT }
            assertEquals("recovered text", message.content)
            assertEquals(GroupLateReplyStatus.DONE, groups.findLateReply(watching.single().id)?.status)
        } finally {
            secondScope.cancel()
        }
    }

    @Test fun `scoped responder rebuilds ordinary and round context instead of trusting unfiltered history strings`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        listOf("lead", "dev").forEachIndexed { i, id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", "seed", null, null, "dm-$id", false, "#123456", 1, 1))
            groups.addMember("group", id, i + 1)
        }
        groups.saveTeam("group", mapOf("dev" to "lead"))
        val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
        tasks.save(BotAssignment("leader", "group", "lead", "lead", "lead", "lead", BotAssignmentStatus.COMPLETED,
            createdAt = 1, startedAt = 1, sessionId = "lead-session", reportToBotId = "bot", independent = true))
        tasks.save(BotAssignment("child", "group", "dev", "dev", "dev", "dev", BotAssignmentStatus.COMPLETED,
            createdAt = 1, startedAt = 1, sessionId = "dev-session", reportToBotId = "lead", independent = true, parentAssignmentId = "leader"))
        groups.postMessage(GroupMessage("user", "group", "user", "User", GroupSenderType.USER, "BROADCAST_CORRECTION", 1))
        groups.postMessage(GroupMessage("direct", "group", "lead", "lead", GroupSenderType.BOT, "DIRECT_DELIVERY", 2, delegatedTaskId = "leader"))
        groups.postMessage(GroupMessage("nested", "group", "dev", "dev", GroupSenderType.BOT, "GRANDCHILD_RAW", 3, delegatedTaskId = "child"))
        listOf(false, true).forEach { round ->
            val native = FakeControl().apply { replyOnSubmit = "done" }
            val responder = GroupBotResponder(groups, { _, _ -> "session" }, native, logger, MutableStateFlow(emptyMap()),
                lateReplyScope = scope, contextScope = GroupContextScope(groups, tasks))
            val result = responder.respond(group, bot, "instruction", "UNFILTERED_CACHE", groups.getMembers("group"),
                emptyList(), null, promptHistory = if (round) "UNFILTERED_ROUND" else null)
            assertFalse(result.failed)
            assertTrue(native.submittedPrompt.contains("BROADCAST_CORRECTION"))
            assertTrue(native.submittedPrompt.contains("DIRECT_DELIVERY"))
            assertFalse(native.submittedPrompt.contains("GRANDCHILD_RAW"))
            assertFalse(native.submittedPrompt.contains("UNFILTERED_"))
        }
        assertTrue(groups.observeMessages("group").first().any { it.content == "GRANDCHILD_RAW" })
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        private val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        @Volatile var approvalOnSubmit = false
        @Volatile var replyOnSubmit: String? = null
        var submittedPrompt: String = ""

        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
        override suspend fun deleteSession(id: String) = Unit

        override suspend fun submit(
            sessionId: String,
            text: String,
            imageUrls: List<String>,
            queue: PromptQueue,
        ): PromptSubmission {
            submittedPrompt = text
            val reply = replyOnSubmit
            if (reply != null) {
                messages.value = listOf(
                    AssistantText("final", System.currentTimeMillis(), reply, totalMs = 1),
                )
                sessionRunStates.value = mapOf(sessionId to SessionRunState.COMPLETED)
                return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
            }
            sessionRunStates.value = mapOf(
                sessionId to if (approvalOnSubmit) SessionRunState.WAITING_APPROVAL else SessionRunState.RUNNING,
            )
            messages.value = listOf(
                AssistantText("working", System.currentTimeMillis(), "working on it", totalMs = 1),
            )
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
        }

        fun finish(text: String) {
            messages.value = messages.value + AssistantText("final", System.currentTimeMillis(), text, totalMs = 1)
            sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        }

        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun cancel(targetSessionId: String?) {
            sessionRunStates.value = mapOf("session" to SessionRunState.IDLE)
        }
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}

package top.tianyan.app.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

/**
 * 脚本化"假模型"全链编排回归（借鉴 DS Bot dev/mock-lll.mjs）：
 * 不打真实 API，按 Bot 逐个剧本回放回复，驱动 真实的 入口受理 → 轮次协调 →
 * 群应答（含会话提交与流式落库）→ @ 接力 → 轮次收尾 全链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptedGroupOrchestrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AppLogger(context) { it }
    private val control = ScriptedControl()

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun seedAdminFirst() {
        db.personaBotDao().upsert(
            BotEntity("chief", "Chief", "admin", "persona", "seed", null, null, "chief-dm", true, "#123456", 1, 1),
        )
        listOf("b" to "乙", "c" to "丙").forEach { (id, name) ->
            db.personaBotDao().upsert(
                BotEntity(id, name, "member", "persona", "seed", null, null, "$id-dm", false, "#123456", 1, 1),
            )
        }
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        listOf("chief", "b", "c").forEachIndexed { index, id -> groups.addMember("group", id, index) }
    }

    @Test fun `admin answers first and relays to the mentioned member`() = runBlocking {
        seedAdminFirst()
        // 剧本：管理员点名乙接手；乙交付；丙全程不被打扰。
        control.script("session-Chief", "@乙 请接手这部分")
        control.script("session-乙", "已完成：方案 B 落盘并自测通过")

        val harness = admission()
        harness.send("group", "整理部署方案，给出结论", emptyList())
        withTimeout(10_000) {
            while (groups.runningRounds().isNotEmpty()) delay(10)
        }

        // 串行且有序：先 Chief（管理员先答），后 乙（@ 接力）
        assertEquals(
            listOf("session-Chief", "session-乙"),
            control.prompts.map { it.first },
        )
        val messages = groups.observeMessages("group").first()
        assertTrue(messages.any { it.senderName == "Chief" && it.content.contains("@乙") })
        assertTrue(messages.any { it.senderName == "乙" && it.content.contains("方案 B") })
        // 未被点名的成员不发言也不被调用
        assertFalse(messages.any { it.senderName == "丙" })
        assertTrue(control.prompts.none { it.first == "session-丙" })
    }

    @Test fun `explicit mention targets the member and skips the admin`() = runBlocking {
        seedAdminFirst()
        control.script("session-乙", "收到，直接交付")

        val harness = admission()
        harness.send("group", "@乙 只需要你核对清单", emptyList())
        withTimeout(10_000) {
            while (groups.runningRounds().isNotEmpty()) delay(10)
        }

        assertEquals(listOf("session-乙"), control.prompts.map { it.first })
        val messages = groups.observeMessages("group").first()
        assertTrue(messages.any { it.senderName == "乙" && it.content.contains("交付") })
        assertFalse(messages.any { it.senderType == GroupSenderType.BOT && it.senderName == "Chief" })
    }

    private fun admission(): GroupRoundAdmission {
        val responder = GroupBotResponder(
            groups,
            { bot, _ -> "session-${bot.name}" },
            control,
            logger,
            MutableStateFlow(emptyMap()),
            lateReplyScope = scope,
            workGraceMs = 50,
        )
        val coordinator = GroupRoundCoordinator(
            groups,
            GroupReplyQueue(scope),
            GroupRoundResponder { group, bot, trigger, history, members, _, _, onSubmitted, onLateReply ->
                responder.respond(
                    group, bot, trigger, history, members, emptyList(), null,
                    onSubmitted = onSubmitted, onLateReply = onLateReply,
                )
            },
            scope,
            logger,
        )
        return GroupRoundAdmission(
            groups,
            coordinator,
            prepare = { PreparedGroupAttachments() },
            notify = { _, _ -> },
        )
    }

    /** 按会话剧本回放的假 SessionControl：每个 Bot 的回复提前写好，逐次弹出。 */
    private class ScriptedControl : SessionControl {
        val prompts = mutableListOf<Pair<String, String>>()
        private val scripts = mutableMapOf<String, ArrayDeque<String>>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<HarnessMessage>>>()
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())

        fun script(sessionId: String, reply: String) {
            scripts.getOrPut(sessionId) { ArrayDeque() }.addLast(reply)
        }

        private fun flowOf(sessionId: String) = flows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

        override fun messagesForSession(sessionId: String) = flowOf(sessionId)
        override suspend fun prepareRemoteSession(sessionId: String) = flowOf(sessionId).value
        override suspend fun persistedMessages(sessionId: String) = flowOf(sessionId).value
        override suspend fun deleteSession(id: String) = Unit

        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue, attachments: List<top.tianyan.app.harness.MessageAttachment>): PromptSubmission {
            prompts += sessionId to text
            val reply = scripts[sessionId]?.removeFirstOrNull() ?: "done"
            flowOf(sessionId).value = listOf(AssistantText("final", System.currentTimeMillis(), reply, totalMs = 1))
            sessionRunStates.value = mapOf(sessionId to SessionRunState.COMPLETED)
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
        }

        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>, attachments: List<top.tianyan.app.harness.MessageAttachment>) = Unit
        override fun cancel(targetSessionId: String?) = Unit
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}

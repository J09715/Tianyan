package top.tianyan.app.harness.bot.diagnostics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentRequest
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotDiagnosticsExporterTest {
    @Test fun `export uses group runtime state and never falls back to private histories`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val file = java.io.File.createTempFile("bot-diagnostics", ".md", context.cacheDir)
        try {
            val chief = BotEntity("chief", "阿策", "lead", "persona", "seed", null, null, "dm-chief", true, "#123456", 1, 1)
            db.personaBotDao().upsert(chief)
            db.personaBotDao().upsert(chief.copy(id = "member", name = "小前", sessionId = "dm-member", isMainBot = false))
            db.groupChatDao().upsertGroup(GroupChatEntity("group", "俄罗斯方块开发", "chief", "", 1))
            db.groupChatDao().addMember(GroupMemberEntity("group", "chief", 0, 1))
            db.groupChatDao().addMember(GroupMemberEntity("group", "member", 1, 1))
            db.botGroupSessionDao().upsert(BotGroupSessionEntity("chief", "group", "group-chief", 1))
            val control = FakeControl()
            control.sessionRunStates.value = mapOf("group-chief" to SessionRunState.RUNNING)
            control.sessionStatuses.value = mapOf("group-chief" to "正在委派")
            control.messages.value = listOf(AssistantText("reply", 1, "group answer"))
            val runtime = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
            val assignments = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
            assignments.createIndependent(BotAssignment("task", "group", "member", "小前", "Task", "Task",
                BotAssignmentStatus.QUEUED, emptyList(), 1, 1, sessionId = "task-session", reportToBotId = "chief", independent = true),
                HarnessSessionEntity("task-session", "Task", 1, 1, null),
                BotAssignmentRequest("initial:task", "task", "Task", 1))
            runtime.saveOperation(HarnessOperationEntity("task-active", "task-session", "main",
                "task-run", "running", "provider", 1, 2, null, "{}", "provider_request"))
            runtime.saveOperation(HarnessOperationEntity("active", "group-chief", "subagent:frontend",
                "run", "running", "provider", 1, 2, null, "{}", "provider_request"))
            val exporter = BotDiagnosticsExporter(
                RoomGroupChatRepository(db.groupChatDao(), Json),
                assignments,
                RoomBotGroupSessionRepository(db.botGroupSessionDao()),
                control, runtime, Json,
                AppLogger(context) { it },
            )
            exporter.exportGroup("group", file)
            val report = file.readText()
            assertTrue(report.contains("runState：RUNNING"))
            assertTrue(report.contains("status：正在委派"))
            assertTrue(report.contains("尚未建立群会话"))
            assertTrue(report.contains("group answer"))
            assertTrue(report.contains("subagent:frontend · running/provider"))
            assertTrue(report.contains("独立任务会话：`task-session`"))
            assertTrue(report.contains("independent answer"))
            assertTrue(report.contains("任务活动执行：main · running/provider"))
            assertTrue(report.contains("不能将气泡时间当作完成时间"))
            assertFalse(report.contains("dm-chief"))
            assertFalse(report.contains("dm-member"))

            // 与真实删除路径一致：先清理树/运行事件，再删除会话及其 Bot 映射。
            runtime.deleteSessionData("group-chief")
            db.harnessSessionDao().deleteSession("group-chief")
            assertNull(db.botGroupSessionDao().findSessionId("chief", "group"))
            db.botGroupSessionDao().upsert(BotGroupSessionEntity("chief", "group", "fresh-chief", 3))
            control.messages.value = listOf(AssistantText("fresh", 3, "fresh answer"))
            exporter.exportGroup("group", file)
            val fresh = file.readText()
            assertTrue(fresh.contains("fresh answer"))
            assertFalse(fresh.contains("group answer"))
            assertFalse(fresh.contains("group-chief"))
            assertFalse(fresh.contains("subagent:frontend"))
            val scoped = exporter.scopedLogs("old group group-chief\nnew group fresh-chief\nunrelated group", setOf("fresh-chief"))
            assertEquals("new group fresh-chief", scoped)
            assertEquals("（无匹配日志）", exporter.scopedLogs("old group group-chief", emptySet()))
        } finally {
            db.close()
            file.delete()
        }
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow(mapOf("session" to SessionRunState.RUNNING))
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        val cancelled = mutableListOf<String?>()
        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String): List<HarnessMessage> {
            if (sessionId == "task-session") return listOf(AssistantText("task-reply", 1, "independent answer"))
            check(sessionId in setOf("group-chief", "fresh-chief")) { "Must not read private history: $sessionId" }
            return messages.value
        }
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue): PromptSubmission = error("unused")
        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun cancel(targetSessionId: String?) { cancelled += targetSessionId }
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}

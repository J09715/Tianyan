package top.tianyan.app.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.*
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.lifecycle.RuntimeLifecycleSupervisor
import kotlinx.serialization.json.JsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentForegroundServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val control = FakeControl()
    private lateinit var controller: ServiceController<AgentForegroundService>
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        val sessions = Proxy.newProxyInstance(HarnessSessionRepository::class.java.classLoader,
            arrayOf(HarnessSessionRepository::class.java)) { _, method, args ->
            when (method.name) {
                "findById" -> HarnessSessionEntity(args!![0] as String, "任务 ${args[0]}", 0, 0, null)
                "toString" -> "sessions"
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as HarnessSessionRepository
        startKoin { modules(module {
            single<SessionControl> { control }
            single<HarnessSessionRepository> { sessions }
            single { RuntimeLifecycleSupervisor(context) }
        }) }
        controller = Robolectric.buildService(AgentForegroundService::class.java).create()
    }

    @After fun cleanup() { controller.destroy(); manager.cancelAll(); stopKoin() }
    private fun start() {
        controller.get().onStartCommand(Intent().setAction(AgentForegroundService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun states(vararg entries: Pair<String, SessionRunState>) {
        control.sessionRunStates.value = mapOf(*entries)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun notice(id: String): Notification = manager.activeNotifications
        .first { it.tag == id && it.id == AgentNotifications.SESSION_ID }.notification
    private fun text(id: String) = notice(id).extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    @Test fun `service started before admission stays alive to receive the run`() {
        start()
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        states("background" to SessionRunState.RUNNING)
        assertTrue(text("background").contains("等待模型响应"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `parallel sessions settle independently with stable notification identity`() {
        states("one" to SessionRunState.RUNNING, "two" to SessionRunState.RUNNING)
        start()
        val secondStop = notice("two").actions.first().actionIntent
        states("one" to SessionRunState.COMPLETED, "two" to SessionRunState.RUNNING)
        assertEquals("任务 one · 任务已完成", notice("one").extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notice("two").flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(secondStop, notice("two").actions.first().actionIntent)
        states("one" to SessionRunState.COMPLETED, "two" to SessionRunState.FAILED)
        assertEquals("任务 two · 执行失败", notice("two").extras.getString(Notification.EXTRA_TITLE))
        assertTrue(manager.activeNotifications.any { it.tag == "one" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `approval stays visible and resumes without a false completion`() {
        states("one" to SessionRunState.RUNNING)
        start()
        states("one" to SessionRunState.WAITING_APPROVAL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(text("one").contains("等待用户批准"))
        assertTrue(notice("one").flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        states("one" to SessionRunState.RUNNING)
        control.sessionStatuses.value = mapOf("one" to "正在生成回复")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(text("one").contains("正在生成回复"))
    }

    @Test fun `progress updates from tools even without a status change`() {
        states("one" to SessionRunState.RUNNING)
        start()
        control.messages.value = listOf(ToolCall("call", 0, HarnessTool.READ, JsonObject(emptyMap())))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(text("one").contains("已完成 0 / 已发起 1"))
        control.messages.value += ToolResult("result", 1, "call", true, "SECRET OUTPUT")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(text("one").contains("已完成 1 / 已发起 1"))
        assertFalse(text("one").contains("SECRET OUTPUT"))
    }

    @Test fun `summary stop cancels every active session including approval`() {
        states("one" to SessionRunState.RUNNING, "two" to SessionRunState.WAITING_APPROVAL)
        start()
        controller.get().onStartCommand(Intent().setAction(AgentForegroundService.ACTION_STOP), 0, 2)
        assertEquals(setOf("one", "two"), control.cancelled.toSet())
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("unrelated-ui-session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        val cancelled = mutableListOf<String?>()
        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
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

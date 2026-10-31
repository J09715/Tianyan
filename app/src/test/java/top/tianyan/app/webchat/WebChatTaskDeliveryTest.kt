package top.tianyan.app.webchat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.task.RoomAgentTaskRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.harness.task.*
import top.tianyan.app.runtime.webchat.WebChatMessage
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WebChatTaskDeliveryTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected operation: ${method.name}") } as T

    private fun delivery(id: String, evidence: Boolean = true, complete: Boolean = true) = TaskDelivery(id, 10,
        TaskDeliveryReport("task", "s", "op", null, "leaf", "claim",
            if (evidence) listOf(ToolObservation("call", "result", "base", ObservationState.SUCCEEDED)) else emptyList(),
            if (evidence) listOf(DeliveryArtifact("result", ".tianyan-outputs/tool-test.txt", "a".repeat(64), 100, true)) else emptyList(),
            complete, listOf(DeliveryGap.GOAL_NOT_VERIFIED) + if (complete) emptyList() else listOf(DeliveryGap.HISTORY_INCOMPLETE), 10))

    private fun contract(messages: List<HarnessMessage>, block: suspend (TianyanWebChatAgentGateway) -> Unit) = runBlocking {
        val transcript = MutableStateFlow(messages)
        val control = Proxy.newProxyInstance(SessionControl::class.java.classLoader, arrayOf(SessionControl::class.java)) {
            _, method, args -> when (method.name) {
                "persistedMessages" -> { assertEquals("s", args?.get(0)); messages }
                "messagesForSession" -> { assertEquals("s", args?.get(0)); transcript }
                "getSessionRunStates" -> MutableStateFlow(mapOf("s" to SessionRunState.COMPLETED))
                "getSessionStatuses" -> MutableStateFlow(emptyMap<String, String>())
                else -> error("Unexpected session operation: ${method.name}")
            }
        } as SessionControl
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val gateway = TianyanWebChatAgentGateway(control, unused(), unused(),
            AgentApprovalRepository(db.agentApprovalDao()), RoomAgentTaskRepository(db.agentTaskDao()))
        try { withTimeout(10_000) { block(gateway) } } finally { db.close() }
    }

    private fun assertUnverified(message: WebChatMessage, source: TaskDelivery) {
        assertEquals(source.id, message.id)
        assertEquals("unverified", message.content["status"]?.jsonPrimitive?.content)
        assertEquals("task_delivery", message.content["toolType"]?.jsonPrimitive?.content)
        val restored = Json.decodeFromJsonElement(TaskDeliveryReport.serializer(), message.content.getValue("report"))
        assertEquals(source.report, restored)
        assertTrue(DeliveryGap.GOAL_NOT_VERIFIED in restored.gaps)
        assertFalse(message.isLoading)
        assertFalse(message.isError)
    }

    @Test fun successfulToolsRemainUnverifiedInDurableAndLiveTranscripts() {
        val record = delivery("delivery")
        contract(listOf(record)) { gateway ->
            assertUnverified(gateway.messages("s").single(), record)
            assertUnverified(gateway.observeSession("s").first().messages.single(), record)
        }
    }

    @Test fun plainConversationIsHiddenButIncompleteEvidenceRemainsVisible() {
        val plain = delivery("plain", evidence = false)
        val incomplete = delivery("incomplete", evidence = false, complete = false)
        contract(listOf(plain, incomplete)) { gateway ->
            assertUnverified(gateway.messages("s").single(), incomplete)
            assertUnverified(gateway.observeSession("s").first().messages.single(), incomplete)
        }
    }
}

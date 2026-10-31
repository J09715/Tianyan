package top.tianyan.app.webchat

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.*
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.runtime.webchat.WebChatInputMode

class TianyanWebChatAgentGatewayTest {
    private fun control(action: (String, Array<out Any?>) -> Any?): SessionControl =
        Proxy.newProxyInstance(SessionControl::class.java.classLoader, arrayOf(SessionControl::class.java)) {
            _, method, args -> action(method.name, args ?: emptyArray())
        } as SessionControl
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected dependency: ${method.name}") } as T
    private fun approvals(find: () -> AgentApprovalRequestEntity? = { error("Unexpected approval read") }): AgentApprovalRepository {
        val dao = Proxy.newProxyInstance(AgentApprovalDao::class.java.classLoader, arrayOf(AgentApprovalDao::class.java)) {
            _, method, _ -> when (method.name) {
                "observeSettings" -> flowOf<AgentApprovalSettingsEntity?>(null)
                "findRequest" -> find()
                else -> error("Unexpected approval dependency: ${method.name}")
            }
        } as AgentApprovalDao
        return AgentApprovalRepository(dao)
    }
    private fun gateway(control: SessionControl, approvals: AgentApprovalRepository = approvals()) =
        TianyanWebChatAgentGateway(control, unused(), unused(), approvals, unused())

    @Test fun everyWireModeUsesExplicitSessionAndTheSharedAwaitedSubmission() = runBlocking {
        for (mode in WebChatInputMode.entries) {
            var invoked = false
            val gateway = gateway(control { name, args ->
                assertEquals("submit", name)
                assertEquals("remote-session", args[0])
                assertEquals("hello", args[1])
                assertEquals(listOf("image"), args[2])
                assertEquals(mode.id, (args[3] as PromptQueue).id)
                invoked = true
                PromptSubmission.Accepted(PromptSubmission.Disposition.QUEUED, "durable", args[3] as PromptQueue, "item")
            })
            val receipt = gateway.send("remote-session", "hello", listOf("image"), mode)
            assertTrue(invoked)
            assertEquals("queued", receipt.disposition)
            assertEquals("durable", receipt.taskId)
            assertEquals("item", receipt.queueItemId)
        }
    }

    @Test fun submissionRejectionCannotBecomeSuccessfulWebAcknowledgment() = runBlocking {
        for (reason in PromptSubmission.Rejection.entries) {
            val gateway = gateway(control { name, _ ->
                assertEquals("submit", name); PromptSubmission.Rejected(reason)
            })
            try { gateway.send("s", "hello", emptyList()); fail("must reject") }
            catch (failure: IllegalArgumentException) { assertFalse(failure.message.isNullOrBlank()) }
        }
    }

    @Test fun cancellationAndInfrastructureFailurePropagateUnmodified() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("storage"))) {
            val gateway = gateway(control { _, _ -> throw failure })
            try { gateway.send("s", "hello", emptyList()); fail("must throw") }
            catch (caught: Exception) { assertSame(failure, caught) }
        }
    }

    @Test fun cancelTargetsRemoteSessionWithoutSelectingForeground() {
        val gateway = gateway(control { name, args ->
            assertEquals("cancel", name); assertEquals("remote", args[0]); Unit
        })
        gateway.cancel("remote")
    }

    @Test fun finalMessageReadUsesPersistedHistoryWithoutSelectingForeground() = runBlocking {
        val gateway = gateway(control { name, args ->
            assertEquals("persistedMessages", name)
            assertEquals("remote", args[0])
            listOf(AssistantText("final", 1, "committed answer"))
        })
        assertEquals("final", gateway.messages("remote").single().id)
    }

    @Test fun approvalsFromAnotherSessionOrAlreadySettledAreRejected() = runBlocking {
        var request = AgentApprovalRequestEntity(id = "request", sessionId = "other", toolCallId = "call",
            toolName = "tool", argumentsJson = "{}", workspace = "", riskLevel = "low",
            reason = "reason", summary = "summary", createdAt = 1, expiresAt = 100)
        val gateway = gateway(control { name, _ -> error("Approval must not reach control: $name") }, approvals { request })
        assertFalse(gateway.resolveApproval("s", "request", true))
        request = request.copy(sessionId = "s", status = AgentApprovalRequestEntity.STATUS_APPROVED)
        assertFalse(gateway.resolveApproval("s", "request", true))
    }
}

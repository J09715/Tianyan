package top.tianyan.app.harness.groupchat

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GroupReplyObserverTest {
    private val log = AppLogger(ApplicationProvider.getApplicationContext()) { it }
    private val bot = Bot("bot", "name", "role", "persona", "seed", sessionId = "session", createdAt = 0, updatedAt = 0)

    @Test fun `tool started during progress publication wins over stale execution deadline`() = runTest {
        val control = FakeControl()
        var now = 0L
        var startedTool = false
        val outcome = GroupReplyObserver(control, log) { now }.awaitReply("session", bot, emptySet(), 0,
            executionPolicy = GroupExecutionPolicy(10, 20)) { progress ->
            when {
                progress.steps.any { it.success == null } -> {
                    now += 1_000
                    control.messages.value += ToolResult("built", now, "build", true, "built")
                    control.messages.value += AssistantText("done", now, "done", totalMs = 1)
                    control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
                }
                progress.executionWarning && !startedTool -> {
                    startedTool = true
                    control.messages.value += ToolCall("build", now, HarnessTool.BASE, JsonObject(emptyMap()))
                }
                else -> if (now == 0L) now = 20
            }
        }
        assertTrue(startedTool)
        assertEquals("done", outcome.candidate?.text)
        assertFalse(outcome.noExecutionProgress)
        assertTrue(control.cancelled.isEmpty())
    }

    @Test fun `reasoning and transport heartbeat do not extend an execution progress deadline`() = runTest {
        val control = FakeControl()
        val updates = mutableListOf<GroupReplyProgress>()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0, executionPolicy = GroupExecutionPolicy(120, 300), onProgress = updates::add) }
        runCurrent()
        for (time in 1..29) {
            advanceTimeBy(10)
            control.messages.value = listOf(AssistantText("thought", 0, "", reasoning = "thinking $time"))
            control.modelOutputRevisions.value = mapOf("session" to time.toLong())
            control.providerActivities.value = mapOf("session" to top.tianyan.app.harness.session.ProviderActivity("op", 0, 1, lastTransportAt = time * 10L))
            runCurrent()
        }
        assertTrue(updates.any { it.executionWarning })
        assertFalse(result.isCompleted)
        advanceTimeBy(10)
        runCurrent()
        assertTrue(result.await().noExecutionProgress)
        assertEquals(listOf("session"), control.cancelled)
    }

    @Test fun `long tool gets its own deadline and completed tools restart progress time`() = runTest {
        val control = FakeControl()
        control.messages.value = listOf(ToolCall("build", 0, HarnessTool.BASE, JsonObject(emptyMap())))
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0, executionPolicy = GroupExecutionPolicy(120, 300)) {} }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(control.cancelled.isEmpty())
        control.messages.value += ToolResult("built", 5_000, "build", true, "done")
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await().noExecutionProgress)
    }

    @Test fun `live group projection forwards chunks without materializing full strings`() = runTest {
        val control = FakeControl()
        val builder = top.tianyan.app.core.model.ChunkedText.Builder()
        val updates = mutableListOf<GroupReplyProgress>()
        val result = async {
            GroupReplyObserver(control, log) { testScheduler.currentTime }
                .awaitReply("session", bot, emptySet(), 0, onProgress = updates::add)
        }
        runCurrent()
        repeat(3) {
            builder.append("text ".repeat(1_000))
            val text = builder.snapshot()
            control.messages.value = listOf(AssistantText("answer", 0, text))
            runCurrent()
            assertSame(text, updates.last().contentText)
            val flattened = text.javaClass.getDeclaredField("flattened").apply { isAccessible = true }
            assertNull(flattened.get(text))
        }
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals(builder.length, result.await().candidate?.textContent?.length)
    }

    @Test fun `new tokens publish immediately and unchanged input causes no refresh loop`() = runTest {
        val control = FakeControl()
        val updates = mutableListOf<GroupReplyProgress>()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { updates += it } }
        runCurrent()
        val initialCount = updates.size
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(initialCount, updates.size)
        control.messages.value = listOf(AssistantText("one", 1_000, "", reasoning = "first token"))
        runCurrent()
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals("first token", updates.last().reasoning)
        control.messages.value = listOf(AssistantText("one", 1_000, "done", reasoning = "first token", totalMs = 1))
        runCurrent()
        assertFalse(result.isCompleted)
        control.messages.value += AssistantText("revision", 1_001, "corrected after group update", totalMs = 2)
        runCurrent()
        assertFalse(result.isCompleted)
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals("corrected after group update", result.await().candidate?.text)
    }

    @Test fun `reasoning updates are separate from body and preserve earlier reasoning`() = runTest {
        val control = FakeControl()
        val updates = mutableListOf<GroupReplyProgress>()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0, onProgress = updates::add) }
        control.messages.value = listOf(AssistantText("one", 0, "", reasoning = "thought ".repeat(200)))
        advanceTimeBy(150)
        runCurrent()
        assertTrue(updates.last().content.isEmpty())
        assertTrue(updates.last().reasoning.length > 600)
        control.messages.value += AssistantText("two", 150, "answer", reasoning = "next thought", totalMs = 200)
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        advanceTimeBy(100)
        val outcome = result.await()
        assertEquals("answer", outcome.candidate?.text)
        assertTrue(outcome.reasoning.contains("next thought"))
        assertTrue(outcome.reasoning.startsWith("thought"))
    }

    @Test fun `waiting for approval does not finish the reply or trip the first token timeout`() = runTest {
        val control = FakeControl()
        control.sessionRunStates.value = mapOf("session" to SessionRunState.WAITING_APPROVAL)
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        advanceTimeBy(75 * 60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(control.cancelled.isEmpty())
        control.messages.value = listOf(AssistantText("reply", testScheduler.currentTime, "resumed", totalMs = 1))
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        advanceTimeBy(100)
        assertEquals("resumed", result.await().candidate?.text)
    }

    @Test fun `empty assistant placeholder is not a first token`() = runTest {
        val control = FakeControl()
        control.messages.value = listOf(AssistantText("placeholder", 0, ""))
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(control.cancelled.isEmpty())
        advanceTimeBy(GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS - 120_000)
        runCurrent()
        assertTrue(result.await().firstTokenTimedOut)
        assertEquals(listOf("session"), control.cancelled)
    }

    @Test fun `hidden reasoning may stay silent for several minutes before responding`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        control.messages.value = listOf(AssistantText("reply", testScheduler.currentTime, "done", totalMs = 1))
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals("done", result.await().candidate?.text)
        assertTrue(control.cancelled.isEmpty())
    }

    @Test fun `stalled provider stops without waiting for whole turn limit while new reasoning resets its deadline`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        control.messages.value = listOf(AssistantText("progress", 0, "Reading plan"))
        runCurrent()
        advanceTimeBy(119_000)
        control.messages.value = listOf(AssistantText("progress", 0, "Reading plan", reasoning = "next step"))
        runCurrent()
        advanceTimeBy(GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS - 1_000)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(result.await().stalled)
        assertEquals(listOf("session"), control.cancelled)
    }

    @Test fun `long tools do not trigger model inactivity but their result starts a fresh deadline`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        control.messages.value = listOf(ToolCall("call", 0, HarnessTool.BASE, JsonObject(emptyMap())))
        runCurrent()
        advanceTimeBy(180_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(control.cancelled.isEmpty())
        control.messages.value += ToolResult("result", 180_000, "call", true, "done", durationMs = 180_000)
        runCurrent()
        advanceTimeBy(GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS)
        runCurrent()
        assertTrue(result.await().stalled)
    }

    @Test fun `failed or cancelled native run cannot turn earlier progress into success`() = runTest {
        for (state in listOf(SessionRunState.FAILED, SessionRunState.IDLE)) {
            val control = FakeControl()
            val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
                .awaitReply("session", bot, emptySet(), testScheduler.currentTime) { } }
            control.messages.value = listOf(AssistantText("progress", testScheduler.currentTime, "working"))
            runCurrent()
            control.sessionRunStates.value = mapOf("session" to state)
            runCurrent()
            val outcome = result.await()
            assertEquals(state == SessionRunState.FAILED, outcome.failed)
            assertEquals(state == SessionRunState.IDLE, outcome.interrupted)
        }
    }

    @Test fun `long tool can finish after the former fifteen minute limit`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        control.messages.value = listOf(ToolCall("build", 0, HarnessTool.BASE, JsonObject(emptyMap())))
        runCurrent()
        advanceTimeBy(20 * 60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(control.cancelled.isEmpty())
        control.messages.value += ToolResult("built", testScheduler.currentTime, "build", true, "done")
        control.messages.value += AssistantText("reply", testScheduler.currentTime, "verified", totalMs = 1)
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals("verified", result.await().candidate?.text)
        assertTrue(control.cancelled.isEmpty())
    }

    @Test fun `continuing model progress can run beyond fifteen minutes`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        runCurrent()
        repeat(4) { index ->
            advanceTimeBy(5 * 60_000)
            control.messages.value = listOf(AssistantText("reply", 0, "progress $index"))
            runCurrent()
            assertFalse(result.isCompleted)
        }
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals("progress 3", result.await().candidate?.text)
        assertTrue(control.cancelled.isEmpty())
    }

    @Test fun `whole turn still has a bounded sixty minute safety deadline`() = runTest {
        val control = FakeControl()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { } }
        control.messages.value = listOf(ToolCall("build", 0, HarnessTool.BASE, JsonObject(emptyMap())))
        runCurrent()
        advanceTimeBy(60 * 60_000)
        runCurrent()
        assertTrue(result.await().replyTimedOut)
        assertEquals(listOf("session"), control.cancelled)
    }

    @Test fun `streaming tool arguments keep provider active even before a tool call is complete`() = runTest {
        val control = FakeControl()
        control.modelOutputRevisions.value = mapOf("session" to 10L)
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0, baselineOutputRevision = 10L) { } }
        runCurrent()
        repeat(2) { index ->
            advanceTimeBy(100_000)
            control.modelOutputRevisions.value = mapOf("session" to 11L + index)
            runCurrent()
            assertFalse(result.isCompleted)
        }
        assertTrue(control.cancelled.isEmpty())
        advanceTimeBy(GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS)
        runCurrent()
        assertTrue(result.await().stalled)
    }

    @Test fun `model output from a previous turn does not satisfy a new first token deadline`() = runTest {
        val control = FakeControl()
        control.modelOutputRevisions.value = mapOf("session" to 10L)
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0, baselineOutputRevision = 10L) { } }
        advanceTimeBy(GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS)
        runCurrent()
        assertTrue(result.await().firstTokenTimedOut)
    }

    @Test fun `tool awaiting approval stays pending without premature success or failure`() = runTest {
        val control = FakeControl()
        val updates = mutableListOf<GroupReplyProgress>()
        val result = async { GroupReplyObserver(control, log) { testScheduler.currentTime }
            .awaitReply("session", bot, emptySet(), 0) { updates += it } }
        val call = ToolCall("call", 0, HarnessTool.BASE, JsonObject(emptyMap()))
        control.messages.value = listOf(call)
        runCurrent()
        assertEquals(1, updates.last().steps.size)
        assertNull(updates.last().steps.first().success)

        // 等待审批时（普通工具 success=false 但 awaitingApproval=true），步骤状态保持 null（pending）
        control.messages.value = listOf(call, ToolResult("res1", 100, "call", false, "approval needed", awaitingApproval = true))
        runCurrent()
        assertNull("等待审批时不应结算为失败", updates.last().steps.first().success)

        // 用户批准后执行成功：最终结果 awaitingApproval=false，结算为真实结果
        control.messages.value = listOf(call, ToolResult("res2", 200, "call", true, "done", durationMs = 150))
        control.sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        runCurrent()
        assertEquals(true, updates.last().steps.first().success)
        assertEquals(150L, updates.last().steps.first().durationMs)
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow(mapOf("session" to SessionRunState.RUNNING))
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        override val modelOutputRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
        override val providerActivities = MutableStateFlow<Map<String, top.tianyan.app.harness.session.ProviderActivity>>(emptyMap())
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

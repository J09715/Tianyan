package top.tianyan.app.harness

import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.tianyan.app.harness.core.LlmApi

@RunWith(Parameterized::class)
class LongReasoningStreamTest(private val protocol: LlmApi) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun adapter(client: OkHttpClient): LlmApiAdapter = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> ChatApi(client, json)
        LlmApi.OPENAI_RESPONSES -> ResponsesApi(client, json)
        LlmApi.ANTHROPIC_MESSAGES -> AnthropicApi(client, json)
    }

    private fun reasoning(index: Int): String = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> """{"choices":[{"delta":{"reasoning_content":"analysis step $index. "}}]}"""
        LlmApi.OPENAI_RESPONSES -> """{"type":"response.reasoning_summary_text.delta","delta":"analysis step $index. "}"""
        LlmApi.ANTHROPIC_MESSAGES -> """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"analysis step $index. "}}"""
    }

    private fun ending(): String = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"
        LlmApi.OPENAI_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        LlmApi.ANTHROPIC_MESSAGES -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
    }

    @Test fun `active reasoning may exceed the read timeout in total without being cut off`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body = ": keepalive\n\n" + (0 until 32).joinToString("") { "data: ${reasoning(it)}\n\n" } + ending()
            server.enqueue(MockResponse().setChunkedBody(body, 64).throttleBody(64, 40, TimeUnit.MILLISECONDS))
            val api = adapter(OkHttpClient.Builder().readTimeout(1_000, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build())
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), "fake", pureChatMode = true)
            val observed = StringBuilder()
            val start = System.nanoTime()
            val result = api.chatStream(model, listOf(ApiMessage("user", "test")), onReasoning = { observed.append(it) }, onDelta = {})
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 1_000)
            assertTrue(observed.toString().contains("analysis step 31"))
            assertEquals("answer", result.content)
        }
    }

    @Test fun `user cancellation interrupts reasoning immediately`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody((0 until 100).joinToString("") { "data: ${reasoning(it)}\n\n" } + ending(), 64)
                .throttleBody(64, 40, TimeUnit.MILLISECONDS))
            val transport = ProviderTransport(OkHttpClient(), json)
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), "fake",
                protocol = if (protocol == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
                responseApiEnabled = protocol == LlmApi.OPENAI_RESPONSES, pureChatMode = true)
            val thinking = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                transport.chatStream(model, listOf(ApiMessage("user", "test")), onReasoning = { thinking.complete(Unit) }, onDelta = {})
            }
            try {
                withTimeout(3_000) { thinking.await() }
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally { job.cancelAndJoin() }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun protocols(): List<Array<Any>> = LlmApi.entries.map { arrayOf<Any>(it) }
    }
}

class ProviderStreamTimeoutPolicyTest {
    @Test fun `hidden reasoning gets a bounded ten minute initial wait`() {
        for (tokens in listOf(1_000, 60_000, 161_000)) {
            assertEquals(600_000L, ProviderClient.resolveFirstEventTimeoutMs(tokens, reasoningEnabled = true))
        }
        assertEquals(90_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000, reasoningEnabled = false))
    }

    @Test fun `heartbeat cancels first event watchdog without requiring exposed reasoning`() {
        val state = AtomicInteger(ProviderClient.FIRST_EVENT_WAITING)
        val watchdog = Job()
        observeStreamHeartbeat("event: ping", state, watchdog)
        assertFalse(watchdog.isCancelled)
        observeStreamHeartbeat(": keepalive", state, watchdog)
        assertEquals(ProviderClient.FIRST_EVENT_RECEIVED, state.get())
        assertTrue(watchdog.isCancelled)
        val timedOut = AtomicInteger(ProviderClient.FIRST_EVENT_TIMED_OUT)
        observeStreamHeartbeat(": ping", timedOut, Job())
        assertEquals(ProviderClient.FIRST_EVENT_TIMED_OUT, timedOut.get())
    }

    @Test fun `timeout errors distinguish client first wait and read inactivity from EOF`() {
        val error = SocketTimeoutException("timeout")
        val first = streamFailure(error, ProviderClient.FIRST_EVENT_TIMED_OUT, 600_000, 600_000)
        assertTrue(first is FirstResponseTimeoutException)
        assertEquals(600_000L, (first as FirstResponseTimeoutException).waitedMs)
        assertTrue(first.message.orEmpty().startsWith(FIRST_RESPONSE_TIMEOUT_MARK))
        assertFalse(first.message.orEmpty().contains("执行异常"))
        assertFalse(HarnessProviderRunner.isTransientFailure(first))
        val idle = streamFailure(error, ProviderClient.FIRST_EVENT_RECEIVED, 600_000, 600_000)
        assertTrue(idle.message.orEmpty().contains("读取超时"))
        assertSame(error, idle.cause)
        assertTrue(HarnessProviderRunner.isTransientFailure(idle))
        val eof = EOFException("upstream ended")
        assertSame(eof, streamFailure(eof, ProviderClient.FIRST_EVENT_RECEIVED, 600_000, 600_000))
    }

    @Test fun `anthropic continuing pauses cannot be reported as completed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(9) {
                server.enqueue(MockResponse().setBody("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"still thinking\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"))
            }
            val api = AnthropicApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), "fake")
            try {
                api.chatStream(model, listOf(ApiMessage("user", "test")), onDelta = {})
                fail("paused reasoning is not a completed response")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("续跑上限"))
                assertEquals(9, server.requestCount)
            }
        }
    }
}

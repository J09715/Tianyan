package top.tianyan.app.harness

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.tianyan.app.harness.core.LlmApi

/** The same provider policy must hold for each real wire implementation. */
@RunWith(Parameterized::class)
class ProviderTransportTest(private val api: LlmApi) {
    private lateinit var server: MockWebServer
    private lateinit var transport: ProviderTransport
    private val messages = listOf(ApiMessage(role = "user", content = "hi"))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        transport = ProviderTransport(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After fun tearDown() { server.shutdown() }

    private fun model() = ModelConfig(
        name = "shared gateway", provider = "custom", model = "test-model",
        baseUrl = server.url("/v1").toString().removeSuffix("/"), apiKey = "key-one",
        protocol = if (api == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
        responseApiEnabled = api == LlmApi.OPENAI_RESPONSES,
        customHeaders = "X-Gateway: custom-value",
    )

    private fun reply(): String = when (api) {
        LlmApi.OPENAI_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        LlmApi.OPENAI_RESPONSES -> """{"id":"resp_1","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}"""
        LlmApi.ANTHROPIC_MESSAGES -> """{"content":[{"type":"text","text":"ok"}]}"""
    }

    private fun path(): String = when (api) {
        LlmApi.OPENAI_COMPLETIONS -> "/v1/chat/completions"
        LlmApi.OPENAI_RESPONSES -> "/v1/responses"
        LlmApi.ANTHROPIC_MESSAGES -> "/v1/messages"
    }

    private fun keyHeader(): String = if (api == LlmApi.ANTHROPIC_MESSAGES) "x-api-key" else "Authorization"
    private fun auth(key: String): String = if (api == LlmApi.ANTHROPIC_MESSAGES) key else "Bearer $key"

    private fun streamReply(): String {
        val events = when (api) {
            LlmApi.OPENAI_COMPLETIONS -> listOf(
                """{"choices":[{"delta":{"reasoning_content":"analysis for this request"}}]}""",
                """{"choices":[{"delta":{"content":"ok"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"write","arguments":"{\"path\":\"x\"}"}}]}}]}""",
                "[DONE]",
            )
            LlmApi.OPENAI_RESPONSES -> listOf(
                """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"delta":"analysis for this request"}""",
                """{"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"delta":"ok"}""",
                """{"type":"response.output_item.added","output_index":2,"item":{"id":"fc_1","call_id":"call_1","type":"function_call","name":"write","arguments":"","status":"in_progress"}}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":2,"delta":"{\"path\":\"x\"}"}""",
                """{"type":"response.completed","response":{"id":"resp_1"}}""",
            )
            LlmApi.ANTHROPIC_MESSAGES -> listOf(
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"analysis for this request"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"write","input":{}}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"x\"}"}}""",
                """{"type":"message_stop"}""",
            )
        }
        return events.joinToString("\n\n", postfix = "\n\n") { "data: $it" }
    }

    @Test fun `provider name is independent of endpoint and auth`() = runBlocking {
        server.enqueue(MockResponse().setBody(reply()))
        assertEquals("ok", transport.chat(model(), messages).content)
        val request = server.takeRequest()
        assertEquals(path(), request.path)
        assertEquals(auth("key-one"), request.getHeader(keyHeader()))
        assertEquals("custom-value", request.getHeader("X-Gateway"))
    }

    @Test fun `stream delivers content reasoning tools timing and final request diagnostics`() = runBlocking {
        server.enqueue(MockResponse().setBody(streamReply()).setHeader("Content-Type", "text/event-stream"))
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val tools = mutableListOf<ToolCallStreamProgress>()
        var diagnostics = 0
        var requestEndpoint = ""
        var requestBody = ""
        var requestBytes = 0L
        var requestSecrets: Collection<String> = emptyList()
        val result = transport.chatStream(
            model(), messages, onReasoning = { reasoning.append(it) }, onToolProgress = { tools += it },
            onRequest = { endpoint, body, bytes, secrets ->
                diagnostics++
                requestEndpoint = endpoint
                requestBody = body
                requestBytes = bytes
                requestSecrets = secrets
            }, onDelta = { content.append(it) },
        )
        assertEquals("ok", result.content)
        assertEquals("ok", content.toString())
        assertEquals("analysis for this request", reasoning.toString())
        assertEquals(reasoning.toString(), result.reasoningContent)
        assertEquals("call_1", result.toolCalls.single().id)
        assertTrue(tools.isNotEmpty())
        assertNotNull(result.reasoningMs)
        assertEquals(1, diagnostics)
        // Observer exceptions are isolated; assert outside the callback to catch regressions.
        assertEquals(path().substringAfterLast('/'), requestEndpoint)
        assertTrue(requestBody.contains("test-model"))
        assertTrue(requestBytes > 0)
        assertTrue(requestSecrets.contains("key-one"))
        assertEquals(path(), server.takeRequest().path)
    }

    @Test fun `diagnostics exceptions do not abort streaming`() = runBlocking {
        server.enqueue(MockResponse().setBody(streamReply()))
        assertEquals("ok", transport.chatStream(model(), messages, onRequest = { _, _, _, _ ->
            error("observer failed")
        }) {}.content)
    }

    @Test fun `rate limit retries next key with the same protocol`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
            .setBody("""{"error":{"message":"rate limited"}}"""))
        server.enqueue(MockResponse().setBody(reply()))
        assertEquals("ok", transport.chat(model().copy(apiKeys = listOf("key-one", "key-two")), messages).content)
        assertEquals(auth("key-one"), server.takeRequest().getHeader(keyHeader()))
        val retry = server.takeRequest()
        assertEquals(path(), retry.path)
        assertEquals(auth("key-two"), retry.getHeader(keyHeader()))
    }

    @Test fun `non rate limit errors are not retried with another credential`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
        val error = runCatching { transport.chat(model().copy(apiKeys = listOf("key-one", "key-two")), messages) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, server.requestCount)
    }

    @Test fun `empty successful response fails at shared boundary`() = runBlocking {
        val empty = when (api) {
            LlmApi.OPENAI_COMPLETIONS -> """{"choices":[]}"""
            LlmApi.OPENAI_RESPONSES -> """{"output":[]}"""
            LlmApi.ANTHROPIC_MESSAGES -> """{"content":[]}"""
        }
        server.enqueue(MockResponse().setBody(empty))
        assertTrue(runCatching { transport.chat(model(), messages) }.exceptionOrNull() is LlmEmptyResponseException)
    }

    @Test fun `cancellation closes the socket without rotating credentials`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) {
            transport.chat(model().copy(apiKeys = listOf("key-one", "key-two")), messages)
        }
        try {
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `stream cancellation closes the socket without rotating credentials`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) {
            transport.chatStream(model().copy(apiKeys = listOf("key-one", "key-two")), messages) {}
        }
        try {
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `orphan tool results are repaired before wire serialization`() = runBlocking {
        server.enqueue(MockResponse().setBody(reply()))
        transport.chat(model(), messages + ApiMessage(role = "tool", content = "orphan-result", tool_call_id = "missing"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("orphan-result"))
        assertTrue(body.contains("历史顺序异常"))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun protocols(): List<Array<Any>> = LlmApi.entries.map { arrayOf<Any>(it) }
    }
}

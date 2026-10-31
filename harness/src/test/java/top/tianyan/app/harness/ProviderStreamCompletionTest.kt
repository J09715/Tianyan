package top.tianyan.app.harness

import java.io.EOFException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderStreamCompletionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withServer(block: (MockWebServer, ModelConfig) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server, ModelConfig("test", "openai", "test", server.url("/v1").toString(), "fake"))
        }
    }

    private fun enqueue(server: MockWebServer, vararg events: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" }))
    }

    @Test fun `chat EOF after one character is retryable and never successful`() = withServer { server, model ->
        enqueue(server, """{"choices":[{"delta":{"content":"好"}}]}""")
        val text = StringBuilder()
        try {
            runBlocking { ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = { text.append(it) }) }
            fail("partial stream must fail")
        } catch (error: EOFException) {
            assertEquals("好", text.toString())
            assertTrue(HarnessProviderRunner.isTransientFailure(error))
        }
    }

    @Test fun `chat supports done marker or finish reason without duplicating text`() = withServer { server, model ->
        for (end in listOf("[DONE]", """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")) {
            enqueue(server, """{"choices":[{"delta":{"content":"好"}}]}""", end)
            val text = StringBuilder()
            val result = runBlocking {
                ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = { text.append(it) })
            }
            assertEquals("好", result.content)
            assertEquals("好", text.toString())
        }
    }

    @Test fun `SSE context errors after partial text retain overflow classification`() = withServer { server, model ->
        enqueue(server, """{"choices":[{"delta":{"content":"好"}}]}""",
            """{"error":{"code":"context_length_exceeded","message":"prompt is too long"}}""")
        try {
            runBlocking { ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = {}) }
            fail("overflow must reach recovery")
        } catch (_: LlmContextOverflowException) { }
    }

    @Test fun `length finish reason cannot silently complete even with done marker`() = withServer { server, model ->
        enqueue(server, """{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}""", "[DONE]")
        try {
            runBlocking { ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = {}) }
            fail("truncation must be reported")
        } catch (error: LlmOutputLimitException) { assertTrue(error.message.orEmpty().contains("token")) }
    }

    @Test fun `responses incomplete and error events cannot complete`() = withServer { server, model ->
        val api = ResponsesApi(OkHttpClient(), json)
        val events = listOf(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            """{"type":"response.failed","response":{"error":{"code":"context_length_exceeded","message":"prompt is too long"}}}""",
            """{"type":"error","code":"context_length_exceeded","message":"prompt is too long"}""",
        )
        events.forEachIndexed { index, event ->
            enqueue(server, """{"type":"response.output_text.delta","delta":"好"}""", event)
            try {
                runBlocking { api.chatStream(model, emptyList(), onDelta = {}) }
                fail("failed Responses event must be reported")
            } catch (error: Exception) {
                assertTrue(if (index == 0) error is LlmOutputLimitException else error is LlmContextOverflowException)
            }
        }
    }

    @Test fun `anthropic requires message stop and classifies stream errors`() = withServer { server, model ->
        val api = AnthropicApi(OkHttpClient(), json)
        val delta = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}"""
        enqueue(server, delta)
        try {
            runBlocking { api.chatStream(model, emptyList(), onDelta = {}) }
            fail("Anthropic partial EOF must fail")
        } catch (_: EOFException) { }
        enqueue(server, delta, """{"type":"message_stop"}""")
        assertEquals("好", runBlocking { api.chatStream(model, emptyList(), onDelta = {}) }.content)
        enqueue(server, delta, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        try {
            runBlocking { api.chatStream(model, emptyList(), onDelta = {}) }
            fail("overload must be retryable")
        } catch (_: TransientHttpException) { }
    }

    @Test fun `one token length response on huge input triggers folding rather than output only error`() {
        try {
            requireOutputCompletion("length", 161_098, 1)
            fail("large input with exhausted residual output must recover")
        } catch (_: LlmContextOverflowException) { }
    }

    @Test fun `JSON gateway fallback still reports output exhaustion`() = withServer { server, model ->
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}"""))
        try {
            runBlocking { ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = {}) }
            fail("JSON fallback must not complete truncated output")
        } catch (error: LlmOutputLimitException) { assertTrue(error.message.orEmpty().contains("token")) }
    }

    @Test fun `length with partial tool arguments reaches output recovery without returning tools`() = withServer { server, model ->
        enqueue(server, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"base","arguments":"{\"command\":\"echo"}}]},"finish_reason":"length"}]}""", "[DONE]")
        val error = runCatching {
            runBlocking { ChatApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = {}) }
        }.exceptionOrNull()
        assertTrue(error is LlmOutputLimitException)
    }

    @Test fun `anthropic max tokens is classified as recoverable output truncation`() = withServer { server, model ->
        enqueue(server,
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
            """{"type":"message_stop"}""")
        val error = runCatching {
            runBlocking { AnthropicApi(OkHttpClient(), json).chatStream(model, emptyList(), onDelta = {}) }
        }.exceptionOrNull()
        assertTrue(error is LlmOutputLimitException)
    }

    @Test fun `output budget refuses exhausted windows instead of silently requesting one token`() {
        val messages = listOf(ApiMessage("user", "hello"))
        val input = ContextWindowPolicy.estimateApiMessages(messages)
        for (remaining in listOf(-1, 0, 1, 2, 255)) {
            try {
                ContextWindowPolicy.outputBudget(null, 8_192, messages, input + remaining)
                fail("exhausted window must reach compaction: $remaining")
            } catch (_: LlmContextOverflowException) { }
        }
        assertEquals(1_000, ContextWindowPolicy.outputBudget(null, 8_192, messages, input + 1_000))
        assertEquals(1, ContextWindowPolicy.outputBudget(1, 8_192, messages, input + 1))
        assertEquals(8_192, ContextWindowPolicy.outputBudget(null, 8_192, messages, null))
    }

    @Test fun `tool schema is included in output headroom check`() {
        val messages = listOf(ApiMessage("user", "hello"))
        val context = ContextWindowPolicy.estimateApiMessages(messages) + 1_000
        assertEquals(1_000, ContextWindowPolicy.outputBudget(null, 8_192, messages, context))
        try {
            ContextWindowPolicy.outputBudget(null, 8_192, messages, context, toolSchemaTokens = 1_000)
            fail("tool schema must not consume the reserved output silently")
        } catch (_: LlmContextOverflowException) { }
    }

    @Test fun `all request builders reject exhausted windows before sending HTTP`() = withServer { server, model ->
        val messages = listOf(ApiMessage("user", "long input ".repeat(1_000)))
        val exhausted = model.copy(contextTokens = 100, pureChatMode = true)
        val apis = listOf(ChatApi(OkHttpClient(), json), ResponsesApi(OkHttpClient(), json), AnthropicApi(OkHttpClient(), json))
        for (api in apis) {
            try {
                runBlocking { api.chatStream(exhausted, messages, onDelta = {}) }
                fail("exhausted request must compact before HTTP")
            } catch (_: LlmContextOverflowException) { }
        }
        assertEquals(0, server.requestCount)
    }
}

package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
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
class ProviderReviewRegressionTest(private val protocol: LlmApi) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun model(server: MockWebServer) = ModelConfig(
        "test", "custom", "test", server.url("/v1").toString(), "fake",
        protocol = if (protocol == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
        responseApiEnabled = protocol == LlmApi.OPENAI_RESPONSES,
        pureChatMode = true, contextTokens = 128_000,
    )
    private fun successfulReply(text: String): String = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        LlmApi.OPENAI_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        LlmApi.ANTHROPIC_MESSAGES -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$text\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
    }
    private fun errorReply(kind: String, message: String): String = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> "data: {\"error\":{\"code\":\"$kind\",\"message\":\"$message\"}}\n\n"
        LlmApi.OPENAI_RESPONSES -> "data: {\"type\":\"error\",\"code\":\"$kind\",\"message\":\"$message\"}\n\n"
        LlmApi.ANTHROPIC_MESSAGES -> "data: {\"type\":\"error\",\"error\":{\"type\":\"$kind\",\"message\":\"$message\"}}\n\n"
    }

    @Test fun `completed short answers on large contexts are accepted without retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = ProviderTransport(OkHttpClient(), json)
            val messages = listOf(ApiMessage("user", "x".repeat(175_000)))
            assertTrue(ProviderClient.estimateApiMessageTokens(messages) >= 64_000)
            for (text in listOf("好", "42")) {
                server.enqueue(MockResponse().setBody(successfulReply(text)))
                assertEquals(text, transport.chatStream(model(server), messages, onDelta = {}).content)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `temporary stream rate limits reach retryable classification instead of quota failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (kind in listOf("rate_limit_exceeded", "rate_limit_error")) {
                val transport = ProviderTransport(OkHttpClient(), json)
                server.enqueue(MockResponse().setBody(errorReply(kind, "Please slow down and retry")))
                val error = runCatching { transport.chatStream(model(server), listOf(ApiMessage("user", "hi")), onDelta = {}) }.exceptionOrNull()
                assertTrue(error is LlmRateLimitException)
                assertFalse((error as LlmRateLimitException).quotaExhausted)
                assertFalse(error.message.orEmpty().contains("HTTP 400"))
            }
        }
    }

    @Test fun `stream quota exhaustion stays permanent even when message omits quota words`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(errorReply("insufficient_quota", "Please update your plan")))
            val error = runCatching {
                ProviderTransport(OkHttpClient(), json).chatStream(model(server), listOf(ApiMessage("user", "hi")), onDelta = {})
            }.exceptionOrNull()
            assertTrue(error is LlmRateLimitException)
            assertTrue((error as LlmRateLimitException).quotaExhausted)
        }
    }

    @Test fun `partial stream rate limit returns to runner before rotating keys or emitting duplicate text`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = ProviderTransport(OkHttpClient(), json)
            val configured = model(server).copy(apiKeys = listOf("fake-one", "fake-two"))
            val partial = when (protocol) {
                LlmApi.OPENAI_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                LlmApi.OPENAI_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                LlmApi.ANTHROPIC_MESSAGES -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n"
            }
            server.enqueue(MockResponse().setBody(partial + errorReply("rate_limit_error", "Please retry")))
            server.enqueue(MockResponse().setBody(successfulReply("fresh answer")))
            val text = StringBuilder()
            val error = runCatching {
                transport.chatStream(configured, listOf(ApiMessage("user", "hi")), onDelta = { text.append(it) })
            }.exceptionOrNull()
            assertTrue(error is LlmRateLimitException)
            assertFalse((error as LlmRateLimitException).allowImmediateKeyRotation)
            assertEquals(1, server.requestCount)
            assertEquals("partial", text.toString())
            // The turn runner clears its stream baseline before invoking this next attempt.
            text.clear()
            assertEquals("fresh answer", transport.chatStream(configured,
                listOf(ApiMessage("user", "hi")), onDelta = { text.append(it) }).content)
            assertEquals("fresh answer", text.toString())
            assertEquals(2, server.requestCount)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun protocols(): List<Array<Any>> = LlmApi.entries.map { arrayOf<Any>(it) }
    }
}

class ProviderJsonFallbackRegressionTest {
    @Test fun `JSON and SSE residual length responses have identical recoverable classification`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), "fake",
                pureChatMode = true, contextTokens = 128_000)
            val messages = listOf(ApiMessage("user", "x".repeat(175_000)))
            val api = ChatApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
            for (body in listOf(
                """{"choices":[{"message":{"content":"好"},"finish_reason":"length"}]}""",
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n",
            )) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(runCatching { api.chatStream(model, messages, onDelta = {}) }.exceptionOrNull() is LlmContextOverflowException)
            }
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}"""))
            assertEquals("好", api.chatStream(model, messages, onDelta = {}).content)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"length"}]}"""))
            assertTrue(runCatching { api.chat(model, messages) }.exceptionOrNull() is LlmContextOverflowException)
        }
    }

    @Test fun `HTTP 429 formatting does not turn temporary throttling into exhausted quota`() {
        val temporary = ProviderClient.rateLimitException(429, """{"error":{"code":"rate_limit_exceeded","message":"Too many requests"}}""", "3")
        assertFalse(temporary.quotaExhausted)
        assertEquals(3L, temporary.retryAfterSeconds)
        assertTrue(ProviderClient.rateLimitException(429, """{"error":{"code":"insufficient_quota","message":"Update your plan"}}""", null).quotaExhausted)
    }

    @Test fun `error message text cannot impersonate a transient error type`() {
        val error = providerStreamError("""{"error":{"code":"invalid_request_error","message":"Invalid field named server_error"}}""")
        assertTrue(error is IllegalStateException)
        assertFalse(error is TransientHttpException)
    }
}

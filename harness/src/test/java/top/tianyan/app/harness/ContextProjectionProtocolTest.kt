package top.tianyan.app.harness

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.session.ApiMessageProjector

/** Validate the final HTTP body, including providers which may replay native response artifacts. */
@RunWith(Parameterized::class)
class ContextProjectionProtocolTest(private val protocol: LlmApi, private val mode: ToolCallMode) {
    @Test fun `historical body is shortened in the actual provider request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val large = "old-body".repeat(10_000)
            val source = listOf<HarnessMessage>(
                UserMessage("u", 0, "write"),
                ToolCall("write", 1, HarnessTool.WRITE, buildJsonObject { put("path", "a.kt"); put("content", large) }),
                ToolResult("written", 2, "write", true, "ok"),
            ) + (1..4).flatMap { listOf(
                ToolCall("read$it", 3, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }),
                ToolResult("result$it", 4, "read$it", true, "ok"),
            ) } + UserMessage("next", 5, "continue")
            val messages = ApiMessageProjector.project(ContextWindowPolicy.projectForUsage(source, true), mode, false)
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), null,
                protocol = if (protocol == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
                responseApiEnabled = protocol == LlmApi.OPENAI_RESPONSES, toolCallMode = mode)
            server.enqueue(MockResponse().setBody(reply()))
            val result = ProviderTransport(OkHttpClient(), Json { ignoreUnknownKeys = true })
                .chatStream(model, messages, onDelta = {})
            assertEquals("done", result.content)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = request.body.readUtf8()
            assertFalse(body.contains(large))
            assertTrue(body.contains("history_read"))
            assertTrue(body.contains("a.kt"))
            val parsed = Json.parseToJsonElement(body).jsonObject
            assertNotNull(if (protocol == LlmApi.OPENAI_RESPONSES) parsed["input"] else parsed["messages"])
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `serialized protocol limit blocks escaped payload before any HTTP request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val model = ModelConfig("test", "custom", "test", server.url("/v1").toString(), null,
                protocol = if (protocol == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
                responseApiEnabled = protocol == LlmApi.OPENAI_RESPONSES, toolCallMode = mode)
            // JSON control escaping expands one character into six wire bytes.
            val messages = listOf(ApiMessage("user", "payload:" + "\u0001".repeat(750_000)))
            assertTrue(ContextWindowPolicy.estimateApiPayloadBytes(messages) < ContextWindowPolicy.REQUEST_BODY_HARD_LIMIT_BYTES)
            val transport = ProviderTransport(OkHttpClient(), Json { ignoreUnknownKeys = true })
            repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("unexpected network request")) }
            val ordinary = runCatching { transport.chat(model, messages) }.exceptionOrNull()
            val stream = runCatching { transport.chatStream(model, messages, onDelta = {}) }.exceptionOrNull()
            assertTrue("$ordinary", ordinary is LlmContextOverflowException)
            assertTrue("$stream", stream is LlmContextOverflowException)
            assertEquals(0, server.requestCount)
        }
    }

    private fun reply() = when (protocol) {
        LlmApi.OPENAI_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        LlmApi.OPENAI_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"done\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        LlmApi.ANTHROPIC_MESSAGES -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}")
        fun protocols(): List<Array<Any>> = LlmApi.entries.flatMap { protocol ->
            listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT).map { mode -> arrayOf<Any>(protocol, mode) }
        }
    }
}

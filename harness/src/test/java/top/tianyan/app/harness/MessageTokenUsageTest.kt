package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTokenUsageTest {
    @Test
    fun `anthropic response preserves raw ledger usage while message input includes all caches`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{
                "content":[{"type":"text","text":"ok"}],
                "usage":{"input_tokens":1000,"output_tokens":500,
                    "cache_read_input_tokens":9000,"cache_creation_input_tokens":2000}
            }"""))
            val model = ModelConfig(name = "test", provider = "test", model = "test",
                baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = null, protocol = ApiProtocol.ANTHROPIC)
            val result = AnthropicApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
                .chat(model, listOf(ApiMessage(role = "user", content = "hi")))
            assertEquals(1_000L, result.usage.inputTokens)
            assertEquals(9_000L, result.usage.cacheReadTokens)
            assertEquals(2_000L, result.usage.cacheWriteTokens)
            assertEquals(12_000, result.usage.messagePromptTokens(model.protocol))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anthropic includes cache reads and writes in persisted message input`() {
        val usage = ChatUsage(inputTokens = 1_000, outputTokens = 500, cacheReadTokens = 9_000, cacheWriteTokens = 2_000)
        val message = AssistantText(
            id = "reply", createdAt = 0, text = "ok",
            promptTokens = usage.messagePromptTokens(ApiProtocol.ANTHROPIC),
            completionTokens = usage.outputTokens.toInt(), cachedTokens = usage.cacheReadTokens.toInt(),
        )
        val restored = Json.decodeFromString<AssistantText>(Json.encodeToString(message))
        val prompt = requireNotNull(restored.promptTokens)
        val cached = requireNotNull(restored.cachedTokens)
        assertEquals(12_000, prompt)
        assertEquals(12_500, prompt + requireNotNull(restored.completionTokens))
        assertEquals(75, cached * 100 / prompt)
        assertEquals(3_000, prompt - cached)
        // 协议原始输入留给账本和原有预算策略，不因展示投影发生改变。
        assertEquals(1_000L, usage.inputTokens)
    }

    @Test
    fun `openai including responses does not count cache tokens twice`() {
        val usage = ChatUsage(inputTokens = 10_000, cacheReadTokens = 9_000, cacheWriteTokens = 1_000)
        assertEquals(10_000, usage.messagePromptTokens(ApiProtocol.OPENAI))
    }

    @Test
    fun `fully cached anthropic input is retained without new input`() {
        assertEquals(9_000, ChatUsage(cacheReadTokens = 9_000).messagePromptTokens(ApiProtocol.ANTHROPIC))
        assertEquals(2_000, ChatUsage(cacheWriteTokens = 2_000).messagePromptTokens(ApiProtocol.ANTHROPIC))
        assertNull(ChatUsage().messagePromptTokens(ApiProtocol.ANTHROPIC))
    }

    @Test
    fun `invalid and oversized counts cannot wrap into negative message input`() {
        assertNull(ChatUsage(inputTokens = -1).messagePromptTokens(ApiProtocol.OPENAI))
        assertEquals(Int.MAX_VALUE, ChatUsage(inputTokens = Long.MAX_VALUE, cacheReadTokens = Long.MAX_VALUE)
            .messagePromptTokens(ApiProtocol.ANTHROPIC))
    }
}

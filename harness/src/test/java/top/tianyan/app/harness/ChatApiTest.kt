package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ChatApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ChatApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(): ModelConfig = ModelConfig(
        name = "测试",
        provider = "OpenAI",
        model = "gpt-4o-mini",
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        apiKey = "sk-test-key",
    )

    @Test
    fun `parses plain text response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"你好，我已经看完了"}}]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals("你好，我已经看完了", result.content)
        assertFalse(result.hasToolCalls)
    }

    @Test
    fun `parses tool calls response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"call_1","type":"function","function":{"name":"base","arguments":"{\"command\":\"uname -m\"}"}}
                ]}}]}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"uname -m"}""", call.argumentsJson)
    }

    @Test
    fun `assistant tool call keeps content field for strict providers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "user", content = "hi"),
                ApiMessage(
                    role = "assistant",
                    content = null,
                    tool_calls = listOf(
                        ApiToolCall(
                            id = "call_1",
                            function = ApiFunctionCall(name = "read", arguments = """{"path":"a.kt"}"""),
                        ),
                    ),
                ),
                ApiMessage(role = "tool", content = "ok", tool_call_id = "call_1"),
            ),
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = body.getValue("messages").jsonArray[1].jsonObject
        assertTrue("content must be present even when there is no assistant text", assistant.containsKey("content"))
        assertEquals("", assistant.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `request sends bearer auth and tools schema`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[]}"""),
        )
        api.chat(model(), emptyList())
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.path == "/v1/chat/completions")
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"name\":\"read\""))
    }

    @Test
    fun `http error throws with code`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid key"}"""))
        val thrown = runCatching { runBlocking { api.chat(model(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("401"))
    }

    @Test
    fun `429 exposes retry after and quota exhaustion`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"error":{"message":"Workspace allocated quota exceeded, please increase your quota limit."}}"""),
        )
        val thrown = runCatching { runBlocking { api.chat(model(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is LlmRateLimitException)
        val rateLimit = thrown as LlmRateLimitException
        assertEquals(7L, rateLimit.retryAfterSeconds)
        assertTrue(rateLimit.quotaExhausted)
    }

    @Test
    fun `openai official maps reasoning effort to reasoning_effort`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = ReasoningEffort.MEDIUM),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `openai official disables reasoning with effort none`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(model().copy(reasoningMode = ReasoningMode.DISABLED), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"none\""))
    }

    @Test
    fun `gemini maps reasoning to thinking_config thinkingBudget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "Google Gemini",
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.LOW,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking_config\":{\"thinkingBudget\":1024}"))
    }

    @Test
    fun `gemini disables reasoning with zero budget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "Google Gemini",
                reasoningMode = ReasoningMode.DISABLED,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking_config\":{\"thinkingBudget\":0}"))
    }

    @Test
    fun `unknown provider does not inject reasoning fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "OpenAI 兼容接口",
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.HIGH,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("reasoning_effort"))
        assertFalse(body.contains("thinking_config"))
        assertFalse(body.contains("\"reasoning\":{"))
    }

    @Test
    fun `auto mode does not inject reasoning fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(model(), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("reasoning_effort"))
        assertFalse(body.contains("thinking_config"))
    }

    @Test
    fun `streams content deltas and accumulates tool calls`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"你"}}]}
            data: {"choices":[{"delta":{"content":"好。"}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"base","arguments":"{\"command\":\""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"abc\"}"}}]}}]}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val deltas = mutableListOf<String>()
        val result = api.chatStream(model(), emptyList()) { deltas += it }
        assertEquals(listOf("你", "好。"), deltas)
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("base", call.name)
        assertEquals("""{"command":"abc"}""", call.argumentsJson)
    }

    @Test
    fun `accumulates parallel tool calls across chunks without explicit index`() = runBlocking {
        // 网关不分发 index：每个调用的首个分片带非空 id，延续分片不带 id。
        // 新调用判据必须是 id 变化——按 chunk 内迭代序号 fallback（恒为 0）会把
        // 跨 chunk 的并行调用合并成一个畸形调用。
        val body = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"c1","function":{"name":"base","arguments":"{\"command\":\"ls\"}"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"id":"c2","function":{"name":"read","arguments":"{\"path\":\"a.md\"}"}}]}}]}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals(2, result.toolCalls.size)
        val first = result.toolCalls.first { it.id == "c1" }
        val second = result.toolCalls.first { it.id == "c2" }
        assertEquals("base", first.name)
        assertEquals("""{"command":"ls"}""", first.argumentsJson)
        assertEquals("read", second.name)
        assertEquals("""{"path":"a.md"}""", second.argumentsJson)
    }

    @Test
    fun `continuation chunk without id or index extends the last active call`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"c1","function":{"name":"base","arguments":"{\"command\":\""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"ls\"}"}}]}}]}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        val call = result.toolCalls.single()
        assertEquals("c1", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"ls"}""", call.argumentsJson)
    }

    @Test
    fun `parses usage from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}],
                   "usage":{"prompt_tokens":120,"completion_tokens":45,
                     "prompt_tokens_details":{"cached_tokens":80},
                     "completion_tokens_details":{"reasoning_tokens":12}}}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals(120L, result.usage.inputTokens)
        assertEquals(45L, result.usage.outputTokens)
        assertEquals(12L, result.usage.reasoningTokens)
        assertEquals(80L, result.usage.cacheReadTokens)
        assertTrue(result.usage.hasData)
    }

    @Test
    fun `parses deepseek style cache fields from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}],
                   "usage":{"prompt_tokens":100,"completion_tokens":20,
                     "prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40}}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals(60L, result.usage.cacheReadTokens)
        assertEquals(40L, result.usage.cacheWriteTokens)
    }

    @Test
    fun `parses usage from final stream chunk and requests include_usage`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"你好"}}]}
            data: {"choices":[{"delta":{"content":"世界"}}]}
            data: {"choices":[],"usage":{"prompt_tokens":31,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":16},"completion_tokens_details":{"reasoning_tokens":3}}}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals(31L, result.usage.inputTokens)
        assertEquals(7L, result.usage.outputTokens)
        assertEquals(3L, result.usage.reasoningTokens)
        assertEquals(16L, result.usage.cacheReadTokens)
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun `retries without stream_options when provider rejects it`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Extra inputs are not permitted: stream_options"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":"ok"}}]}
                data: [DONE]
                """.trimIndent(),
            ),
        )
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals("ok", result.content)
        server.takeRequest() // 第一次带 stream_options 的请求
        val retry = server.takeRequest()
        assertFalse(retry.body.readUtf8().contains("stream_options"))
    }

    @Test
    fun `propagates other stream errors without retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server exploded"}"""))
        var thrown: Throwable? = null
        try {
            api.chatStream(model(), emptyList()) { }
        } catch (t: Throwable) {
            thrown = t
        }
        // 5xx 抛 TransientHttpException（IOException 子类，交给上游重试策略按退避处理）；
        // 本层不吞错也不自行重试——requestCount==1 验证这一点。
        assertTrue(thrown is TransientHttpException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty stream fails loudly and carries the raw response head`() = runBlocking {
        // 上游 200 但只发了 [DONE]：此前会被判定为"模型答完了"，前台表现为没有任何回复。
        server.enqueue(MockResponse().setBody("data: [DONE]"))
        val thrown = runCatching { api.chatStream(model(), emptyList()) { } }.exceptionOrNull()
        assertTrue(thrown is LlmEmptyResponseException)
        val msg = thrown!!.message.orEmpty()
        assertTrue("Expected empty-response guidance, got: $msg", msg.contains("空响应"))
        assertTrue("Expected raw response head for diagnosis, got: $msg", msg.contains("[DONE]"))
    }

    @Test
    fun `non sse json body is recovered instead of silently dropped`() = runBlocking {
        // 部分 OpenAI 兼容网关忽略 stream:true，直接返回整段 JSON 补全：
        // 这种 body 没有 data: 行，此前被逐行丢弃，最终表现为空回复。
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"兜底解析回来的回复"}}],
                   "usage":{"prompt_tokens":12,"completion_tokens":8}}""",
            ),
        )
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals("兜底解析回来的回复", result.content)
        assertEquals(12L, result.usage.inputTokens)
        assertEquals(8L, result.usage.outputTokens)
    }

    @Test
    fun `html response is reported with a friendly message instead of a serialization stack`() = runBlocking {
        // 模拟代理/CDN 返回 HTML 登录页 (与 runtime.log 里 Model discovery 那条同源)。
        server.enqueue(
            MockResponse()
                .setBody("<!doctype html><html lang=\"en\"><head><title>Sign in</title></head><body>Auth required</body></html>"),
        )
        val thrown = runCatching { api.chat(model(), listOf(ApiMessage(role = "user", content = "hi"))) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        val msg = thrown!!.message.orEmpty()
        assertTrue("Expected HTML guidance in message, got: $msg", msg.contains("网页") || msg.contains("非 JSON"))
        assertFalse("Raw HTML must not leak into the message: $msg", msg.contains("<html"))
    }

    @Test
    fun `bom prefixed html is also detected`() = runBlocking {
        // 部分代理会把 UTF-8 BOM 加在 HTML 前缀，让 kotlinx.serialization 从 offset 1 起算。
        // 这正是 runtime.log 里那条 "Expected EOF ... but had h" 的根因。
        server.enqueue(
            MockResponse()
                .setBody("\uFEFF<!doctype html><html lang=\"en\"></html>"),
        )
        val thrown = runCatching { api.chat(model(), listOf(ApiMessage(role = "user", content = "hi"))) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(thrown!!.message!!.contains("<html"))
    }

    @Test
    fun `looksLikeJsonResponse helper handles bom and whitespace`() {
        assertTrue(ProviderClient.looksLikeJsonResponse("""{"choices":[]}"""))
        assertTrue(ProviderClient.looksLikeJsonResponse("   \n  []"))
        assertTrue(ProviderClient.looksLikeJsonResponse("\uFEFF{\"a\":1}"))
        assertFalse(ProviderClient.looksLikeJsonResponse("<!doctype html><html></html>"))
        assertFalse(ProviderClient.looksLikeJsonResponse("\uFEFF<html></html>"))
        assertFalse(ProviderClient.looksLikeJsonResponse("plain text error page"))
        assertFalse(ProviderClient.looksLikeJsonResponse(""))
    }
}

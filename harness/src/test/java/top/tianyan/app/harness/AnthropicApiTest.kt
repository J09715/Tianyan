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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AnthropicApi
    private val recordedRequests = mutableListOf<okhttp3.Request>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AnthropicApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
        recordedRequests.clear()
    }

    private fun model(): ModelConfig = ModelConfig(
        name = "测试",
        provider = "Anthropic Claude",
        model = "claude-sonnet-4.6",
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        apiKey = "sk-ant-test",
        protocol = ApiProtocol.ANTHROPIC,
    )

    @Test
    fun `plain text response parses content`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"搞定，已完成修改"}]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals("搞定，已完成修改", result.content)
        assertFalse(result.hasToolCalls)
    }

    @Test
    fun `tool use response parses call id name and arguments`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[
                    {"type":"text","text":"我来执行命令"},
                    {"type":"tool_use","id":"toolu_1","name":"base","input":{"command":"uname -m"}}
                ]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "run")))
        assertEquals("我来执行命令", result.content)
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("base", call.name)
        assertTrue(call.argumentsJson.contains("uname -m"))
    }

    @Test
    fun `system prompt moves to top-level and auth headers are anthropic style`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "system", content = "你是天衍 Agent"),
                ApiMessage(role = "user", content = "hi"),
            ),
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""system":"你是天衍 Agent""""))
        assertFalse(body.contains(""""role":"system""""))
    }

    @Test
    fun `tool results merge into single user message with tool_result blocks`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "user", content = "run two commands"),
                ApiMessage(
                    role = "assistant",
                    content = null,
                    tool_calls = listOf(
                        ApiToolCall(id = "toolu_1", function = ApiFunctionCall(name = "base", arguments = """{"command":"ls"}""")),
                        ApiToolCall(id = "toolu_2", function = ApiFunctionCall(name = "base", arguments = """{"command":"pwd"}""")),
                    ),
                ),
                ApiMessage(role = "tool", content = "file-a\nfile-b", tool_call_id = "toolu_1"),
                ApiMessage(role = "tool", content = "/root", tool_call_id = "toolu_2"),
            ),
        )
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // 两个 tool_result 必须在同一条 user 消息内
        val toolResultCount = Regex(""""type":"tool_result"""").findAll(body).count()
        assertEquals(2, toolResultCount)
        // assistant 的 tool_calls 翻译成 tool_use 块
        val toolUseCount = Regex(""""type":"tool_use"""").findAll(body).count()
        assertEquals(2, toolUseCount)
        assertTrue(body.contains(""""tool_use_id":"toolu_1""""))
        assertTrue(body.contains(""""tool_use_id":"toolu_2""""))
        assertTrue(body.contains("file-a"))
    }

    @Test
    fun `assistant text is encoded as a typed content block`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "user", content = "first"),
                ApiMessage(role = "assistant", content = "answer"),
                ApiMessage(role = "user", content = "next"),
            ),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = body.getValue("messages").jsonArray[1].jsonObject
        val textBlock = assistant.getValue("content").jsonArray.single().jsonObject
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        assertEquals("text", textBlock.getValue("type").jsonPrimitive.content)
        assertEquals("answer", textBlock.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `non-positive max tokens is normalized and thinking stays below output budget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model().copy(maxTokens = 0, reasoningMode = ReasoningMode.ENABLED), listOf(ApiMessage(role = "user", content = "hi")))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body.getValue("max_tokens").jsonPrimitive.content.toInt() > 0)
        assertTrue(body.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.content.toInt() < body.getValue("max_tokens").jsonPrimitive.content.toInt())
    }

    @Test
    fun `max tokens is required and defaults when unset`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""max_tokens":8192"""))
    }

    @Test
    fun `inference parameters are sent when configured`() = runBlocking {
        val configured = model().copy(temperature = 0.7f, maxTokens = 1024, topP = 0.9f)
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(configured, listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""temperature":0.7"""))
        assertTrue(body.contains(""""max_tokens":1024"""))
        assertTrue(body.contains(""""top_p":0.9"""))
    }

    @Test
    fun `thinking enabled sends budget tokens and drops temperature`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.MEDIUM,
                temperature = 0.7f,
                topP = 0.9f,
            ),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}"))
        assertFalse(body.contains("\"temperature\""))
        assertFalse(body.contains("\"top_p\""))
    }

    @Test
    fun `thinking enabled without effort uses default budget and clamps to max tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, maxTokens = 2500),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"budget_tokens\":2048"))
    }

    @Test
    fun `high thinking budget remains strictly below default max tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = ReasoningEffort.HIGH),
            listOf(ApiMessage(role = "user", content = "hi")),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(8_192, body.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals(
            8_191,
            body.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `enabled thinking is omitted when output budget cannot satisfy minimum`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, maxTokens = 1_024),
            listOf(ApiMessage(role = "user", content = "hi")),
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("\"thinking\""))
    }

    @Test
    fun `thinking disabled sends type disabled and keeps temperature`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.DISABLED, temperature = 0.3f),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"temperature\":0.3"))
    }

    @Test
    fun `auto mode does not inject thinking`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("\"thinking\""))
    }

    @Test
    fun `stream events assemble text reasoning and tool arguments`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start","message":{}}""" + "\n\n")
            append("event: content_block_start\n")
            append("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"正在分析"}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"文件"}}""" + "\n\n")
            append("event: content_block_start\n")
            append("""data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"base","input":{}}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":"}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"ls -la\"}"}}""" + "\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        var text = ""
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = {}, onDelta = { text += it })
        assertEquals("正在分析文件", result.content)
        assertEquals("正在分析文件", text)
        val call = result.toolCalls.single()
        assertEquals("toolu_9", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"ls -la"}""", call.argumentsJson)
        assertNull(result.reasoningContent)
    }

    @Test
    fun `thinking delta maps to reasoning content`() = runBlocking {
        val sse = buildString {
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先看看目录"}}""" + "\n\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"结论"}}""" + "\n\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        var reasoning = ""
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = { reasoning += it }, onDelta = {})
        assertEquals("结论", result.content)
        assertEquals("先看看目录", result.reasoningContent)
        assertEquals("先看看目录", reasoning)
    }

    @Test
    fun `http error surfaces anthropic error message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
            ),
        )
        val error = runCatching {
            api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun `parses usage from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],
                   "usage":{"input_tokens":210,"output_tokens":88,
                     "cache_read_input_tokens":150,"cache_creation_input_tokens":60}}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals(210L, result.usage.inputTokens)
        assertEquals(88L, result.usage.outputTokens)
        assertEquals(150L, result.usage.cacheReadTokens)
        assertEquals(60L, result.usage.cacheWriteTokens)
    }

    @Test
    fun `parses usage from message_start and message_delta in stream`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append(
                """data: {"type":"message_start","message":{"usage":{"input_tokens":512,"output_tokens":2,"cache_read_input_tokens":300,"cache_creation_input_tokens":50}}}""" + "\n\n",
            )
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"结论"}}""" + "\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{},"usage":{"output_tokens":96}}""" + "\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = {}, onDelta = {})
        assertEquals(512L, result.usage.inputTokens)
        assertEquals(96L, result.usage.outputTokens)
        assertEquals(300L, result.usage.cacheReadTokens)
        assertEquals(50L, result.usage.cacheWriteTokens)
    }

    @Test
    fun `html response from claude endpoint is reported cleanly`() = runBlocking {
        // Anthropic 路径同样要避免把 HTML 误喂给 JSON 解析器。
        server.enqueue(
            MockResponse()
                .setBody("\uFEFF<!doctype html><html lang=\"en\"><head><title>Sign in</title></head></html>"),
        )
        val thrown = runCatching { api.chat(model(), listOf(ApiMessage(role = "user", content = "hi"))) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse("Raw HTML must not leak into the message", thrown!!.message!!.contains("<html"))
    }

    @Test
    fun `prompt caching injects system tools and penultimate user breakpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(promptCachingEnabled = true),
            listOf(
                ApiMessage(role = "system", content = "你是天衍 Agent"),
                ApiMessage(role = "user", content = "first"),
                ApiMessage(role = "assistant", content = "answer"),
                ApiMessage(role = "user", content = "next"),
            ),
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        // 断点 1：system 转为结构化 block 并带 cache_control
        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("text", system.getValue("type").jsonPrimitive.content)
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        // 断点 2：tools 数组最后一个工具定义
        val lastTool = body.getValue("tools").jsonArray.last().jsonObject
        assertEquals("ephemeral", lastTool.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        // 断点 3：倒数第二条真实用户消息（index 0）；最新 user（index 2）不注入
        val messages = body.getValue("messages").jsonArray
        val firstUserText = messages[0].jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("ephemeral", firstUserText.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        val lastUserText = messages[2].jsonObject.getValue("content").jsonArray.single().jsonObject
        assertFalse(lastUserText.containsKey("cache_control"))
        // 总数恰好 3，不超过 Anthropic 的 4 断点上限
        val breakpoints = Regex(""""cache_control":\{"type":"ephemeral"\}""").findAll(body.toString()).count()
        assertEquals(3, breakpoints)
    }

    @Test
    fun `prompt caching is off by default and keeps plain system string`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "system", content = "sys"),
                ApiMessage(role = "user", content = "first"),
                ApiMessage(role = "assistant", content = "a"),
                ApiMessage(role = "user", content = "next"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("cache_control"))
        assertTrue(body.contains(""""system":"sys""""))
    }


    @Test
    fun `pause turn resumes by replaying assistant message without synthetic user turn`() = runBlocking {
        val first = buildString {
            append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"前半段\"}}\n\n")
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"},\"usage\":{\"output_tokens\":5}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        val second = buildString {
            append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20,\"output_tokens\":1}}}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"后半段\"}}\n\n")
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setBody(first).setHeader("Content-Type", "text/event-stream"))
        server.enqueue(MockResponse().setBody(second).setHeader("Content-Type", "text/event-stream"))

        var streamed = ""
        val result = api.chatStream(
            model(),
            listOf(ApiMessage(role = "user", content = "hi")),
            onReasoning = {},
            onDelta = { streamed += it },
        )

        assertEquals("前半段后半段", result.content)
        assertEquals("前半段后半段", streamed)
        assertEquals(12L, result.usage.outputTokens)
        assertEquals(30L, result.usage.inputTokens)
        server.takeRequest()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(secondBody.contains("前半段"))
        assertTrue(secondBody.contains("\"role\":\"assistant\""))
        assertFalse(secondBody.contains("Continue"))
    }


    @Test
    fun `vision bridge image user message merges into preceding tool result user message`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "user", content = "看看这张图"),
                ApiMessage(
                    role = "assistant",
                    content = null,
                    tool_calls = listOf(
                        ApiToolCall(
                            id = "t1",
                            function = ApiFunctionCall(name = "read", arguments = """{"path":"a.png"}"""),
                        ),
                    ),
                ),
                ApiMessage(role = "tool", content = "图片已读取", tool_call_id = "t1"),
                ApiMessage(
                    role = "user",
                    content = "[read 工具读取的图片]",
                    imageUrls = listOf("data:image/png;base64,AAAA"),
                ),
            ),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        messages.zipWithNext().forEach { (first, second) ->
            val firstRole = first.getValue("role").jsonPrimitive.content
            val secondRole = second.getValue("role").jsonPrimitive.content
            assertFalse("相邻 user 消息必须合并", firstRole == "user" && secondRole == "user")
        }
        val userWithToolResult = messages.first { message ->
            message.getValue("role").jsonPrimitive.content == "user" &&
                message.getValue("content").jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result"
                }
        }
        val contentTypes = userWithToolResult.getValue("content").jsonArray.map {
            it.jsonObject["type"]?.jsonPrimitive?.content
        }
        assertTrue(contentTypes.contains("tool_result"))
        assertTrue(contentTypes.contains("image"))
    }


    @Test
    fun `prompt caching 1h ttl adds ttl field and beta header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(promptCachingEnabled = true, promptCacheTtl1h = true),
            listOf(
                ApiMessage(role = "system", content = "sys"),
                ApiMessage(role = "user", content = "hi"),
            ),
        )
        val recorded = server.takeRequest()
        assertEquals("extended-cache-ttl-2025-04-11", recorded.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val cacheControl = body.getValue("system").jsonArray.single().jsonObject
            .getValue("cache_control").jsonObject
        assertEquals("ephemeral", cacheControl.getValue("type").jsonPrimitive.content)
        assertEquals("1h", cacheControl.getValue("ttl").jsonPrimitive.content)
    }

    @Test
    fun `prompt caching without 1h ttl omits ttl and beta header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model().copy(promptCachingEnabled = true), listOf(ApiMessage(role = "user", content = "hi")))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("anthropic-beta"))
        assertFalse(recorded.body.readUtf8().contains("\"ttl\""))
    }
}

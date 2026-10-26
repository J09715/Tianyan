package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.session.ApiMessageProjector

class ReasoningHistoryReplayTest {
    private val history = listOf(
        UserMessage("u1", 1L, "检查文件"),
        AssistantText("a1", 2L, "先检查", reasoning = " 先分析\n"),
        ToolCall("t1", 3L, HarnessTool.READ, buildJsonObject {}, reasoning = " 先分析\n"),
        ToolResult("r1", 4L, "t1", success = true, output = "内容"),
        AssistantText("a2", 5L, "已检查", reasoning = "检查完毕\n"),
        UserMessage("u2", 6L, "继续"),
        AssistantText("a3", 7L, "下一步", reasoning = "继续分析"),
    )

    @Test fun `native replay preserves all assistant reasoning across user turns on the wire`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
            ))
            val model = ModelConfig("offline", "deepseek", "offline-model", server.url("/v1").toString(), null)
            val messages = ApiMessageProjector.project(history, ToolCallMode.NATIVE, false)
            ProviderTransport(OkHttpClient(), Json { ignoreUnknownKeys = true }).chat(model, messages)

            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(body.getValue("tools").jsonArray.isNotEmpty())
            val assistants = body.getValue("messages").jsonArray.map { it.jsonObject }
                .filter { it.getValue("role").jsonPrimitive.content == "assistant" }
            assertEquals(listOf(" 先分析\n", "检查完毕\n", "继续分析"),
                assistants.map { it.getValue("reasoning_content").jsonPrimitive.content })
            assertEquals("t1", assistants.first().getValue("tool_calls").jsonArray.single()
                .jsonObject.getValue("id").jsonPrimitive.content)
            assertFalse(assistants.last().containsKey("tool_calls"))
        }
    }

    @Test fun `plain chat and text tools still omit historical reasoning`() {
        for (mode in listOf(ToolCallMode.DISABLED, ToolCallMode.JSON_TEXT)) {
            val messages = ApiMessageProjector.project(history.takeLast(3), mode, false)
            assertTrue(messages.filter { it.role == "assistant" }.all { it.reasoning_content == null })
        }
    }

    @Test fun `native replay does not invent reasoning for synthetic assistant messages`() {
        val messages = ApiMessageProjector.project(
            listOf(UserMessage("u1", 1L, "继续"), AssistantText("a1", 2L, "已停止")),
            ToolCallMode.NATIVE, false,
        )
        assertNull(messages.last().reasoning_content)
    }
}

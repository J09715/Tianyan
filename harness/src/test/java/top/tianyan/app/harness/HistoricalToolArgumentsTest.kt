package top.tianyan.app.harness

import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.session.ApiMessageProjector

class HistoricalToolArgumentsTest {
    private val content = "start" + "x".repeat(50_000) + "end"
    private fun call(id: String = "old", tool: HarnessTool = HarnessTool.WRITE) = ToolCall(id, 1, tool,
        buildJsonObject {
            put("path", "a.kt")
            if (tool == HarnessTool.EDIT) { put("oldText", content); put("newText", content) }
            else put("content", content)
        })
    private fun recent() = (1..4).flatMap { listOf(
        ToolCall("recent$it", 2, HarnessTool.READ, buildJsonObject { put("path", "f$it") }),
        ToolResult("r$it", 3, "recent$it", true, "ok"),
    ) }
    private fun history(call: ToolCall = call(), result: ToolResult = ToolResult("result", 2, call.id, true, "ok")) =
        listOf(UserMessage("u", 0, "write"), call, result) + recent() + UserMessage("next", 9, "continue")

    @Test fun `only known large string bodies are shortened without mutating stored arguments`() {
        for (tool in listOf(HarnessTool.WRITE, HarnessTool.EDIT)) {
            val original = history(call(tool = tool))
            val projected = HistoricalToolArguments.project(original)
            val before = original[1] as ToolCall
            val after = projected[1] as ToolCall
            assertEquals(before.id, after.id)
            assertEquals("a.kt", after.args["path"]!!.jsonPrimitive.content)
            val fields = if (tool == HarnessTool.WRITE) listOf("content") else listOf("oldText", "newText")
            for (field in fields) {
                assertEquals(content, before.args[field]!!.jsonPrimitive.content)
                val text = after.args[field]!!.jsonPrimitive.content
                assertTrue(text.length < HistoricalToolArguments.LARGE_VALUE_CHARS)
                assertTrue(text.contains("history_read(message_id=\"old\")"))
                assertTrue(text.startsWith("start"))
                assertTrue(text.endsWith("end"))
            }
            assertSame(projected, HistoricalToolArguments.project(projected))
        }
    }

    @Test fun `current recent pending failed deferred and unanswered calls remain intact`() {
        val call = call()
        for (result in listOf(
            ToolResult("r", 2, call.id, false, "failed"),
            ToolResult("r", 2, call.id, true, "pending", awaitingApproval = true),
            ToolResult("r", 2, call.id, true, "deferred", approvalDeferred = true),
        )) {
            val source = history(call, result)
            assertSame(source, HistoricalToolArguments.project(source))
        }
        val current = history().dropLast(1)
        assertSame(current, HistoricalToolArguments.project(current))
        val unanswered = history().filterNot { it is ToolResult && it.toolCallId == "old" }
        assertSame(unanswered, HistoricalToolArguments.project(unanswered))
        val recent = listOf(UserMessage("u", 0, "write"), call, ToolResult("r", 2, "old", true, "ok"), UserMessage("next", 4, "continue"))
        assertSame(recent, HistoricalToolArguments.project(recent))
        val mcp = history(call(tool = HarnessTool.MCP))
        assertSame(mcp, HistoricalToolArguments.project(mcp))
    }

    @Test fun `settled approval pairs reduce while an undecided or failed tail stays protected`() {
        val call = call()
        val approved = listOf(
            UserMessage("u", 0, "write"),
            call,
            ToolResult("pending", 2, call.id, false, "等待用户批准", awaitingApproval = true),
            ToolResult("result", 3, call.id, true, "ok"),
        ) + recent() + UserMessage("next", 9, "continue")
        val projected = HistoricalToolArguments.project(approved)
        val projectedCall = projected.first { it is ToolCall && it.id == "old" } as ToolCall
        val text = projectedCall.args["content"]!!.jsonPrimitive.content
        assertTrue(text.length < HistoricalToolArguments.LARGE_VALUE_CHARS)
        assertTrue(text.contains("history_read(message_id=\"old\")"))
        assertEquals(content, (approved[1] as ToolCall).args["content"]!!.jsonPrimitive.content)

        val undecided = listOf(
            UserMessage("u", 0, "write"),
            call,
            ToolResult("pending", 2, call.id, false, "等待用户批准", awaitingApproval = true),
        ) + recent() + UserMessage("next", 9, "continue")
        assertSame(undecided, HistoricalToolArguments.project(undecided))

        val failedTail = listOf(
            UserMessage("u", 0, "write"),
            call,
            ToolResult("pending", 2, call.id, false, "等待用户批准", awaitingApproval = true),
            ToolResult("failed", 3, call.id, false, "boom"),
        ) + recent() + UserMessage("next", 9, "continue")
        assertSame(failedTail, HistoricalToolArguments.project(failedTail))
    }

    @Test fun `usage projection and both tool modes use the shortened view with intact tool pairs`() {
        val source = history()
        assertSame(source, ContextWindowPolicy.projectForUsage(source, false))
        val projected = ContextWindowPolicy.projectForUsage(source, true)
        assertTrue(ContextWindowPolicy.estimateHarnessPayloadBytes(projected) < ContextWindowPolicy.estimateHarnessPayloadBytes(source))
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val api = ApiMessageProjector.project(projected, mode, false)
            assertFalse(api.toString().contains(content))
            assertTrue(api.toString().contains("history_read"))
            if (mode == ToolCallMode.NATIVE) {
                assertEquals(5, api.sumOf { it.tool_calls.orEmpty().size })
                assertEquals(5, api.count { it.role == "tool" })
            }
        }
    }

    @Test fun `Responses native replay cannot restore original large arguments after projection`() {
        val model = ModelConfig("test", "custom", "test", "https://example.com/v1", null, responseApiEnabled = true)
        val old = call()
        val output = buildJsonArray {
            add(buildJsonObject { put("type", "function_call"); put("call_id", "native_old")
                put("name", "write"); put("arguments", old.args.toString()); put("status", "completed") })
        }
        val source = history().toMutableList().apply {
            add(1, AssistantText("assistant", 1, "", responsesTurn = ResponsesTurn.capture(model, output)))
        }
        val api = ApiMessageProjector.project(ContextWindowPolicy.projectForUsage(source, true), ToolCallMode.NATIVE, false)
        assertNull(api.first { it.role == "assistant" }.responsesTurn)
        val buffer = Buffer()
        buildResponsesRequest(model, api, false).body!!.writeTo(buffer)
        val serialized = buffer.readUtf8()
        assertFalse(serialized.contains(content))
        assertTrue(serialized.contains("history_read"))
        assertFalse(serialized.contains("native_old"))
    }
}

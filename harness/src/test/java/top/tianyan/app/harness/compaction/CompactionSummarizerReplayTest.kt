package top.tianyan.app.harness.compaction

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/**
 * 压缩摘要 cache-replay 请求形状测试：重放请求必须按
 * [system] + [摘要层] + 被折叠前缀（与主请求同投影口径）+ 末尾压缩指令 的顺序组装，
 * 且用户轮召回后缀与主对话同口径追加——这是命中 provider KV 缓存的前提。
 */
class CompactionSummarizerReplayTest {

    private fun model(contextTokens: Int = 200_000) = ModelConfig(
        name = "n", provider = "p", model = "gpt-x",
        baseUrl = "https://example.com", apiKey = null,
        contextTokens = contextTokens,
    )

    private fun context(
        replayPrefix: List<HarnessMessage>,
        recallBlocks: Map<String, String> = emptyMap(),
    ) = SummaryRequestContext(
        systemPrompt = "SYS-PROMPT",
        summaryLayer = "SUMMARY-LAYER",
        toolCallMode = ToolCallMode.NATIVE,
        visionEnabled = false,
        recallBlocks = recallBlocks,
        replayPrefix = replayPrefix,
    )

    @Test
    fun `replay request replays system and summary layer then prefix then instruction`() {
        val prefix = listOf(
            UserMessage("u1", 1L, "早期问题"),
            ToolCall("t1", 2L, HarnessTool.READ, buildJsonObject { put("path", JsonPrimitive("a.md")) }, rawToolName = "read"),
            ToolResult("r1", 3L, "t1", success = true, output = "内容"),
            UserMessage("u2", 4L, "后续追问"),
        )
        val request = SummaryReplayRequests.buildReplayRequest(
            model(),
            previousSummaries = listOf("旧摘要"),
            requestContext = context(prefix),
        )!!

        assertEquals("system", request[0].role)
        assertEquals("SYS-PROMPT", request[0].content)
        assertEquals("system", request[1].role)
        assertEquals("SUMMARY-LAYER", request[1].content)
        assertEquals("user", request[2].role)
        assertEquals("早期问题", request[2].content)
        assertEquals("assistant", request[3].role)
        assertEquals("read", request[3].tool_calls!!.single().function.name)
        assertEquals("tool", request[4].role)
        assertEquals("t1", request[4].tool_call_id)
        assertEquals("user", request[5].role)
        assertEquals("后续追问", request[5].content)
        // 末尾必须是压缩指令（user 角色），并提示合并既有摘要
        val instruction = request.last()
        assertEquals("user", instruction.role)
        assertTrue(instruction.content!!.contains("压缩"))
        assertTrue(instruction.content!!.contains("此前的压缩摘要"))
    }

    @Test
    fun `replay request attaches persisted recall suffixes to user turns`() {
        val prefix = listOf(UserMessage("u1", 1L, "问题"))
        val request = SummaryReplayRequests.buildReplayRequest(
            model(),
            previousSummaries = emptyList(),
            requestContext = context(prefix, recallBlocks = mapOf("u1" to "<recalled_memory>事实A</recalled_memory>")),
        )!!

        val user = request.first { it.role == "user" && it.content!!.startsWith("问题") }
        assertTrue(user.content!!.endsWith("<recalled_memory>事实A</recalled_memory>"))
    }

    @Test
    fun `replay drops unanswered tool calls like the main projection`() {
        val prefix = listOf(
            UserMessage("u1", 1L, "问题"),
            ToolCall("t-orphan", 2L, HarnessTool.READ, buildJsonObject { }, rawToolName = "read"),
        )
        val request = SummaryReplayRequests.buildReplayRequest(
            model(),
            previousSummaries = emptyList(),
            requestContext = context(prefix),
        )!!

        assertTrue(request.none { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() })
        assertEquals("user", request[2].role)
        assertNull(request[2].tool_calls)
    }

    @Test
    fun `replay is infeasible when prefix exceeds the model window`() {
        val big = (1..400).joinToString("") { "很长的历史内容用于撑爆窗口预算。" }
        val prefix = listOf(UserMessage("u1", 1L, big))
        val request = SummaryReplayRequests.buildReplayRequest(
            model(contextTokens = 500),
            previousSummaries = emptyList(),
            requestContext = context(prefix),
        )
        assertNull(request)
    }

    @Test
    fun `empty replay prefix falls back to null so caller uses serialized path`() {
        val request = SummaryReplayRequests.buildReplayRequest(
            model(),
            previousSummaries = emptyList(),
            requestContext = context(emptyList()),
        )
        assertNull(request)
    }
}

package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRunnerTest {
    private val runner = TurnRunner(ProviderResponseNormalizer(Json { ignoreUnknownKeys = true }))

    @Test
    fun `provider failure stops before publication and effects`() = runBlocking {
        var persisted = false
        var executed = false

        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Failed("offline") },
            persistAssistant = { persisted = true },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> executed = true; true },
        )

        assertEquals(TurnOutcome.Failed("offline"), outcome)
        assertFalse(persisted)
        assertFalse(executed)
    }

    @Test
    fun `malformed textual tool request is published then fails closed`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(
                    ChatResult(content = null, toolCalls = emptyList()),
                    "<gateway_tool_call>read<gateway_argkey>path",
                )
            },
            observeResponse = { events += "observed" },
            persistAssistant = { events += "persisted:${it.displayText}" },
            consumeFollowUps = { events += "follow-up"; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> events += "executed"; true },
        )

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(listOf("observed", "persisted:"), events)
    }

    @Test
    fun `text tool protocol normalizes before limit and execution`() = runBlocking {
        val events = mutableListOf<String>()
        var executedNames = emptyList<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(
                    ChatResult(content = null, toolCalls = emptyList(), reasoningContent = "why"),
                    "准备[[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"a.kt\"}}[[/tool_call]]完成",
                )
            },
            persistAssistant = { normalized ->
                events += "persisted:${normalized.displayText}:${normalized.toolCalls.size}"
            },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> events += "limited"; calls.take(1) },
            executeTools = { calls, result ->
                events += "executed:${result.reasoningContent}"
                executedNames = calls.map { it.name }
                true
            },
        )

        assertEquals(TurnOutcome.Continue(1, toolsHadSuccess = true), outcome)
        assertEquals(listOf("read"), executedNames)
        assertEquals(listOf("persisted:准备完成:1", "limited", "executed:why"), events)
    }

    @Test
    fun `empty provider response fails instead of marking the task complete`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(content = null, toolCalls = emptyList()), "")
            },
            persistAssistant = { events += "persisted" },
            consumeFollowUps = { events += "follow-ups"; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Failed("模型返回了空响应；本轮未收到可展示的答复或工具调用"), outcome)
        assertEquals(listOf("persisted"), events)
    }

    /**
     * 只输出思考内容的回复必须算成功，而不是「空响应」。
     *
     * 这条测试此前断言的是相反的行为（要求 Failed），把一个真实缺陷锁死成了「预期」：
     * 推理模型（DeepSeek-R1 类）有时只产出 reasoningContent 而不给正文，
     * 界面上用户能看到整段「推理思考过程」，却被告知「模型返回了空响应，执行失败」。
     * 用户实测的报错就是这个。
     */
    @Test
    fun `reasoning-only response completes instead of failing`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(content = null, toolCalls = emptyList(), reasoningContent = "thinking"), "")
            },
            persistAssistant = { events += "persisted" },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
        assertEquals("思考内容也要落库，否则界面连思考过程都看不到", listOf("persisted"), events)
    }

    /** 真·空响应（无正文、无思考、无工具调用）仍然必须失败关闭。 */
    @Test
    fun `truly empty response still fails closed`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(content = null, toolCalls = emptyList()), "")
            },
            persistAssistant = {},
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Failed("模型返回了空响应；本轮未收到可展示的答复或工具调用"), outcome)
    }

    /** 空字符串的 reasoning 不算内容，不能拿它绕过失败关闭。 */
    @Test
    fun `blank reasoning is not treated as content`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(content = null, toolCalls = emptyList(), reasoningContent = "   "), "")
            },
            persistAssistant = {},
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Failed("模型返回了空响应；本轮未收到可展示的答复或工具调用"), outcome)
    }

    @Test
    fun `plain answer completes only after durable publication`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult("done", emptyList()), "done")
            },
            persistAssistant = { events += "persisted" },
            consumeFollowUps = { events += "follow-ups"; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
        assertEquals(listOf("persisted", "follow-ups"), events)
    }

    @Test
    fun `follow up advances to another turn without tool execution`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult("first", emptyList()), "first")
            },
            persistAssistant = {},
            consumeFollowUps = { 2 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Continue(0, toolsHadSuccess = true, followUpCount = 2), outcome)
    }

    @Test
    fun `pure chat does not interpret textual tool markers`() = runBlocking {
        val marker = "[[tool_call]]{\"name\":\"read\",\"arguments\":{}}[[/tool_call]]"
        var published = ""
        val outcome = runner.run(
            toolsEnabled = false,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(marker, emptyList()), marker)
            },
            persistAssistant = { published = it.displayText },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
        assertEquals(marker, published)
    }
}

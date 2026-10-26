package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import top.tianyan.app.harness.core.TurnOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRunnerTest {
    private val runner = TurnRunner(ProviderResponseNormalizer(Json { ignoreUnknownKeys = true }))

    @Test
    fun `successful tool on last round still requires another model turn`() = runBlocking {
        var executed = false
        val marker = "[[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"file.txt\"}}[[/tool_call]]"
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Success(ChatResult(marker, emptyList()), marker) },
            persistAssistant = {}, consumeFollowUps = { error("must not consume") },
            enforceToolLimit = { calls, _ -> calls }, executeTools = { _, _ -> executed = true; true },
            remainingRounds = 1,
        )
        assertTrue(executed)
        assertTrue(outcome is TurnOutcome.RoundLimit)
    }

    @Test
    fun `last round with pending follow up is not reported complete`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Success(ChatResult(content = "done", toolCalls = emptyList()), "done") },
            persistAssistant = {}, consumeFollowUps = { 1 },
            enforceToolLimit = { calls, _ -> calls }, executeTools = { _, _ -> true },
            remainingRounds = 1,
        )
        assertTrue(outcome is TurnOutcome.RoundLimit)
    }

    @Test
    fun `final answer on last allowed round can complete`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Success(ChatResult(content = "done", toolCalls = emptyList()), "done") },
            persistAssistant = {}, consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls }, executeTools = { _, _ -> true },
            remainingRounds = 1,
        )
        assertEquals(TurnOutcome.Complete, outcome)
    }

    @Test
    fun `exhausted budget does not call provider`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { error("must not call provider") },
            persistAssistant = {}, consumeFollowUps = { error("must not consume queue") },
            enforceToolLimit = { calls, _ -> calls }, executeTools = { _, _ -> error("must not execute") },
            remainingRounds = 0,
        )
        assertTrue(outcome is TurnOutcome.RoundLimit)
    }

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

    @Test
    fun `blank provider response fails instead of completing silently`() = runBlocking {
        var published = false
        var consumed = false
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Success(ChatResult(null, emptyList()), "") },
            observeResponse = {},
            persistAssistant = { published = true },
            consumeFollowUps = { consumed = true; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        // 空响应必须变成可见失败：不能走"无工具调用 → Complete"，否则前台零提示。
        assertEquals(TurnOutcome.Failed(ProviderClient.EMPTY_RESPONSE_MESSAGE), outcome)
        assertTrue(published)
        assertFalse(consumed)
    }

    @Test
    fun `near blank response with residual reasoning fails instead of completing silently`() = runBlocking {
        var consumed = false
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                // 复现线上事故：中转截断产生的空壳响应——正文与工具全空，reasoning 只剩 2~3 字符残渣。
                TurnProviderOutcome.Success(
                    ChatResult(content = null, toolCalls = emptyList(), reasoningContent = "\n\n"),
                    "",
                )
            },
            observeResponse = {},
            persistAssistant = {},
            consumeFollowUps = { consumed = true; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        // 残渣级思考不能算"有内容"：否则会走"无工具调用 → Complete"，把静默失败记成完成。
        assertEquals(TurnOutcome.Failed(ProviderClient.EMPTY_RESPONSE_MESSAGE), outcome)
        assertFalse(consumed)
    }

    @Test
    fun `substantive reasoning alone is not treated as blank`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                // 成段思考 + 无正文 + 无工具是模型自主收束的合法形态，不得被残渣阈值误杀。
                TurnProviderOutcome.Success(
                    ChatResult(
                        content = null,
                        toolCalls = emptyList(),
                        reasoningContent = "这是一段明显超过残渣阈值长度的正常推理内容，应当被视为有效产出",
                    ),
                    "",
                )
            },
            persistAssistant = {},
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
    }
}

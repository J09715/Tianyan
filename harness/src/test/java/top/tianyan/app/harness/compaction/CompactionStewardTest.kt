package top.tianyan.app.harness.compaction

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.ChunkedText
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

class CompactionStewardTest {

    private fun model() = ModelConfig(
        name = "测试", provider = "p", model = "m", baseUrl = "http://localhost", apiKey = null,
    )

    private class Script(vararg verdicts: String) {
        val prompts = mutableListOf<String>()
        val caller = StewardJudgeCaller { _, messages ->
            prompts += messages.last().content.orEmpty()
            verdicts.first()
        }
    }

    @Test
    fun `switches when the judge says the task is done`() = runTest {
        val script = Script("""{"done": true}""")
        assertTrue(CompactionSteward(script.caller).shouldSwitchSegment(model(), "用户: 做完了\n助手: 已完成并验收"))
        // 判定提示里必须携带近期转录与一行 JSON 要求
        assertTrue(script.prompts.single().contains("做完了"))
    }

    @Test
    fun `compacts when the judge says the task is ongoing`() = runTest {
        val steward = CompactionSteward(Script("""{"done": false}""").caller)
        assertFalse(steward.shouldSwitchSegment(model(), "用户: 继续修\n助手: 正在跑测试"))
    }

    @Test
    fun `fenced or noisy verdicts still parse`() = runTest {
        val steward = CompactionSteward(Script("```json\n{\"done\": true}\n```").caller)
        assertTrue(steward.shouldSwitchSegment(model(), "x"))
        assertTrue(steward.parseDone("前置说明 {\"done\": true} 后置说明"))
        assertFalse(steward.parseDone("完全不是 JSON"))
        assertFalse(steward.parseDone(""))
        // 严格布尔解析：说明性文本不算命中；字符串 "true" 按内容宽容接受
        assertTrue(steward.parseDone("""{"done": "true"}"""))
        assertFalse(steward.parseDone("""{"done": 并非 true}"""))
    }

    @Test
    fun `judge failures fall back to compact`() = runTest {
        val steward = CompactionSteward(caller = { _, _ -> throw java.io.IOException("offline") })
        assertFalse(steward.shouldSwitchSegment(model(), "近期对话"))
    }

    @Test
    fun `blank transcript skips the judge entirely`() = runTest {
        val script = Script("""{"done": true}""")
        assertFalse(CompactionSteward(script.caller).shouldSwitchSegment(model(), ""))
        assertTrue(script.prompts.isEmpty())
    }

    @Test
    fun `switch keep index prefers the last user turn and never empties the tail`() {
        val messages = listOf(
            UserMessage("a", 1, "第一轮") as top.tianyan.app.harness.HarnessMessage,
            AssistantText("b", 2, ChunkedText.of("已答复")) as top.tianyan.app.harness.HarnessMessage,
            UserMessage("c", 3, "第二轮"),
        )
        assertEquals(2, CompactionSteward.switchKeepFromIndex(messages, 0))
        // 预算线更深时尊重预算线，但始终至少保留最后一条（keepFromIndex 钳在末条内）
        assertEquals(2, CompactionSteward.switchKeepFromIndex(messages, 3))
        // 无用户消息时退化为预算线，且至少保留最后一条
        val toolOnly = listOf<top.tianyan.app.harness.HarnessMessage>(ToolResult("r", 3, "c", true, "ok"))
        assertEquals(0, CompactionSteward.switchKeepFromIndex(toolOnly, 5))
    }

    @Test
    fun `recent transcript bounds chars and keeps latest lines`() {
        val messages = (1..50).map { index ->
            UserMessage("u$index", index.toLong(), "消息$index-" + "x".repeat(200))
        }
        val transcript = CompactionSteward.recentTranscript(messages, maxChars = 2_000)
        assertTrue(transcript.length <= 2_100)
        assertTrue(transcript.contains("消息50"))
        assertFalse(transcript.contains("消息1-"))
    }
}

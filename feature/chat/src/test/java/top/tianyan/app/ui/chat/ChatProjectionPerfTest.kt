package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/**
 * 聊天投影路径的复杂度守卫。
 *
 * 背景：`projectChatMessages` 在**每一次流式增量发布**时都会被重跑（UI 层用
 * `remember(messages, toolResults, expandedOverrides)` 缓存，而 messages 每个
 * 发布帧都是新列表）。因此它的单次成本必须与「消息总数」线性，而不能有隐藏的
 * 平方项——长对话下平方项会把每个发布帧都拖成几十毫秒，表现为整页掉帧。
 *
 * 这组测试不测墙钟时间（CI 机器抖动会让阈值变成 flaky），而是测**算法不变量**：
 *   1. 投影结果条数与被消费的消息数严格对应，不因规模变化而漂移；
 *   2. 工具卡的 reasoning 去重是「同轮内已见即隐藏」的语义，且不随消息数增长而
 *      改变判定——这是单趟 HashSet 实现与旧的向后回扫实现唯一的行为差异点；
 *   3. 大输入（2000 条）能在合理时间内完成，作为平方级回归的粗糙护栏。
 *
 * 第 3 条的阈值刻意放得很宽（10 秒）：它要抓的是 O(n²) 爆炸（2000² = 4M 次
 * 比较在低配 CI 上会远超此值），而不是几毫秒的正常波动。
 */
class ChatProjectionPerfTest {

    private fun makeUser(id: String) = UserMessage(id = id, createdAt = 1000L, text = "query")

    private fun makeAssistant(id: String, text: String, reasoning: String? = null) =
        AssistantText(id = id, createdAt = 1000L, text = text, reasoning = reasoning)

    private fun makeToolCall(id: String, reasoning: String? = null) =
        ToolCall(id = id, createdAt = 1000L, tool = HarnessTool.READ, args = buildJsonObject {}, reasoning = reasoning)

    private fun makeToolResult(toolCallId: String) = ToolResult(
        id = "res_$toolCallId",
        createdAt = 1000L,
        toolCallId = toolCallId,
        success = true,
        output = "ok",
        durationMs = 1000L,
    )

    /** 一条「用户提问 → N 步思考/工具 → 最终回复」的完整回合。 */
    private fun buildRound(round: Int, steps: Int): List<HarnessMessage> = buildList {
        add(makeUser("u$round"))
        repeat(steps) { step ->
            // reasoning 每步都不同：确保不会被同轮去重逻辑吞掉，从而真实走完全部分支
            add(makeToolCall("tc_${round}_$step", reasoning = "reasoning-$round-$step"))
            add(makeToolResult("tc_${round}_$step"))
        }
        add(makeAssistant("a$round", "answer $round"))
    }

    @Test
    fun `projection size scales linearly with consumed messages`() {
        // ToolResult 由 ToolCard 内部消费，不产出渲染项；其余每条产出一项。
        listOf(1, 5, 20).forEach { rounds ->
            val messages = (1..rounds).flatMap { buildRound(it, steps = 3) }
            val items = projectChatMessages(messages)
            val toolResults = messages.count { it is ToolResult }
            val expected = messages.size - toolResults
            assertTrue(
                "回合数 $rounds：投影条数应与（消息数 - ToolResult 数）一致，" +
                    "实际 ${items.size}，期望 $expected",
                items.size == expected,
            )
        }
    }

    /**
     * 同轮 reasoning 去重：同一 reasoning 文本在**同一轮**内第二次出现时必须被隐藏，
     * 跨轮则重新可见（遇 UserMessage 清空已见集合）。
     *
     * 这条锁住的是单趟实现与旧向后回扫实现的语义等价性——两者唯一的差异点就在这里。
     */
    @Test
    fun `reasoning dedup is scoped to the current round`() {
        val sameReasoning = "identical reasoning"

        // 同一轮内：第二个工具卡携带相同 reasoning → 隐藏
        val withinRound = listOf(
            makeUser("u1"),
            makeToolCall("tc1", reasoning = sameReasoning),
            makeToolCall("tc2", reasoning = sameReasoning),
        )
        val within = projectChatMessages(withinRound)
        val tc1 = within.first { it.stableKey == "tc1" } as ChatRenderItem.MessageItem
        val tc2 = within.first { it.stableKey == "tc2" } as ChatRenderItem.MessageItem
        assertTrue("同轮内首个工具卡应展示 reasoning", tc1.showReasoning)
        assertTrue("同轮内重复 reasoning 应被隐藏", !tc2.showReasoning)

        // 跨轮：新一轮遇到相同文本 → 重新可见
        val acrossRounds = withinRound + listOf(
            makeUser("u2"),
            makeToolCall("tc3", reasoning = sameReasoning),
        )
        val across = projectChatMessages(acrossRounds)
        val tc3 = across.first { it.stableKey == "tc3" } as ChatRenderItem.MessageItem
        assertTrue("跨轮后相同 reasoning 应重新可见（已见集合在 UserMessage 处清空）", tc3.showReasoning)
    }

    /**
     * 大输入护栏：2000 条消息的投影必须在一秒级内完成。
     *
     * 阈值取 10 秒是刻意宽松的——它只用于抓平方级退化。当前实现（单趟 + HashSet）
     * 在 2000 条上实测为毫秒级；若有人改回向后回扫，4M 次比较会立刻撞破这个上界。
     */
    @Test
    fun `large conversation projection stays sub-linear-squared`() {
        val messages = (1..400).flatMap { buildRound(it, steps = 2) } // 400 轮 × 6 条 = 2400 条
        assertTrue("构造的样本规模应为 2400 条，实际 ${messages.size}", messages.size == 2400)

        // 预热一次，避免把类加载/JIT 冷启动算进预算
        projectChatMessages(messages)

        val started = System.nanoTime()
        val items = projectChatMessages(messages)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("投影应产出渲染项", items.isNotEmpty())
        assertTrue(
            "2400 条消息的投影耗时 ${elapsedMs}ms，超过 10000ms 上界——" +
                "疑似引入了随消息数平方增长的逻辑（例如向后回扫、逐项 indexOf、重复序列化）",
            elapsedMs < 10_000,
        )
    }
}

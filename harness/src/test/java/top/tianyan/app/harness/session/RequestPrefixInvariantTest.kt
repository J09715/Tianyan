package top.tianyan.app.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.UserMessage

/**
 * 请求前缀不变式（针对**真实渲染路径**）。
 *
 * ## 这组测试为什么必须存在
 *
 * 我第一版写了一个「源码断言」守卫：检查 `recallSection` 有没有被写进
 * `dynamic = listOf(...)`。那个守卫是自欺欺人的 —— 它看的是代码文本。
 * 我把动态尾部从 system prompt 挪到「渲染时拼到最后一条 user 消息里」，
 * 它照样全绿，而**缓存实际仍然每轮全量失效**：
 *
 * ```
 * 第1轮: [S][U1+tail1][A1]
 * 第2轮: [S][U1      ][A1][U2+tail2]
 *              ↑ U1 的字节变了 → 断裂点从 system 搬到了这里，其后照样全量 prefill
 * ```
 *
 * 所以这里直接对 `HarnessApiMapper` 的真实渲染结果做断言，
 * 而不是对源码文本。测的是「同一批持久化消息，能否稳定渲染出同样的字节」。
 */
class RequestPrefixInvariantTest {

    /**
     * 用真实渲染路径把消息列表转成请求消息。
     * 这就是 `ApiContextAssembler` 实际走的转换（除压缩折叠外）。
     */
    private fun render(messages: List<top.tianyan.app.harness.HarnessMessage>) =
        messages.map { HarnessApiMapper.toApiMessage(it) }

    private fun user(id: String, text: String, tail: String = "") =
        UserMessage(id = id, createdAt = id.hashCode().toLong(), text = text, contextTail = tail)

    /**
     * 关键不变式：同一批**已持久化**的消息，无论渲染多少次、顺序如何，
     * 都必须产出逐字节相同的结果。
     *
     * 这代替了「尾部挂在最后一条消息上」的错误做法：那种做法下，
     * 同一批消息在不同轮次渲染结果不同（因为"最后一条"变了）。
     */
    @Test
    fun `rendering is a pure function of persisted messages`() {
        val messages = listOf(
            user("u1", "看看这个文件", "<context-update>记忆A</context-update>"),
            AssistantText("a1", 2L, "好的"),
            user("u2", "继续", "<context-update>记忆B</context-update>"),
        )
        val first = render(messages)
        val second = render(messages)
        assertEquals("同一批消息两次渲染必须逐字节相同", first, second)
    }

    /**
     * 第 N+1 轮请求必须以第 N 轮请求的**全部字节**为前缀。
     *
     * 模拟真实场景：第 1 轮发出后，第 2 轮在末尾追加新消息（历史不动）。
     */
    @Test
    fun `next turn request extends the previous request as a strict prefix`() {
        // 第 1 轮：用户消息携带自己的尾部（持久化）
        val turn1 = listOf(
            user("u1", "看看这个文件", "<context-update>记忆A</context-update>"),
            AssistantText("a1", 2L, "好的"),
        )
        // 第 2 轮：历史原样保留，只在末尾追加新的用户消息（带它自己的尾部）
        val turn2 = turn1 + user("u2", "继续", "<context-update>记忆B</context-update>")

        val r1 = render(turn1)
        val r2 = render(turn2)
        assertTrue(
            "第 2 轮必须以第 1 轮全部字节为前缀，否则缓存从断裂点起全量失效。" +
                "实际 r1=${r1.map { it.content }}",
            RequestShaping.preservesPrefix(r1, r2),
        )
    }

    @Test
    fun `three consecutive turns stay prefix-chained`() {
        val t1 = listOf(user("u1", "第一轮", "tail-1"), AssistantText("a1", 2L, "ok"))
        val t2 = t1 + listOf(user("u2", "第二轮", "tail-2"), AssistantText("a2", 4L, "ok"))
        val t3 = t2 + listOf(user("u3", "第三轮", "tail-3"), AssistantText("a3", 6L, "ok"))

        val r1 = render(t1); val r2 = render(t2); val r3 = render(t3)
        assertTrue("第 1→2 轮前缀断裂", RequestShaping.preservesPrefix(r1, r2))
        assertTrue("第 2→3 轮前缀断裂", RequestShaping.preservesPrefix(r2, r3))
        assertTrue("第 1→3 轮前缀断裂", RequestShaping.preservesPrefix(r1, r3))
    }

    /**
     * 反向用例：如果尾部**不持久化**（渲染期现算），
     * 第 2 轮把尾部挂到"最后一条"上时，第 1 轮那条 user 消息的字节就变了。
     * 这条测试证明守卫能识别出我第一版的缺陷形态。
     */
    @Test
    fun `render-time tail merging breaks the prefix`() {
        // 第 1 轮：尾部被拼进第一条 user 消息（无持久化字段）
        val bad1 = listOf(
            user("u1", "看看这个文件").copy(contextTail = "tail-1"),
            AssistantText("a1", 2L, "好的"),
        )
        // 第 2 轮：同一条消息的尾部变成"空"（因为渲染期只给最后一条拼尾部）
        val bad2 = listOf(
            user("u1", "看看这个文件"),          // ← 字节与上轮不同
            AssistantText("a1", 2L, "好的"),
            user("u2", "继续", "tail-2"),
        )
        assertFalse(
            "渲染期现算尾部必然破坏前缀 —— 这正是本测试要拦住的缺陷形态",
            RequestShaping.preservesPrefix(render(bad1), render(bad2)),
        )
    }

    @Test
    fun `empty tail renders identically to no tail`() {
        val withEmpty = render(listOf(user("u1", "你好", "")))
        val plain = render(listOf(UserMessage("u1", 0L, "你好")))
        assertEquals("空尾部不应引入任何额外字节（含分隔符）", plain, withEmpty)
        assertEquals("你好", withEmpty.single().content)
    }

    @Test
    fun `tail is appended after a separating blank line`() {
        val rendered = render(listOf(user("u1", "问题", "<context-update>x</context-update>")))
        assertEquals("问题\n\n<context-update>x</context-update>", rendered.single().content)
    }

    /** 尾部含尾随空白时不应把分隔符叠成多行（保证字节可预测）。 */
    @Test
    fun `trailing whitespace in text does not duplicate separators`() {
        val rendered = render(listOf(user("u1", "问题   \n", "tail")))
        assertEquals("问题\n\ntail", rendered.single().content)
    }

    @Test
    fun `tool history is preserved byte for byte`() {
        val call = top.tianyan.app.harness.ToolCall(
            id = "c1",
            createdAt = 3L,
            tool = top.tianyan.app.harness.HarnessTool.READ,
            args = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("a.kt"))
            },
        )
        val base = listOf(
            user("u1", "读文件", "tail-1"),
            call,
            top.tianyan.app.harness.ToolResult("r1", 4L, "c1", true, "内容"),
        )
        val next = base + user("u2", "继续", "tail-2")
        assertTrue("带工具往返的历史必须保持前缀", RequestShaping.preservesPrefix(render(base), render(next)))
        assertEquals("c1", render(next)[1].tool_calls?.single()?.id)
    }
}

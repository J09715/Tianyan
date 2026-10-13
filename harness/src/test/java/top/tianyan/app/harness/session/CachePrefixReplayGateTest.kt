package top.tianyan.app.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.UserMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 缓存前缀重放门禁（对齐天枢 `cache-prefix-replay.test.ts` 的写法）。
 *
 * ## 为什么是「重放」而不是「抽样断言」
 *
 * 天枢的做法值得照抄：**逐步重放整条会话**，把第 N 轮与第 N+1 轮的请求
 * 序列化成字节序列逐条比对，报告**最早分歧点**与两侧字节。
 *
 * 我上一版只比了手搓的三两条消息 —— 那证明不了真实会话上的稳定性，
 * 因为真实的失效点往往在「某条消息上一轮带尾部、这一轮不带」这种
 * 跨版本的形态上，手搓的用例恰好避开了它。
 *
 * ## 不变式
 *
 * 除显式的压缩重写外，第 N 轮请求的每一条消息都必须与第 N+1 轮
 * **逐字节相同**（前缀），且请求不得变短。
 */
class CachePrefixReplayGateTest {

    private data class Divergence(val index: Int, val prev: String, val cur: String)

    /** 把消息序列化为字节串序列（对齐 JSON.stringify 的粒度）。 */
    private fun serialize(messages: List<HarnessMessage>): List<String> =
        messages.map { HarnessApiMapper.toApiMessage(it).let { a -> a.toString() } }

    /** 返回 prev 不是 cur 字节前缀的最早位置；无分歧返回 null。 */
    private fun earliestDivergence(prev: List<String>, cur: List<String>): Divergence? {
        for (i in prev.indices) {
            if (i >= cur.size) return Divergence(i, prev[i], "<missing — request shrank>")
            if (prev[i] != cur[i]) return Divergence(i, prev[i], cur[i])
        }
        return null
    }

    private fun user(id: String, text: String, tail: String = "") =
        UserMessage(id = id, createdAt = id.hashCode().toLong(), text = text, contextTail = tail)

    private fun assistant(id: String, text: String) = AssistantText(id, id.hashCode().toLong(), text)

    /**
     * 核心用例：6 轮追加式会话，每轮都不得在上一轮前缀内部产生分歧。
     *
     * 这正是天枢那条 `append-only multi-turn conversation never diverges`。
     */
    @Test
    fun `append-only multi-turn conversation never diverges inside the previous prefix`() {
        val conv = mutableListOf<HarnessMessage>()
        var prev: List<String> = emptyList()

        for (turn in 1..6) {
            conv += user("u$turn", "第 $turn 轮问题", "<context-update>tail-$turn</context-update>")
            val bytes = serialize(conv)
            val div = earliestDivergence(prev, bytes)
            assertNull(
                "第 $turn 轮在前缀内产生分歧：index=${div?.index}\n" +
                    "prev=${div?.prev?.take(200)}\ncur=${div?.cur?.take(200)}",
                div,
            )
            assertTrue(
                "第 $turn 轮请求不得变短（prev=${prev.size} cur=${bytes.size}）",
                bytes.size >= prev.size,
            )
            prev = bytes
            conv += assistant("a$turn", "第 $turn 轮回答")
        }
    }

    /** 工具往返轮：历史含 tool_calls / tool_result 时同样保持前缀。 */
    @Test
    fun `tool-call rounds keep the previous request as a byte prefix`() {
        val conv = mutableListOf<HarnessMessage>(
            user("u1", "读文件", "tail-1"),
        )
        var prev = serialize(conv)

        val call = ToolCall(
            id = "c1",
            createdAt = 1L,
            tool = HarnessTool.READ,
            args = buildJsonObject { put("path", JsonPrimitive("a.kt")) },
        )
        conv += call
        conv += ToolResult("r1", 2L, "c1", true, "文件内容")
        conv += assistant("a1", "看完了")

        val bytes = serialize(conv)
        assertNull(
            "工具往返轮不得在前缀内分歧：${earliestDivergence(prev, bytes)}",
            earliestDivergence(prev, bytes),
        )
        prev = bytes

        conv += user("u2", "继续", "tail-2")
        assertNull(
            "工具往返后追加新用户消息不得分歧：${earliestDivergence(prev, serialize(conv))}",
            earliestDivergence(prev, serialize(conv)),
        )
    }

    /**
     * 跨版本会话：v0.18.15 之前创建的历史消息 contextTail 为空串。
     *
     * 这类会话在新版本里首次运行时的形态是：
     * ```
     * 第1轮: [U1("")][U2("")][U3+tail3]
     * 第2轮: [U1("")][U2("")][U3("")][U4+tail4]
     * ```
     * U3 在第 1 轮带尾部、第 2 轮为空 —— 若渲染期现算，这里就是断裂点。
     * 因为尾部**已随消息持久化**，U3 的尾部不会消失，前缀保持完整。
     */
    @Test
    fun `legacy session messages with empty tail keep their bytes stable`() {
        // 第 1 轮：模拟老会话的两条历史消息（无尾部）+ 本轮新消息（有尾部）
        val turn1 = listOf(
            user("legacy-1", "很久以前的提问"),
            assistant("legacy-a1", "很久以前的回答"),
            user("legacy-2", "另一个老提问"),
            assistant("legacy-a2", "另一个老回答"),
            user("u3", "本轮新问题", "<context-update>tail-3</context-update>"),
        )
        val bytes1 = serialize(turn1)
        assertEquals(
            "老消息（无尾部）应渲染为纯正文",
            "很久以前的提问",
            HarnessApiMapper.toApiMessage(turn1[0]).content,
        )

        // 第 2 轮：历史原样，追加新的用户消息
        val turn2 = turn1 + user("u4", "继续", "<context-update>tail-4</context-update>")
        val div = earliestDivergence(bytes1, serialize(turn2))
        assertNull(
            "跨版本会话不得在老消息处断裂：index=${div?.index}\n" +
                "prev=${div?.prev?.take(200)}\ncur=${div?.cur?.take(200)}",
            div,
        )
    }

    /**
     * 尾部内容变化不得影响**历史**消息的字节。
     *
     * 这直接拦「把本轮尾部拼进历史消息」的错误做法。
     */
    @Test
    fun `changing this turn's tail does not alter history bytes`() {
        val history = listOf(
            user("u1", "第一轮", "<context-update>old-tail</context-update>"),
            assistant("a1", "回答"),
        )
        val forbidden = history + user("u2", "第二轮", "<context-update>new-tail</context-update>")
        val rendered = serialize(forbidden)

        assertEquals(
            "历史消息的字节必须与本轮尾部无关",
            "第一轮\n\n<context-update>old-tail</context-update>",
            HarnessApiMapper.toApiMessage(forbidden[0]).content,
        )
        // 历史前缀与「只有历史」时逐字节一致
        val historyOnly = serialize(history)
        assertNull(
            "historyOnly 必须是完整请求的前缀",
            earliestDivergence(historyOnly, rendered),
        )
    }

    /** 空尾部：老消息与新消息都不得因分隔符产生字节差异。 */
    @Test
    fun `blank tail renders identically to a plain message`() {
        val withBlank = HarnessApiMapper.toApiMessage(user("u1", "你好", "  \n "))
        val plain = HarnessApiMapper.toApiMessage(UserMessage("u1", 0L, "你好"))
        assertEquals("空/空白尾部不得引入任何额外字节", plain.content, withBlank.content)
        assertEquals("你好", withBlank.content)
    }

    /** 渲染必须是纯函数：同样输入多次渲染结果相同。 */
    @Test
    fun `rendering is deterministic across repeated calls`() {
        val conv = listOf(
            user("u1", "问题", "<context-update>t</context-update>"),
            assistant("a1", "回答"),
            user("u2", "再问", ""),
        )
        val first = serialize(conv)
        repeat(3) { assertEquals("第 ${it + 2} 次渲染结果必须相同", first, serialize(conv)) }
    }
}

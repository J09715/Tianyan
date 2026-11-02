package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/**
 * 增量投影（前缀复用 + 末条单投影）必须与全量投影逐 token 等价 ——
 * 流式热路径省掉的正是整表重投影，一旦切点/分组边界判断错了，UI 会出现重复过程链或错位。
 */
class ChatRenderItemsCacheTest {
    private fun call(id: String, tool: HarnessTool = HarnessTool.BASE) = ToolCall(id, 10, tool, buildJsonObject {})
    private fun result(id: String) = ToolResult("r-$id", 20, id, true, "output")
    private fun user(id: String) = UserMessage(id, 1, "request")

    private fun projected(cache: ChatRenderItemsCache, messages: List<HarnessMessage>) = cache.project(
        messages = messages,
        toolResults = emptyMap(),
        expandedOverrides = emptyMap(),
        collapseEnabled = false,
        revealLimits = emptyMap(),
        focusedId = null,
    )

    @Test fun streamingTextGrowthMatchesFullProjectionOnEveryToken() {
        val cache = ChatRenderItemsCache()
        val history = listOf(user("u"), AssistantText("a", 30, "hello"), call("c1"), result("c1"))
        projected(cache, history)
        var text = ""
        repeat(64) { token ->
            text += if (token % 8 == 0) " 分词" else "字"
            val messages = history + AssistantText("s", 40, text)
            assertEquals("token #$token", projectChatTimeline(messages), projected(cache, messages))
        }
    }

    @Test fun growingToolChainStaysOneGroupAndMatchesFullProjection() {
        val cache = ChatRenderItemsCache()
        val messages = mutableListOf<HarnessMessage>(user("u"), AssistantText("a", 30, "开始"))
        projected(cache, messages.toList())
        repeat(8) { index ->
            messages += call("c$index")
            assertEquals(projectChatTimeline(messages.toList()), projected(cache, messages.toList()))
            messages += result("c$index")
            assertEquals(projectChatTimeline(messages.toList()), projected(cache, messages.toList()))
        }
        assertEquals(1, projected(cache, messages.toList()).filterIsInstance<ToolStepsItem>().size)
    }

    @Test fun reasoningHandoffAndNewUserTurnResetToFullProjection() {
        val cache = ChatRenderItemsCache()
        val first = listOf(user("u1"), AssistantText("a1", 30, "", "思考中"))
        projected(cache, first)
        val withAnswer = first + AssistantText("a2", 40, "回答")
        assertEquals(projectChatTimeline(withAnswer), projected(cache, withAnswer))
        val secondTurn = withAnswer + user("u2") + AssistantText("a3", 50, "第二问")
        assertEquals(projectChatTimeline(secondTurn), projected(cache, secondTurn))
        // 历史条目被改写（非末条）时必须整表重算，不能复用旧前缀。
        val edited = listOf(user("u1"), AssistantText("a1", 30, "改过的正文")) + secondTurn.drop(2)
        assertEquals(projectChatTimeline(edited), projected(cache, edited))
    }

    @Test fun repeatedCallWithSameListInstanceShortCircuits() {
        val cache = ChatRenderItemsCache()
        val messages = listOf(user("u"), call("c1"), result("c1"))
        val first = projected(cache, messages)
        val second = projected(cache, messages)
        assertTrue(first === second)
    }
}

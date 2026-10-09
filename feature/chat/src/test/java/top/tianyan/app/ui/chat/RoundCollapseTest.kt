package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

class RoundCollapseTest {

    private fun makeUser(id: String, text: String = "query") =
        UserMessage(id = id, createdAt = 1000L, text = text)

    private fun makeAssistant(id: String, text: String, reasoning: String? = null) =
        AssistantText(id = id, createdAt = 1000L, text = text, reasoning = reasoning)

    private fun makeToolCall(id: String, tool: HarnessTool = HarnessTool.READ, reasoning: String? = null) =
        ToolCall(id = id, createdAt = 1000L, tool = tool, args = buildJsonObject {}, reasoning = reasoning)

    private fun makeToolResult(toolCallId: String, durationMs: Long? = 1000L) =
        ToolResult(
            id = "res_$toolCallId",
            createdAt = 1000L,
            toolCallId = toolCallId,
            success = true,
            output = "ok",
            durationMs = durationMs,
        )

    @Test
    fun `empty messages returns empty list`() {
        val items = projectChatMessages(emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `round with 2 or fewer steps is never collapsed and shows no button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"), // creates a new round so u1 becomes historical
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages)
        // Check u1 round: should have u1, t1, t2, a1 (no CollapseButtonItem)
        val buttons = items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()
        assertTrue("≤ 2 步的历史轮不应该有折叠按钮", buttons.isEmpty())

        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds)
    }

    @Test
    fun `tool results are excluded from direct render items`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages)
        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("u1", "t1", "a1"), messageIds)
    }

    @Test
    fun `all assistant and user messages and tool calls are preserved naturally in order`() {
        val messages = listOf(
            makeAssistant("a_init", "Welcome"),
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Answer"),
            makeUser("u2"),
            makeAssistant("a2", "Next"),
        )
        val items = projectChatMessages(messages)
        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("a_init", "u1", "t1", "t2", "a1", "u2", "a2"), messageIds)
    }

    // ==================== showReasoning 投影期预计算（P7：列表项零回扫） ====================

    private fun showReasoningOf(items: List<ChatRenderItem>, id: String): Boolean {
        val item = items.filterIsInstance<ChatRenderItem.MessageItem>().first { it.message.id == id }
        return item.showReasoning
    }

    @Test
    fun `tool call without reasoning has showReasoning false`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages)
        assertFalse("无 reasoning 的工具卡不应展示思考链", showReasoningOf(items, "t1"))
    }

    @Test
    fun `tool call with fresh reasoning has showReasoning true`() {
        val messages = listOf(
            makeUser("u1"),
            makeAssistant("a1", "Let me check", reasoning = "thinking-A"),
            makeToolCall("t1", reasoning = "thinking-B"),
        )
        val items = projectChatMessages(messages)
        assertTrue("前文未出现过的 reasoning 应展示", showReasoningOf(items, "t1"))
    }

    @Test
    fun `tool call reasoning already shown by earlier assistant in same round is hidden`() {
        val messages = listOf(
            makeUser("u1"),
            makeAssistant("a1", "Let me check", reasoning = "same-thinking"),
            makeToolCall("t1", reasoning = "same-thinking"),
        )
        val items = projectChatMessages(messages)
        assertFalse("同轮前文已展示过的 reasoning 不应重复展示", showReasoningOf(items, "t1"))
    }

    @Test
    fun `duplicate reasoning across consecutive tool calls is deduplicated`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1", reasoning = "dup"),
            makeToolCall("t2", reasoning = "dup"),
        )
        val items = projectChatMessages(messages)
        assertTrue("首个携带者应展示", showReasoningOf(items, "t1"))
        assertFalse("后续重复者不应展示", showReasoningOf(items, "t2"))
    }

    @Test
    fun `reasoning dedup does not cross previous user message boundary`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1", reasoning = "round-thinking"),
            makeAssistant("a1", "Done"),
            // 新一轮：相同 reasoning 字符串，但前文在上一轮，不应被去重
            makeUser("u2"),
            makeToolCall("t2", reasoning = "round-thinking"),
        )
        val items = projectChatMessages(messages)
        assertTrue("跨轮（越过用户消息边界）的 reasoning 不应被去重", showReasoningOf(items, "t2"))
    }

    @Test
    fun `non tool call messages always have showReasoning true`() {
        val messages = listOf(
            makeAssistant("a1", "text", reasoning = "r"),
            makeUser("u1"),
        )
        val items = projectChatMessages(messages)
        assertTrue(showReasoningOf(items, "a1"))
        assertTrue(showReasoningOf(items, "u1"))
    }
}

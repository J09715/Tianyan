package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.harness.*

class ChatTimelineSpacingTest {
    private fun tool(id: String, tool: HarnessTool = HarnessTool.BASE) =
        ChatRenderItem.MessageItem(ToolCall(id, 1, tool, buildJsonObject {}))

    @Test fun groupedCommandsAndStandaloneQuestionHaveNoExtraBoundaryGap() {
        val group = ToolStepsItem(listOf(tool("plan", HarnessTool.PLAN), tool("command")))
        val question = tool("question", HarnessTool.ASK_USER)
        assertEquals(0, chatTimelineItemSpacing(group, question))
        assertEquals(0, chatTimelineItemSpacing(question, group))
        assertEquals(0, chatTimelineItemSpacing(tool("a"), tool("b")))
    }

    @Test fun proseAndDedicatedCardsKeepTheirOwnSeparation() {
        val prose = ChatRenderItem.MessageItem(AssistantText("a", 1, "answer"))
        val user = ChatRenderItem.MessageItem(UserMessage("u", 2, "next"))
        assertEquals(4, chatTimelineItemSpacing(prose, tool("t")))
        assertEquals(8, chatTimelineItemSpacing(prose, tool("sub", HarnessTool.SUBAGENT)))
        assertEquals(16, chatTimelineItemSpacing(prose, user))
        assertEquals(0, chatTimelineItemSpacing(null, prose))
    }
}

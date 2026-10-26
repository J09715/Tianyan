package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.task.*

class TaskDeliveryProjectionTest {
    private fun delivery(withEvidence: Boolean = true) = TaskDelivery("delivery", 8,
        TaskDeliveryReport("task", "s", "op", null, "final", "final",
            if (withEvidence) listOf(ToolObservation("call", "result", "base", ObservationState.SUCCEEDED)) else emptyList(),
            emptyList(), true, listOf(DeliveryGap.GOAL_NOT_VERIFIED), 8))

    @Test fun executionRecordRemainsVisibleWhenHistoricalToolsCollapse() {
        val messages = listOf(UserMessage("input", 1, "build")) + (1..4).map {
            ToolCall("call$it", it.toLong() + 1, HarnessTool.BASE, buildJsonObject { })
        } + listOf(AssistantText("final", 7, "done"), delivery(), UserMessage("next", 9, "next"))
        for (collapse in listOf(false, true)) {
            val items = projectChatMessages(messages, collapseEnabled = collapse)
            val record = items.filterIsInstance<ChatRenderItem.MessageItem>().single { it.message is TaskDelivery }
            assertEquals(messages.indexOfFirst { it.id == "delivery" }, record.rawIndex)
        }
    }

    @Test fun plainConversationDoesNotAddAnEmptyDeliveryCard() {
        val messages = listOf(UserMessage("input", 1, "hello"), AssistantText("final", 2, "hello"), delivery(false))
        for (collapse in listOf(false, true)) {
            assertTrue(projectChatMessages(messages, collapseEnabled = collapse).filterIsInstance<ChatRenderItem.MessageItem>()
                .none { it.message is TaskDelivery })
        }
    }
}

package top.tianyan.app.ui.chat

import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage

/** Compact tool rows share one rhythm even when an approval overview is outside a tool group. */
internal fun chatTimelineItemSpacing(previous: ChatRenderItem?, current: ChatRenderItem): Int {
    if (previous == null) return 0
    val message = (current as? ChatRenderItem.MessageItem)?.message
    if (message is UserMessage) return 16
    if (previous.isCompactToolLine() && current.isCompactToolLine()) return 0
    if (previous.isCompactToolLine() || current.isCompactToolLine() ||
        (current is ChatRenderItem.MessageItem && message is AssistantText && current.showReasoning &&
            !message.reasoning.isNullOrBlank())) return 4
    return 8
}

private fun ChatRenderItem.isCompactToolLine(): Boolean {
    if (this is ToolStepsItem || this is ChatRenderItem.ReasoningItem) return true
    val call = (this as? ChatRenderItem.MessageItem)?.message as? ToolCall ?: return false
    return call.tool != HarnessTool.SUBAGENT && call.tool != HarnessTool.RENDER_SURFACE
}

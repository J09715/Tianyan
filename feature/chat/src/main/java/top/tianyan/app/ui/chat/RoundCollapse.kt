package top.tianyan.app.ui.chat

import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/**
 * 聊天流投影渲染项：
 * 将扁平消息流投影为包含可见消息与折叠控件的渲染结构。
 */
sealed interface ChatRenderItem {
    val stableKey: String

    data class MessageItem(
        val message: HarnessMessage,
        /** 工具卡是否展示其思考链：投影时按「前文是否已出现过相同 reasoning」预计算，
         *  避免列表项重组时对整个消息流做 O(n) 回扫；非 ToolCall 消息无意义，恒为 true。 */
        val showReasoning: Boolean = true,
    ) : ChatRenderItem {
        override val stableKey: String get() = message.id
    }

    data class CollapseButtonItem(
        val roundKey: String,
        val hiddenSteps: Int,
        val totalSteps: Int,
        val hiddenDurationMs: Long,
        val isExpanded: Boolean,
    ) : ChatRenderItem {
        override val stableKey: String get() = "collapse_btn_$roundKey"
    }
}

/**
 * 纯函数投影：将原始消息列表按自然清晰的单行流投影为 LazyColumn 的渲染项。
 *
 * @param messages 原始 Harness 消息流
 * @param toolResults 工具执行结果映射表（用于提取 durationMs）
 * @param expandedOverrides 手动展开/收起记忆表
 */
fun projectChatMessages(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult> = emptyMap(),
    expandedOverrides: Map<String, Boolean> = emptyMap(),
): List<ChatRenderItem> {
    if (messages.isEmpty()) return emptyList()

    // 过滤掉已被 ToolCard 内部独立消费渲染的 ToolResult，所有思考过程与工具调用按自然单行流呈现。
    // 工具卡的思考链可见性（前文去重）单趟完成：向后回扫的写法在工具密集的一轮里是平方级，
    // 而本函数每次流式增量都会重跑，长对话下正是卡顿来源。语义保持一致——
    // 只在同一轮（遇 UserMessage 截断）内出现过相同 reasoning 才判为已展示。
    val shownReasoning = HashSet<String>()
    val items = ArrayList<ChatRenderItem>(messages.size)
    for (message in messages) {
        when {
            message is ToolResult -> Unit
            message is ToolCall -> {
                val reasoning = message.reasoning
                val show = reasoning != null && shownReasoning.add(reasoning)
                items.add(ChatRenderItem.MessageItem(message, show))
            }
            message is UserMessage -> {
                shownReasoning.clear()
                items.add(ChatRenderItem.MessageItem(message, true))
            }
            message is AssistantText -> {
                // assistant 正文同气泡展示的 reasoning 也计入同轮已见集合：
                // 其后的工具卡若重复相同 reasoning，应被前文去重隐藏（自身 showReasoning 恒 true）。
                message.reasoning?.let { shownReasoning.add(it) }
                items.add(ChatRenderItem.MessageItem(message, true))
            }
            else -> items.add(ChatRenderItem.MessageItem(message, true))
        }
    }
    return items
}

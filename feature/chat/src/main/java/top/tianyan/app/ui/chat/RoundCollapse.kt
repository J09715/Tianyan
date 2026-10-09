package top.tianyan.app.ui.chat

import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolResult

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
    // 工具卡的思考链可见性（前文去重）在投影时一次性预计算进 MessageItem，
    // 列表项重组时不再对整个 messages 做 indexOfFirst + 回扫两次 O(n)。
    return messages
        .mapIndexedNotNull { index, message ->
            if (message is ToolResult) return@mapIndexedNotNull null
            val showReasoning = if (message is ToolCall) {
                message.reasoning != null && !reasoningAlreadyShown(messages, index, message.reasoning)
            } else {
                true
            }
            ChatRenderItem.MessageItem(message, showReasoning)
        }
}

package top.tianyan.app.ui.chat

import top.tianyan.app.harness.*
import top.tianyan.app.harness.effects.ToolRecoveryNotice

internal data class ToolStepsItem(val steps: List<ChatRenderItem>) : ChatRenderItem {
    // Anchor to the first tool call so streaming appends do not reset expansion or scroll position.
    override val stableKey: String
        get() = "tool_steps_${steps.firstNotNullOfOrNull { it.toolCallId } ?: steps.first().stableKey}"

    /** 链里可以夹着思考步骤，但标题只统计真正的工具调用。 */
    val toolCount: Int get() = steps.count { it.toolCallId != null }
}

internal val ChatRenderItem.toolCallId: String? get() = ((this as? ChatRenderItem.MessageItem)?.message as? ToolCall)?.id

/** 步骤对应原始消息 id：工具调用取 call id，思考取所属助手消息 id。 */
internal val ChatRenderItem.stepId: String?
    get() = when (this) {
        is ChatRenderItem.MessageItem -> message.id
        is ChatRenderItem.ReasoningItem -> messageId
        else -> null
    }

/** 步骤在原始消息列表中的下标：工具取调用消息，思考取所属助手消息。 */
internal val ChatRenderItem.stepRawIndex: Int
    get() = when (this) {
        is ChatRenderItem.MessageItem -> rawIndex
        is ChatRenderItem.ReasoningItem -> rawIndex
        else -> -1
    }

internal fun projectChatTimeline(
    messages: List<HarnessMessage>, toolResults: Map<String, ToolResult> = emptyMap(),
    expandedOverrides: Map<String, Boolean> = emptyMap(), collapseEnabled: Boolean = false,
    revealLimits: Map<String, Int> = emptyMap(),
): List<ChatRenderItem> =
    groupToolSteps(splitReasoning(projectChatMessages(messages, toolResults, expandedOverrides, collapseEnabled, revealLimits)))

/**
 * 把带推理的助手消息拆成「思考步骤 + 正文」。
 * 思考作为过程链的一步随工具调用一起展开/收起；正文仍留在原位置，且不再重复渲染思考。
 * 纯思考占位的流式气泡没有正文，只保留思考步骤。
 */
private fun splitReasoning(items: List<ChatRenderItem>): List<ChatRenderItem> = buildList {
    items.forEach { item ->
        val message = (item as? ChatRenderItem.MessageItem)?.message
        val reasoning = (message as? AssistantText)?.reasoning
        if (message is AssistantText && !reasoning.isNullOrBlank()) {
            add(ChatRenderItem.ReasoningItem(message.id, reasoning, message.reasoningMs, item.stepRawIndex))
            if (message.text.isNotBlank()) add(item.copy(showReasoning = false))
        } else {
            add(item)
        }
    }
}

/**
 * 相邻的思考与普通工具调用合成一条过程链。
 * 文本、交互界面和子智能体卡片保持原顺序并切断链；只有思考、没有工具调用的分组不成链。
 */
internal fun groupToolSteps(items: List<ChatRenderItem>): List<ChatRenderItem> = buildList {
    val pending = mutableListOf<ChatRenderItem>()
    fun flush() {
        if (pending.isEmpty()) return
        if (pending.none { it.isChainToolStep }) addAll(pending) else add(ToolStepsItem(pending.toList()))
        pending.clear()
    }
    items.forEach { item ->
        if (item is ChatRenderItem.ReasoningItem || item.isChainToolStep) {
            pending += item
        } else {
            flush()
            add(item)
        }
    }
    flush()
}

private val ChatRenderItem.isChainToolStep: Boolean
    get() {
        val call = (this as? ChatRenderItem.MessageItem)?.message as? ToolCall ?: return false
        return call.tool != HarnessTool.SUBAGENT && call.tool != HarnessTool.RENDER_SURFACE
    }

internal fun ChatRenderItem.containsMessage(id: String): Boolean = when (this) {
    is ChatRenderItem.MessageItem -> message.id == id
    is ChatRenderItem.ReasoningItem -> messageId == id
    is ToolStepsItem -> steps.any { it.containsMessage(id) }
    is ChatRenderItem.CollapseButtonItem -> false
}

internal fun focusedRoundKey(messages: List<HarnessMessage>, id: String?): String? {
    if (id == null) return null
    var roundKey = "__initial_round__"
    messages.forEach {
        if (it is UserMessage) roundKey = it.id
        if (it.id == id) return roundKey
    }
    return null
}

internal enum class ToolStepState { RUNNING, WAITING, DEFERRED, SUCCEEDED, FAILED, UNKNOWN, INCOMPLETE }

internal fun toolStepState(result: ToolResult?, running: Boolean): ToolStepState = when {
    result?.awaitingApproval == true -> ToolStepState.WAITING
    result?.approvalDeferred == true -> ToolStepState.DEFERRED
    result == null -> if (running) ToolStepState.RUNNING else ToolStepState.INCOMPLETE
    result.errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN -> ToolStepState.UNKNOWN
    result.success -> ToolStepState.SUCCEEDED
    else -> ToolStepState.FAILED
}

/** Show a bounded tail of one continuous chain, plus a focused step and the latest issue. */
internal fun visibleToolChainSteps(steps: List<ChatRenderItem>, results: Map<String, ToolResult>,
    running: Boolean, limit: Int = 8, focusedId: String? = null): List<ChatRenderItem> {
    val attention = steps.lastOrNull {
        val id = it.toolCallId ?: return@lastOrNull false
        toolStepState(results[id], running) in setOf(ToolStepState.FAILED, ToolStepState.UNKNOWN,
            ToolStepState.WAITING, ToolStepState.DEFERRED)
    }
    val ids = steps.takeLast(limit.coerceAtLeast(1)).mapNotNull { it.stepId }.toSet() +
        listOfNotNull(attention?.stepId, focusedId)
    return steps.filter { it.stepId in ids }
}

/** Final visible prose per user turn gets the full toolbar; intermediate updates keep a details menu. */
internal fun assistantToolbarIds(messages: List<HarnessMessage>, running: Boolean): Set<String> = buildSet {
    var candidate: String? = null
    messages.forEach { message ->
        if (message is UserMessage) {
            candidate?.let(::add)
            candidate = null
        } else if (message is AssistantText && message.text.isNotBlank()) candidate = message.id
    }
    if (!running) candidate?.let(::add)
}

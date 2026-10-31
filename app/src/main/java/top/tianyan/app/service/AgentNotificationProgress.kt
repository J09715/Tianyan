package top.tianyan.app.service

import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

internal val NOTIFIABLE_AGENT_STATES = setOf(SessionRunState.RUNNING, SessionRunState.WAITING_APPROVAL)

/** Reuses execution status and tool counters without copying model or tool-result bodies. */
internal fun agentNotificationProgress(state: SessionRunState, status: String?, messages: List<HarnessMessage>): String {
    val current = messages.drop((messages.indexOfLast { it is UserMessage }).coerceAtLeast(0))
    val calls = current.filterIsInstance<ToolCall>().distinctBy { it.id }
    val results = current.filterIsInstance<ToolResult>().filterNot { it.awaitingApproval }.associateBy { it.toolCallId }
    val done = calls.count { it.id in results }
    val phase = if (state == SessionRunState.WAITING_APPROVAL) "等待用户批准"
        else status?.takeIf { it.isNotBlank() }?.take(240) ?: "等待模型响应"
    val active = calls.lastOrNull { it.id !in results }
    return buildList {
        add(phase)
        if (calls.isNotEmpty()) add("工具步骤：已完成 $done / 已发起 ${calls.size}")
        if (state == SessionRunState.RUNNING && active != null) {
            add("当前工具：${(active.rawToolName ?: active.tool.name).take(80)}")
        }
    }.joinToString("\n")
}

package top.tianyan.app.harness

internal enum class HistoryProtection { CURRENT_TURN, RECENT_RESULT, UNANSWERED, AWAITING_APPROVAL, DEFERRED_APPROVAL, FAILED }

/** Shared eligibility reasons for historical arguments and assembly diagnostics. */
internal object HistoryProtectionPolicy {
    fun reasons(messages: List<HarnessMessage>): Map<String, HistoryProtection> {
        val lastUser = messages.indexOfLast { it is UserMessage }
        val results = messages.filterIsInstance<ToolResult>()
        val latest = results.associateBy { it.toolCallId }
        val recent = results.takeLast(ContextWindowPolicy.KEEP_RECENT_TOOL_RESULTS).mapTo(hashSetOf()) { it.toolCallId }
        val active = messages.drop(lastUser + 1).filterIsInstance<ToolResult>().mapTo(hashSetOf()) { it.toolCallId }
        return buildMap {
            messages.forEachIndexed { index, message ->
                val reason = when {
                    lastUser < 0 || index >= lastUser -> HistoryProtection.CURRENT_TURN
                    message is ToolResult && message.toolCallId in recent -> HistoryProtection.RECENT_RESULT
                    message is ToolCall -> {
                        val result = latest[message.id]
                        when {
                            result == null -> HistoryProtection.UNANSWERED
                            result.awaitingApproval -> HistoryProtection.AWAITING_APPROVAL
                            result.approvalDeferred -> HistoryProtection.DEFERRED_APPROVAL
                            !result.success -> HistoryProtection.FAILED
                            message.id in recent -> HistoryProtection.RECENT_RESULT
                            message.id in active -> HistoryProtection.CURRENT_TURN
                            else -> null
                        }
                    }
                    else -> null
                }
                if (reason != null) put(message.id, reason)
            }
        }
    }
}

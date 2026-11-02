package top.tianyan.app.ui.chat

import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage
import java.util.Locale

internal fun reasoningAlreadyShown(messages: List<HarnessMessage>, index: Int, reasoning: String?): Boolean {
    if (reasoning == null) return false
    var i = index - 1
    while (i >= 0) {
        val m = messages[i]
        if (m is UserMessage) break
        if (m is AssistantText && m.reasoning == reasoning) return true
        if (m is ToolCall && m.reasoning == reasoning) return true
        i--
    }
    return false
}

internal fun formatChatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return when {
        totalSeconds < 10 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        totalSeconds < 60 -> "${totalSeconds}s"
        else -> "${totalSeconds / 60}m${totalSeconds % 60}s"
    }
}

/** Token 计数紧凑格式：1234 → 1.2k，用于气泡底部的用量明细。 */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 10_000 -> String.format(Locale.US, "%.1fk", tokens / 1000f)
    tokens >= 1_000 -> String.format(Locale.US, "%.2fk", tokens / 1000f)
    else -> tokens.toString()
}

internal fun formatContextWindow(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

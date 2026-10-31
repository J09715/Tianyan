package top.tianyan.app.ui.bot

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType

/** 与参考实现一致：同作者 5 分钟内的消息合并为一组，间隔 30 分钟插入时间分隔。 */
internal data class ChatEntry(
    val message: GroupMessage,
    val showSeparator: Boolean,
    val firstInGroup: Boolean,
    val lastInGroup: Boolean,
)

internal fun buildChatEntries(messages: List<GroupMessage>): List<ChatEntry> {
    if (messages.isEmpty()) return emptyList()
    val entries = ArrayList<ChatEntry>(messages.size)
    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val gap = if (previous == null) Long.MAX_VALUE else message.timestamp - previous.timestamp
        val separated = previous == null || gap >= SEPARATOR_GAP_MS ||
            !sameLocalDay(previous.timestamp, message.timestamp)
        val joins = previous != null && !separated &&
            message.senderType != GroupSenderType.SYSTEM &&
            previous.senderType == message.senderType &&
            previous.senderId == message.senderId &&
            gap < GROUP_GAP_MS
        if (joins) entries[entries.lastIndex] = entries.last().copy(lastInGroup = false)
        entries += ChatEntry(message, separated, !joins, true)
    }
    return entries
}

internal fun separatorLabel(timestamp: Long, yesterday: String): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(timestamp).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(at)
    return when (at.toLocalDate()) {
        today -> clock
        today.minusDays(1) -> "$yesterday $clock"
        else -> {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timestamp))
            "$date $clock"
        }
    }
}

/**
 * 未读数从锚点之后开始统计，且排除用户自己的消息。
 * 锚点消息已不存在（清空/删除）时返回 0，避免把整段历史重新算成未读。
 */
internal fun countUnseen(messages: List<GroupMessage>, anchorId: String?): Int {
    if (anchorId == null) return 0
    val anchorIndex = messages.indexOfLast { it.id == anchorId }
    if (anchorIndex < 0) return 0
    return messages.drop(anchorIndex + 1).count { it.senderType != GroupSenderType.USER }
}

private fun sameLocalDay(first: Long, second: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
}

private const val GROUP_GAP_MS = 5 * 60 * 1000L
private const val SEPARATOR_GAP_MS = 30 * 60 * 1000L

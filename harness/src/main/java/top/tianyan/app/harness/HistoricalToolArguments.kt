package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request projection only. Original arguments remain in the session tree and must never be replayed from this view. */
internal object HistoricalToolArguments {
    const val LARGE_VALUE_CHARS = 2_048

    fun project(messages: List<HarnessMessage>): List<HarnessMessage> {
        val lastUser = messages.indexOfLast { it is UserMessage }
        if (lastUser < 0) return messages
        val protections = HistoryProtectionPolicy.reasons(messages)
        var changed = false
        val projected = messages.mapIndexed { index, message ->
            if (index >= lastUser || message !is ToolCall || message.id in protections) return@mapIndexed message
            val fields = when (message.tool) {
                HarnessTool.WRITE -> setOf("content")
                HarnessTool.EDIT -> setOf("oldText", "newText")
                else -> return@mapIndexed message
            }
            val args = message.args.toMutableMap()
            var modified = false
            for (field in fields) {
                val value = args[field] as? JsonPrimitive ?: continue
                if (!value.isString || value.content.length <= LARGE_VALUE_CHARS) continue
                val head = value.content.take(256).dropLastWhile { it.isHighSurrogate() }
                val tail = value.content.takeLast(128).dropWhile { it.isLowSurrogate() }
                args[field] = JsonPrimitive(head +
                    "\n[历史参数已缩减，原长 ${value.content.length} UTF-16 字符；仅供参考，不可重放。" +
                    "原文：history_read(message_id=\"${message.id}\")]\n" + tail)
                modified = true
            }
            if (!modified) message else {
                changed = true
                message.copy(args = JsonObject(args))
            }
        }
        return if (changed) projected else messages
    }
}

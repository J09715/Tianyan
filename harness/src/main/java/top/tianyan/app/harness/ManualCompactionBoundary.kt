package top.tianyan.app.harness

/** 按用户轮次保留尾部；跨用户消息返回的工具结果也必须与原调用一起保留。0 表示无法安全折叠。 */
internal fun manualCompactionBoundary(messages: List<HarnessMessage>, keepRecent: Int): Int {
    val target = (messages.size - keepRecent.coerceAtLeast(1)).coerceAtLeast(0)
    fun userBoundary(at: Int): Int =
        (minOf(at, messages.lastIndex) downTo 0).firstOrNull { messages[it] is UserMessage } ?: 0

    var boundary = userBoundary(target)
    val callIndexes = messages.withIndex().filter { it.value is ToolCall }.associate { it.value.id to it.index }
    val resultIndexes = messages.withIndex().mapNotNull { (index, message) ->
        (message as? ToolResult)?.let { it.toolCallId to index }
    }.toMap()
    while (boundary > 0) {
        // 结果尚未返回的调用也要保留，否则随后落库的结果会失去原调用。
        val crossingCall = callIndexes.filter { (id, index) ->
            index < boundary && (resultIndexes[id] ?: Int.MAX_VALUE) >= boundary
        }.values.minOrNull()
            ?: break
        boundary = userBoundary(crossingCall)
    }
    return boundary
}

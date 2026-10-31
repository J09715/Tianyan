package top.tianyan.app.harness

/**
 * 消息展示用的输入总量，包含缓存读取和写入；不改变协议原始用量及账本口径。
 * OpenAI / Responses 的 input 已包含缓存，Anthropic 的 input 则只包含未缓存输入。
 */
fun ChatUsage.messagePromptTokens(protocol: ApiProtocol): Int? {
    val maxTokens = Int.MAX_VALUE.toLong()
    var total = inputTokens.coerceIn(0, maxTokens)
    if (protocol == ApiProtocol.ANTHROPIC) {
        total += cacheReadTokens.coerceIn(0, maxTokens)
        total += cacheWriteTokens.coerceIn(0, maxTokens)
    }
    return total.coerceAtMost(maxTokens).takeIf { it > 0 }?.toInt()
}

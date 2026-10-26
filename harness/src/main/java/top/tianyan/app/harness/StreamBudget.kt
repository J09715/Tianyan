package top.tianyan.app.harness

/**
 * 单轮流式响应的硬失败预算：跨协议累计正文 / 思考 / 工具入参字符数，超限立即中断本轮流式请求。
 *
 * 与 [StreamBuffer] 的静默截断语义不同：正文与工具调用协议依赖完整原文，截断会拼出残缺
 * JSON 或半截正文污染后续上下文。一旦上游产量超过合理上限（网关重复重放、提示注入导致的
 * 无界输出、被错误地把整段文件塞进 arguments），继续累积只会撑爆堆内存，因此这里直接抛
 * [StreamBudgetExceededException] 终止请求，由 runner 以 Failed 终态返回——绝不重发，
 * 重发只会再次产出一模一样的内容。
 */
internal class StreamBudgetExceededException(message: String) : IllegalStateException(message)

/** 累计流式输出字符数并在超限时抛出 [StreamBudgetExceededException]。非线程安全，仅单请求协程内使用。 */
internal class StreamBudget(private val maxChars: Int = ProviderClient.MAX_STREAM_CHARS) {
    private var total = 0

    /** 计入一段增量的字符数；累计超过上限时终止本轮请求。 */
    fun add(chars: Int) {
        if (chars <= 0) return
        total += chars
        if (total > maxChars) {
            throw StreamBudgetExceededException(
                "模型流式响应已超过 $maxChars 字符上限（累计约 $total），已中断本轮请求以避免内存耗尽。",
            )
        }
    }

    fun add(text: String) = add(text.length)
}
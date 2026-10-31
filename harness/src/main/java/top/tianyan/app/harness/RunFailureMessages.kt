package top.tianyan.app.harness

/** 首个响应超时文案的稳定前缀。带此前缀的消息不再套「执行失败 / 执行异常」。 */
internal const val FIRST_RESPONSE_TIMEOUT_MARK = "⏱"

internal fun presentRunFailure(message: String): String =
    if (message.startsWith(FIRST_RESPONSE_TIMEOUT_MARK)) message else "❌ 执行失败：$message"

internal fun presentExecutionFault(message: String): String =
    if (message.startsWith(FIRST_RESPONSE_TIMEOUT_MARK)) message else "❌ 执行异常：$message"

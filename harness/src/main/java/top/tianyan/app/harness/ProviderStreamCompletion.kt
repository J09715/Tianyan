package top.tianyan.app.harness

import java.io.EOFException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Protocol completion is required before partial text or tool arguments can be accepted. */
internal fun requireStreamCompletion(completed: Boolean, protocol: String) {
    if (!completed) throw EOFException("$protocol 流式响应提前结束，未收到完成事件；已生成内容不代表任务完成")
}

/** Never turn an exhausted input window into a nominally valid one-token request. */
internal fun requireOutputHeadroom(available: Int, requested: Int) {
    if (available < minOf(requested, 256)) {
        throw LlmContextOverflowException("上下文剩余输出空间不足（剩余 $available tokens，请求输出 $requested tokens），需要压缩历史")
    }
}

internal fun requireOutputCompletion(reason: String?, inputTokens: Int = 0, outputChars: Int = Int.MAX_VALUE) {
    if (reason in setOf("length", "max_tokens", "max_output_tokens")) {
        if (inputTokens >= 64_000 && outputChars <= 2) {
            throw LlmContextOverflowException("大上下文请求输出仅 $outputChars 个字符即达到 token 上限（$reason）")
        }
        throw LlmOutputLimitException("模型输出达到 token 上限（$reason），回复尚未完成。请增加输出预算后重试")
    }
}

/** SSE errors use HTTP 200; retain recoverable overflow and transient server classifications. */
internal fun providerStreamError(body: String): Exception {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = root?.get("error") as? JsonObject ?: root
    val kinds = listOf("code", "type").mapNotNull { key ->
        error?.get(key)?.let { it as? JsonPrimitive }?.contentOrNull?.lowercase()
    }
    val classified = when {
        kinds.any { it in setOf("rate_limit_exceeded", "rate_limit_error", "rate_limit", "429",
            "insufficient_quota", "quota_exceeded", "quota_exhausted", "resource_exhausted") } ->
            ProviderClient.rateLimitException(429, body, null)
        kinds.any { it in setOf("overloaded_error", "server_error", "api_error", "internal_server_error") } ->
            ProviderClient.transientHttpException(503, body, null)
        else -> ProviderClient.contextOverflowException(400, body)
    }
    val message = "上游流内错误：" + (error?.get("message")?.let {
        it as? JsonPrimitive
    }?.contentOrNull ?: body).take(300)
    return when (classified) {
        is LlmRateLimitException -> LlmRateLimitException(message, classified.retryAfterSeconds,
            classified.quotaExhausted, allowImmediateKeyRotation = false)
        is TransientHttpException -> TransientHttpException(message, classified.httpCode, classified.retryAfterSeconds)
        is LlmContextOverflowException -> LlmContextOverflowException(message)
        is LlmInvalidOutputTokensException -> LlmInvalidOutputTokensException(message)
        else -> IllegalStateException(message)
    }
}

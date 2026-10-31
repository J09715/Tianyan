package top.tianyan.app.harness

import java.io.IOException

/** HTTP 429: distinguish temporary throttling from exhausted account quota. */
class LlmRateLimitException(
    message: String,
    val retryAfterSeconds: Long? = null,
    val quotaExhausted: Boolean = false,
    /** A started SSE stream must return to the turn runner to clear partial output before replay. */
    val allowImmediateKeyRotation: Boolean = true,
) : IOException(message)

/** Temporary upstream failure: retry without reducing context. */
class TransientHttpException(
    message: String,
    val httpCode: Int,
    val retryAfterSeconds: Long? = null,
) : IOException(message)

/** Input context or payload overflow: mechanically compact history before replay. */
class LlmContextOverflowException(message: String) : IOException(message)

/** Provider rejected the requested output-token parameter, not the input context. */
class LlmInvalidOutputTokensException(message: String) : IOException(message)

/** A completed transport with truncated text/tool arguments; never a successful turn. */
class LlmOutputLimitException(message: String) : IllegalStateException(message)

/** HTTP 200 without useful text, reasoning or tool calls must not complete the turn. */
class LlmEmptyResponseException(message: String) : IOException(message)

/**
 * 本机在收到任何模型事件或心跳之前主动停止等待。
 * 不是上游 HTTP 错误，也不按断线重试。
 */
class FirstResponseTimeoutException(val waitedMs: Long) : IOException(fallbackMessage(waitedMs)) {
    companion object {
        fun fallbackMessage(waitedMs: Long): String {
            val seconds = (waitedMs / 1_000L).coerceAtLeast(1)
            return "$FIRST_RESPONSE_TIMEOUT_MARK 等待模型首个响应已超过 $seconds 秒。" +
                "这是本机等待上限，上游没有返回错误。" +
                "可在「Agent 执行与上下文」中调高或关闭「等待模型首个响应」后重试。"
        }
    }
}

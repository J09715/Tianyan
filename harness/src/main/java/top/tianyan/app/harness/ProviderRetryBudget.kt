package top.tianyan.app.harness

import java.io.EOFException
import java.net.SocketException
import java.io.InterruptedIOException

internal const val LARGE_REQUEST_TOKEN_THRESHOLD = 64_000
internal const val LARGE_REQUEST_MAX_RETRIES = 1

/** 瞬态故障（断线 / 5xx 等）的保底重试次数，不受大上下文降级影响。 */
internal const val TRANSIENT_MAX_RETRIES = 3

private const val EMERGENCY_FOLD_RATIO_PERCENT = 25
private const val EMERGENCY_FOLD_MIN_BUDGET = 8_000
private const val DEFAULT_CONTEXT_BUDGET_TOKENS = 128_000

internal fun providerEmergencyFoldBudget(model: ModelConfig): Int =
    (ContextWindowPolicy.clampedBudget(
        model.contextTokens,
        DEFAULT_CONTEXT_BUDGET_TOKENS,
        modelId = model.model,
        providerId = model.provider,
    ) * EMERGENCY_FOLD_RATIO_PERCENT / 100)
        .coerceAtLeast(EMERGENCY_FOLD_MIN_BUDGET)

internal fun providerMaxNetworkRetriesFor(estimatedRequestTokens: Int, configuredRetries: Int): Int =
    if (estimatedRequestTokens >= LARGE_REQUEST_TOKEN_THRESHOLD) {
        minOf(configuredRetries, LARGE_REQUEST_MAX_RETRIES)
    } else {
        configuredRetries
    }

/**
 * 实际重试预算 = 大上下文降级后的预算，但瞬态故障（断线 / 超时 / TLS 中断 / 上游 5xx）
 * 至少保留 [TRANSIENT_MAX_RETRIES] 次，不被降级到 1 次。
 * 首个响应看门狗超时不是上游故障，不计入瞬态重试。
 */
internal fun providerEffectiveRetryBudget(largeContextRetries: Int, throwable: Throwable): Int =
    if (providerIsTransientFailure(throwable)) {
        maxOf(largeContextRetries, TRANSIENT_MAX_RETRIES)
    } else {
        largeContextRetries
    }

/** 可原样重发的瞬态故障。首个响应超时沿 cause 链出现时一律排除。 */
internal fun providerIsTransientFailure(throwable: Throwable): Boolean {
    if (throwable.firstResponseTimeoutOrNull() != null) return false
    var cause: Throwable? = throwable
    var depth = 0
    while (cause != null && depth < 10) {
        if (cause is SocketException ||
            cause is InterruptedIOException ||
            cause is javax.net.ssl.SSLException ||
            cause is EOFException ||
            cause is TransientHttpException
        ) {
            return true
        }
        cause = cause.cause
        depth++
    }
    return false
}

internal fun Throwable.firstResponseTimeoutOrNull(): FirstResponseTimeoutException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        if (current is FirstResponseTimeoutException) return current
        current = current.cause
        depth++
    }
    return null
}

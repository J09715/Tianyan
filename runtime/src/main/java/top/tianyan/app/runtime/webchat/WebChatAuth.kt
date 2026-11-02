package top.tianyan.app.runtime.webchat

import java.util.concurrent.ConcurrentHashMap

/** 配对成功后写入的会话 Cookie 名：EventSource / <a download> 无法自定义头，只能靠同源 Cookie。 */
internal const val WEBCHAT_TOKEN_COOKIE = "tianyan_webchat_token"

/**
 * 配对码（PIN）失败计数 + 指数退避锁out，服务端内存实现。
 *
 * 6 位 PIN 只有 10^6 空间：无锁定时同 Wi-Fi 内可并发枚举（审计 P0-2）。
 * 按来源地址计数，连续 5 次失败锁 5 分钟，之后每再失败 5 次锁定翻倍（上限 80 分钟）；
 * 成功配对立即清零。锁定期返回 429 而不是 401，避免把「已锁定」暴露成「口令错误」。
 */
internal class WebChatPinGuard(
    private val failuresPerLock: Int = 5,
    private val baseLockMs: Long = 5 * 60_000L,
) {
    private class State(var failures: Int, var lockedUntil: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun lockRemainingMs(key: String): Long = states[key]?.let {
        (it.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0)
    } ?: 0

    fun recordFailure(key: String) {
        states.compute(key) { _, old ->
            val failures = (old?.failures ?: 0) + 1
            val rounds = (failures / failuresPerLock - 1).coerceAtMost(4)
            val lockMs = if (failures < failuresPerLock) 0 else baseLockMs shl rounds
            State(failures, System.currentTimeMillis() + lockMs)
        }
    }

    fun clear(key: String) {
        states.remove(key)
    }
}

internal fun AndroidHttpExchange.clientKey(): String = remoteAddress.ifBlank { "unknown" }

/**
 * 只接受 `Authorization: Bearer <PIN>` 或配对后下发的 HttpOnly Cookie；
 * **不再接受 `?token=`**（会进访问日志/Referer/历史记录，且可被同机任意页面嗅探）。
 */
internal fun AndroidHttpExchange.authToken(): String? =
    requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
        ?: requestHeaders.getFirst("Cookie")
            ?.split(';')
            ?.firstOrNull { it.trim().startsWith("$WEBCHAT_TOKEN_COOKIE=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

internal fun AndroidHttpExchange.markAuthenticated(token: String) {
    responseHeaders.add("Set-Cookie", "$WEBCHAT_TOKEN_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
}

/**
 * 校验一次 PIN 尝试：0=通过；正数=锁定剩余毫秒；-1=配对码错误（已计数）。
 * 锁定优先于比较，锁定期间不再消耗比较预算。
 */
internal fun AndroidHttpExchange.admitPinAttempt(guard: WebChatPinGuard, pin: String): Long {
    val key = clientKey()
    val remaining = guard.lockRemainingMs(key)
    if (remaining > 0) return remaining
    if (authToken() == pin) {
        guard.clear(key)
        return 0
    }
    guard.recordFailure(key)
    return -1
}

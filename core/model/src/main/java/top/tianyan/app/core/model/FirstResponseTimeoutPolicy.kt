package top.tianyan.app.core.model

/**
 * 客户端等待模型首个响应（或心跳）的上限。
 *
 * 这是本机看门狗，不是上游错误码。缺省 90 秒，与历史行为一致；
 * [DISABLED] 表示不再用这项上限主动断开，连接本身的失败仍会结束等待。
 */
object FirstResponseTimeoutPolicy {
    const val DEFAULT_SECONDS = 90
    const val MIN_ENABLED_SECONDS = 90
    const val MAX_SECONDS = 600
    const val DISABLED = 0
    const val DEFAULT_TIMEOUT_MS = DEFAULT_SECONDS * 1_000L

    /** 关闭看门狗时使用的等待值：不会在正常运行时间内触发。 */
    const val UNLIMITED_TIMEOUT_MS = Long.MAX_VALUE

    /** 未设置时回到默认 90 秒；0 与负数表示关闭；其余钳制到可调区间。 */
    fun normalizeSeconds(raw: Int?): Int = when {
        raw == null -> DEFAULT_SECONDS
        raw <= DISABLED -> DISABLED
        else -> raw.coerceIn(MIN_ENABLED_SECONDS, MAX_SECONDS)
    }

    /** 0 表示关闭。正数是规范化后的毫秒。 */
    fun timeoutMs(seconds: Int): Long {
        val normalized = normalizeSeconds(seconds)
        return if (normalized == DISABLED) 0L else normalized * 1_000L
    }
}

package top.tianyan.app.harness

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import top.tianyan.app.core.model.FirstResponseTimeoutPolicy

/**
 * 首个模型事件（或 SSE 心跳）到达前的等待。
 *
 * 默认 90 秒；大请求与隐藏推理仍保留原来的更长下限。配置值只抬高下限，
 * 不会把原本更长的等待缩短。0 表示关闭这项看门狗。
 */
internal object FirstResponseWait {
    fun resolve(estimatedTokens: Int, reasoningEnabled: Boolean, configuredBaseMs: Long): Long {
        if (configuredBaseMs <= 0L) return FirstResponseTimeoutPolicy.UNLIMITED_TIMEOUT_MS
        return when {
            reasoningEnabled -> maxOf(REASONING_STREAM_WAIT_MS, configuredBaseMs)
            estimatedTokens > 80_000 -> maxOf(240_000L, configuredBaseMs)
            estimatedTokens > 40_000 -> maxOf(150_000L, configuredBaseMs)
            else -> configuredBaseMs
        }
    }
}

internal fun firstEventTimeoutMs(messages: List<ApiMessage>, model: ModelConfig): Long =
    ProviderClient.resolveFirstEventTimeoutMs(
        ProviderClient.estimateApiMessageTokens(messages),
        reasoningEnabled = model.reasoningMode != ReasoningMode.DISABLED,
        configuredBaseMs = model.firstResponseTimeoutMs,
    )

/** 关闭看门狗时直接返回，不取消仍在进行的请求。 */
internal suspend fun awaitFirstEventTimeout(timeoutMs: Long, state: AtomicInteger, cancel: () -> Unit) {
    if (timeoutMs <= 0L || timeoutMs == FirstResponseTimeoutPolicy.UNLIMITED_TIMEOUT_MS) return
    delay(timeoutMs)
    if (state.compareAndSet(ProviderClient.FIRST_EVENT_WAITING, ProviderClient.FIRST_EVENT_TIMED_OUT)) cancel()
}

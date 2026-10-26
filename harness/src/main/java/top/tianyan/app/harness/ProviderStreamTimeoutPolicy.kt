package top.tianyan.app.harness

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job

/** Reasoning may stay server-side with no visible deltas for several minutes. */
internal const val REASONING_STREAM_WAIT_MS = 10 * 60 * 1_000L

/** SSE comments are real transport activity even when the model exposes no reasoning text. */
internal fun observeStreamHeartbeat(line: String, state: AtomicInteger, watchdog: Job) {
    if (line.startsWith(":") && state.compareAndSet(
            ProviderClient.FIRST_EVENT_WAITING, ProviderClient.FIRST_EVENT_RECEIVED,
        )
    ) watchdog.cancel()
}

internal suspend fun observeStreamActivity(line: String, state: AtomicInteger, watchdog: Job) {
    observeStreamHeartbeat(line, state, watchdog)
    kotlinx.coroutines.currentCoroutineContext()[top.tianyan.app.harness.session.ProviderActivityContext]?.transport()
}

internal fun streamFailure(
    error: IOException,
    firstEventState: Int,
    firstWaitMs: Long,
    readWaitMs: Long,
): IOException {
    if (firstEventState == ProviderClient.FIRST_EVENT_TIMED_OUT) {
        return FirstResponseTimeoutException(firstWaitMs).apply { initCause(error) }
    }
    val detail = when {
        error is SocketTimeoutException && firstEventState == ProviderClient.FIRST_EVENT_RECEIVED ->
            "客户端连续 ${readWaitMs / 1_000}s 未收到流数据或心跳，已因读取超时关闭连接"
        error is SocketTimeoutException ->
            "模型首响应前连接或读取超时，尚未收到流事件或心跳"
        else -> return error
    }
    return SocketTimeoutException(detail).apply { initCause(error) }
}

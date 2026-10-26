package top.tianyan.app.harness

import kotlinx.coroutines.CancellationException

/** Local projection failures must never enter the provider network retry path. */
internal class StreamChunkHandlingException(message: String, cause: Throwable) : Exception(message, cause)

internal inline fun <T> withinStreamHandling(block: () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    throw StreamChunkHandlingException(throwable.message ?: "流式增量处理失败", throwable)
}

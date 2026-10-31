package top.tianyan.app.harness

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.Test

/**
 * 瞬态故障识别与重试预算：
 * 上游 5xx（TransientHttpException）必须与断线 / 读超时一样，不被大上下文降级压到 1 次。
 */
class HarnessTransientFailureTest {

    private fun transient(code: Int) = TransientHttpException("HTTP $code", code, null as Long?)

    @Test
    fun `upstream 5xx counts as transient`() {
        assertTrue(HarnessProviderRunner.isTransientFailure(transient(524)))
        assertTrue(HarnessProviderRunner.isTransientFailure(transient(503)))
    }

    @Test
    fun `connection failures count as transient`() {
        assertTrue(HarnessProviderRunner.isTransientFailure(SocketException("Connection abort")))
        assertTrue(HarnessProviderRunner.isTransientFailure(SocketTimeoutException("timeout")))
        assertTrue(HarnessProviderRunner.isTransientFailure(SSLException("TLS broken")))
        assertTrue(HarnessProviderRunner.isTransientFailure(EOFException("unexpected end of stream")))
    }

    @Test
    fun `wrapped cause is inspected`() {
        val wrapped = IOException("stream failed", SocketTimeoutException("read timeout"))
        assertTrue(HarnessProviderRunner.isTransientFailure(wrapped))
    }

    @Test
    fun `ordinary errors are not transient`() {
        assertFalse(HarnessProviderRunner.isTransientFailure(IllegalStateException("bad request")))
        assertFalse(HarnessProviderRunner.isTransientFailure(IOException("disk full")))
    }

    @Test
    fun `first response timeout is not retried as an upstream disconnect`() {
        val timeout = FirstResponseTimeoutException(90_000L)
        timeout.initCause(SocketException("canceled"))
        assertFalse(HarnessProviderRunner.isTransientFailure(timeout))
        assertEquals(1, HarnessProviderRunner.effectiveRetryBudget(1, timeout))
        val wrapped = IOException("stream closed", timeout)
        assertFalse(HarnessProviderRunner.isTransientFailure(wrapped))
    }

    @Test
    fun `self referencing cause terminates`() {
        // Java 禁止自引用 cause（initCause 自引用会抛 IllegalArgumentException），
        // 这里改用一个实际可达的环形链：a.cause = b，b.cause = a。
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(HarnessProviderRunner.isTransientFailure(a))
    }

    @Test
    fun `large context keeps transient retry budget`() {
        // 大上下文把预算降到 1；5xx 属瞬态，必须拉回 3
        val largeContextRetries = HarnessProviderRunner.maxNetworkRetriesFor(100_000, 3)
        assertEquals(1, largeContextRetries)
        assertEquals(3, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, transient(503)))
        assertEquals(3, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, SocketTimeoutException("t")))
    }

    @Test
    fun `large context does not raise non transient budget`() {
        val largeContextRetries = HarnessProviderRunner.maxNetworkRetriesFor(100_000, 3)
        assertEquals(1, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, IllegalStateException("x")))
    }

    @Test
    fun `transient never shrinks a richer configured budget`() {
        assertEquals(5, HarnessProviderRunner.effectiveRetryBudget(5, transient(500)))
        assertEquals(0, HarnessProviderRunner.effectiveRetryBudget(0, IllegalStateException("x")))
    }

    @Test
    fun `local stream handling failures are wrapped and never treated as retryable`() {
        val wrapped = runCatching {
            withinStreamHandling { throw IllegalStateException("db write failed") }
        }.exceptionOrNull()
        assertTrue(wrapped is StreamChunkHandlingException)
        // 即使本地处理包裹了 IO cause，也必须走本地终止分支，而不是网络重发分支
        val ioBacked = StreamChunkHandlingException("local io", IOException("disk full"))
        assertTrue(ioBacked.cause is IOException)
        assertFalse(HarnessProviderRunner.isTransientFailure(ioBacked.cause!!))
    }

    @Test
    fun `within stream handling rethrows cancellation untouched`() {
        val cancellation = CancellationException("user stop")
        val rethrown = runCatching { withinStreamHandling<Unit> { throw cancellation } }.exceptionOrNull()
        assertTrue(rethrown === cancellation)
    }
}

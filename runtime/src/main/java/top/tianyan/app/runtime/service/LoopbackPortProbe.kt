package top.tianyan.app.runtime.service

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 环回端口占用探测。
 *
 * 网关的「就绪」与「已放手」只能用同一个判据：能否连上 `127.0.0.1:<port>`。连接成功说明已有
 * 监听者 —— 至于是谁，由 [top.tianyan.app.runtime.shell.SandboxPortReclaimer] 按 procfs 事实判定。
 * 抽成独立类是为了让工具中心、页面来源与本地服务共用同一套超时节奏，也便于单元测试注入替身。
 */
class LoopbackPortProbe(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    /** 端口上已有监听者即为占用；探测本身的异常一律视为未占用。 */
    fun isBusy(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK_ADDRESS, port), connectTimeoutMs)
        }
        true
    }.getOrDefault(false)

    /** 等待端口真正放手；返回 false 表示超时后仍被占用。 */
    suspend fun awaitFree(
        port: Int,
        timeoutMs: Long,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ): Boolean {
        if (!isBusy(port)) return true
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            delay(pollIntervalMs)
            if (!isBusy(port)) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 250
        const val DEFAULT_POLL_INTERVAL_MS = 150L
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}

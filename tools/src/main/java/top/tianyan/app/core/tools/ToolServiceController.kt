package top.tianyan.app.core.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.shell.ManagedProcess
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/** Owns background-tool lifecycle and readiness probing independently from install transactions. */
class ToolServiceController(
    private val linuxRuntime: LinuxRuntime,
) {
    fun isRunning(toolId: String, spec: LocalServiceSpec?): Boolean =
        linuxRuntime.listBackground().any {
            it.toolId == toolId && it.session.isAlive && (spec == null || isPortOpen(spec.port))
        }

    suspend fun stop(toolId: String, spec: LocalServiceSpec? = null) {
        linuxRuntime.listBackground()
            .filter { it.toolId == toolId }
            .forEach { linuxRuntime.stopBackground(it.id) }
        if (spec != null) {
            val deadline = System.currentTimeMillis() + 2500L
            while (isPortOpen(spec.port) && System.currentTimeMillis() < deadline) {
                delay(150)
            }
        }
    }

    suspend fun restart(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess {
        stop(toolId, spec)
        return start(toolId, adapter, spec)
    }

    suspend fun start(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess {
        if (spec != null && isPortOpen(spec.port)) {
            val deadline = System.currentTimeMillis() + 2000L
            while (isPortOpen(spec.port) && System.currentTimeMillis() < deadline) {
                delay(150)
            }
        }
        val process = requireNotNull(adapter.startService()) { "工具不提供后台服务：$toolId" }
        if (spec != null) awaitPortOrThrow(toolId, process, spec)
        return process
    }

    fun observeLogs(toolId: String): Flow<List<String>> = linuxRuntime.observeBackgroundLogs(toolId)

    fun getLogs(toolId: String): List<String> = linuxRuntime.getBackgroundLogs(toolId)

    fun clearLogs(toolId: String) = linuxRuntime.clearBackgroundLogs(toolId)

    private suspend fun awaitPortOrThrow(
        toolId: String,
        process: ManagedProcess,
        spec: LocalServiceSpec,
    ) {
        val deadline = System.currentTimeMillis() + spec.startupTimeoutMs
        while (true) {
            coroutineContext.ensureActive()
            if (!process.session.isAlive) {
                val logs = linuxRuntime.getBackgroundLogs(toolId)
                    .filter { it.isNotBlank() }
                    .takeLast(10)
                    .joinToString("\n")
                linuxRuntime.stopBackground(process.id)
                val detail = if (logs.isNotBlank()) "：\n$logs" else ""
                throw IllegalStateException("网关进程启动后立即退出，服务日志$detail")
            }
            if (isPortOpen(spec.port)) return
            if (System.currentTimeMillis() > deadline) break
            delay(spec.pollIntervalMs)
        }
        val timeoutLogs = linuxRuntime.getBackgroundLogs(toolId)
            .filter { it.isNotBlank() }
            .takeLast(10)
            .joinToString("\n")
        linuxRuntime.stopBackground(process.id)
        val timeoutDetail = if (timeoutLogs.isNotBlank()) "：\n$timeoutLogs" else ""
        throw IllegalStateException(
            "网关未在 ${spec.startupTimeoutMs / 1000} 秒内就绪（端口 ${spec.port} 未监听），已自动停止$timeoutDetail",
        )
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val PORT_PROBE_TIMEOUT_MS = 250
    }
}

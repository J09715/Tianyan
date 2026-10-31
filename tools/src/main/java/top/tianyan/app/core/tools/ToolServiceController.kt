package top.tianyan.app.core.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.service.LoopbackPortProbe
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.service.ToolServiceAuthentication
import top.tianyan.app.runtime.service.ManagedWebServiceClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Owns background-tool lifecycle and readiness probing independently from install transactions.
 *
 * 生命周期按「实例」而不是「toolId」记账：启动成功时登记 [Instance]，停止只停登记的那个实例。
 * 这样排队中的 stop 不会杀掉刚起来的新实例，页面自建的同 toolId 实例也不会被工具中心误停；
 * 同一 toolId 的 start/stop/restart 由同一把互斥锁串行，后来者看到的一定是前一次操作完成后的状态。
 */
class ToolServiceController(
    private val linuxRuntime: LinuxRuntime,
    /** 网关服务的通知栏投影；为 null 时（单测 / 无通知环境）不贴通知。 */
    private val notifier: ServiceNotificationSink? = null,
) {
    private data class Instance(
        val process: ManagedProcess,
        val authentication: ToolServiceAuthentication? = null,
        /** 该实例的本地面板地址；用于崩溃/重启后补贴通知。 */
        val url: String? = null,
    )

    private val instances = ConcurrentHashMap<String, Instance>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val probe = LoopbackPortProbe()

    private fun activeDistro(): String = linuxRuntime.activeDistroId.value

    fun authentication(toolId: String, distroId: String): ToolServiceAuthentication? {
        val key = instanceKey(toolId, distroId)
        val entry = instances[key] ?: return null
        if (!isLive(entry.process)) {
            instances.remove(key, entry)
            return null
        }
        return entry.authentication
    }

    fun isRunning(toolId: String, spec: LocalServiceSpec?): Boolean {
        if (spec?.authenticated == true) return authentication(toolId, requireNotNull(spec.distroId)) != null
        val tracked = tracked(toolId, spec)
        if (tracked != null && isLive(tracked.process) && (spec == null || probe.isBusy(spec.port))) return true
        // 兜底：插件页面自建的同 toolId 实例没有控制器登记，仍按进程表判定。
        return linuxRuntime.listBackground().any {
            it.toolId == toolId && it.session.isAlive &&
                (spec?.distroId == null || it.distroId == spec.distroId) && (spec == null || probe.isBusy(spec.port))
        }
    }

    suspend fun stop(toolId: String, spec: LocalServiceSpec? = null) =
        lockFor(toolId, spec).withLock { stopLocked(toolId, spec) }

    suspend fun restart(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess = lockFor(toolId, spec).withLock {
        stopLocked(toolId, spec)
        startLocked(toolId, adapter, spec)
    }

    suspend fun start(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess = lockFor(toolId, spec).withLock { startLocked(toolId, adapter, spec) }

    fun observeLogs(toolId: String): Flow<List<String>> = linuxRuntime.observeBackgroundLogs(toolId)

    fun getLogs(toolId: String): List<String> = linuxRuntime.getBackgroundLogs(toolId)

    fun clearLogs(toolId: String) = linuxRuntime.clearBackgroundLogs(toolId)

    /**
     * 通知栏对账：已贴出但已不在运行的撤销；仍在运行却没贴出的补上（如 App 进程重启后网关仍存活，
     * 或用户在 Android 14 上划掉了常驻通知）。由 RuntimeForegroundService 的恢复循环调用。
     */
    suspend fun reconcileNotifications() {
        val notifier = this.notifier ?: return
        val active = activeDistro()
        val posted = notifier.serviceNotifications()
        posted.forEach { (distro, toolId) ->
            val running = distro == active && runCatching { isRunning(toolId, null) }.getOrDefault(false)
            if (!running) notifier.hideService(distro, toolId)
        }
        instances.values.forEach { entry ->
            val distro = entry.process.distroId ?: return@forEach
            val toolId = entry.process.toolId ?: return@forEach
            val url = entry.url ?: return@forEach
            if (distro != active || !isLive(entry.process) || (distro to toolId) in posted) return@forEach
            notifier.showService(distro, toolId, toolId, url)
        }
    }

    /** 运行时常驻服务被整体关闭（或通知栏「停止」）：网关通知一并撤销。 */
    fun hideNotifications() {
        notifier?.hideAllServices()
    }

    private fun announceStarted(toolId: String, distroId: String, url: String) {
        notifier?.showService(distroId, toolId, toolId, url)
    }

    private fun serviceUrl(spec: LocalServiceSpec) = "http://127.0.0.1:${spec.port}${spec.path}"

    private suspend fun startLocked(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess {
        val key = instanceKey(toolId, spec?.distroId)
        instances[key]?.let { existing ->
            // 已经在跑就直接返回同一实例：重复点击「启动」不再顺带重启一次。
            if (isLive(existing.process)) return existing.process
            instances.remove(key, existing)
        }
        if (spec?.authenticated == true) {
            check(linuxRuntime.supportsBackgroundInstanceStop) { "认证服务需要精确实例回收能力" }
            val distro = requireNotNull(spec.distroId)
            awaitPortFreeOrReclaim(spec)
            val launch = requireNotNull(adapter.serviceLaunch())
            check(launch.authenticated && launch.distroId == distro) { "服务启动身份已改变" }
            val auth = ToolServiceAuthentication()
            val process = linuxRuntime.startBackground(
                id = "authenticated-service:$distro:$toolId",
                command = ShellCommand(launch.launchCommand, environment = launch.environment, pluginIdentity = launch.pluginIdentity),
                toolId = toolId, distroId = distro,
            )
            try {
                process.session.write(auth.bootstrap())
                awaitPortOrThrow(toolId, process, spec)
                val client = ManagedWebServiceClient(
                    spec.port,
                    spec.path.trimEnd('/') + "/",
                    available = { process.session.isAlive },
                    authentication = auth,
                )
                try { check(awaitServiceIdentity(client, process)) { "服务身份验证失败" } } finally { client.close() }
                coroutineContext.ensureActive()
                instances[key] = Instance(process, auth, serviceUrl(spec))
                announceStarted(toolId, distro, serviceUrl(spec))
                return process
            } catch (failure: Throwable) {
                withContext(NonCancellable) { release(Instance(process, auth)) }
                throw failure
            }
        }
        if (spec != null) awaitPortFreeOrReclaim(spec)
        val process = requireNotNull(adapter.startService()) { "工具不提供后台服务：$toolId" }
        try {
            if (spec != null) awaitPortOrThrow(toolId, process, spec)
            coroutineContext.ensureActive()
            val url = spec?.let(::serviceUrl)
            instances[key] = Instance(process, url = url)
            if (spec != null && url != null) announceStarted(toolId, spec.distroId ?: activeDistro(), url)
            return process
        } catch (failure: Throwable) {
            withContext(NonCancellable) { release(Instance(process)) }
            throw failure
        }
    }

    private suspend fun stopLocked(toolId: String, spec: LocalServiceSpec?) {
        // 收尾失败不能让端口回收被跳过：先把实例停掉（或尽力停），再无条件确认端口交还。
        val failure = runCatching { stopInstancesLocked(toolId, spec) }.exceptionOrNull()
        if (spec != null) awaitPortReleased(spec)
        notifier?.hideService(spec?.distroId ?: activeDistro(), toolId)
        // 端口已交还就说明服务真的停了：此时收尾告警只当作噪声，不再让 UI 弹「停止失败」。
        if (failure != null && (spec == null || probe.isBusy(spec.port))) throw failure
    }

    private suspend fun stopInstancesLocked(toolId: String, spec: LocalServiceSpec?) {
        val tracked = trackedInstances(toolId, spec)
        if (tracked.isNotEmpty()) {
            tracked.forEach { (key, entry) ->
                instances.remove(key, entry)
                release(entry)
            }
        } else {
            // 没有控制器登记的实例（插件页面自建 / 上一代遗留）：按 toolId 精确回收登记表里匹配的进程。
            linuxRuntime.listBackground()
                .filter { it.toolId == toolId && (spec?.distroId == null || it.distroId == spec.distroId) }
                .forEach { process ->
                    if (linuxRuntime.supportsBackgroundInstanceStop) {
                        check(linuxRuntime.stopBackgroundInstance(process)) { "服务实例尚未释放" }
                    } else linuxRuntime.stopBackground(process.id)
                }
        }
    }

    /**
     * 停止后的端口交还确认。仍被占用则回收该端口上被遗弃的本运行时监听者：网关常自建进程组，
     * tracer 先死时 host/server 会被 reparent 到 init 继续监听，这个兜底比留下一个错误更有用。
     * 外部监听者不归我们管，留给下一次启动的排他检查去拒绝。
     */
    private suspend fun awaitPortReleased(spec: LocalServiceSpec) {
        if (probe.awaitFree(spec.port, PORT_RELEASE_TIMEOUT_MS)) return
        runCatching { linuxRuntime.reclaimListeningPort(spec.port) }
        probe.awaitFree(spec.port, PORT_RELEASE_TIMEOUT_MS)
    }

    private suspend fun release(entry: Instance) {
        val failure = runCatching { releaseProcess(entry) }.exceptionOrNull() ?: return
        // 进程树确实已经没了（例如整组/端口兜底已回收，只是某个流的 close 报了错）：
        // 清理告警不应否定「已停止」的结果，否则 UI 会显示一个误导性的失败。
        if (!entry.process.session.isAlive) return
        throw failure
    }

    private suspend fun releaseProcess(entry: Instance) {
        if (!linuxRuntime.supportsBackgroundInstanceStop) {
            linuxRuntime.stopBackground(entry.process.id)
            return
        }
        if (!linuxRuntime.stopBackgroundInstance(entry.process)) {
            check(!entry.process.session.isAlive) { "服务实例尚未释放" }
        }
    }

    private fun tracked(toolId: String, spec: LocalServiceSpec?): Instance? {
        val key = instanceKey(toolId, spec?.distroId)
        return instances[key]
    }

    private fun trackedInstances(toolId: String, spec: LocalServiceSpec?): Map<String, Instance> = instances
        .filterKeys { key ->
            key.endsWith(":$toolId") && (spec?.distroId == null || key == instanceKey(toolId, spec.distroId))
        }

    private fun isLive(process: ManagedProcess): Boolean =
        process.session.isAlive && linuxRuntime.listBackground().any { it === process }

    private fun instanceKey(toolId: String, distroId: String?) = "${distroId.orEmpty()}:$toolId"

    private fun lockFor(toolId: String, spec: LocalServiceSpec?) =
        locks.computeIfAbsent(instanceKey(toolId, spec?.distroId)) { Mutex() }

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
            if (probe.isBusy(spec.port)) return
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

    /**
     * 端口可连不等于面板路由/签名已经就绪：慢设备上先于路由就绪会被误判成身份失败、随即杀掉刚起来的
     * 实例，于是「启动→立刻停止→再启动」。这里给一个短的退避窗口，进程已退出则立即失败。
     */
    private suspend fun awaitServiceIdentity(client: ManagedWebServiceClient, process: ManagedProcess): Boolean {
        val deadline = System.currentTimeMillis() + IDENTITY_TIMEOUT_MS
        while (true) {
            coroutineContext.ensureActive()
            if (!process.session.isAlive) return false
            if (client.read("index.html") != null) return true
            if (System.currentTimeMillis() > deadline) return false
            delay(IDENTITY_POLL_INTERVAL_MS)
        }
    }

    /**
     * 启动前的端口排他检查。
     *
     * 上一代服务被强杀（进程卡死、App 被系统回收）时，PRoot tracer 来不及带走真正持有 LISTEN
     * 套接字的孙进程，它们沿袭了端口却不在本进程的登记表里。此时先给正常收尾一点时间，仍然占用
     * 则精确回收该端口上被遗弃的本运行时监听者；只有确认端口空出来才允许绑定，未知外部监听者
     * （他应用、终端里手起的服务）依旧拒绝连接。
     */
    private suspend fun awaitPortFreeOrReclaim(spec: LocalServiceSpec) {
        if (!probe.isBusy(spec.port)) return
        if (probe.awaitFree(spec.port, PORT_RELEASE_TIMEOUT_MS)) return
        runCatching { linuxRuntime.reclaimListeningPort(spec.port) }
        check(probe.awaitFree(spec.port, PORT_RELEASE_TIMEOUT_MS)) { OCCUPIED_PORT_MESSAGE }
    }

    private companion object {
        const val PORT_RELEASE_TIMEOUT_MS = 2_500L
        const val IDENTITY_TIMEOUT_MS = 2_000L
        const val IDENTITY_POLL_INTERVAL_MS = 100L
        const val OCCUPIED_PORT_MESSAGE = "服务端口已被占用，拒绝连接未知进程"
    }
}

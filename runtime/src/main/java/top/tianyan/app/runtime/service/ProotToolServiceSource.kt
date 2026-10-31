package top.tianyan.app.runtime.service

import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.ShellCommand
import kotlin.coroutines.coroutineContext

/** Native-resolved launch plan of an installed tool's own service, never taken from Web content. */
data class ServiceLaunch(
    val launchCommand: String,
    val environment: Map<String, String>,
    val pluginIdentity: top.tianyan.app.core.model.PluginExecutionIdentity? = null,
    val authenticated: Boolean = false,
    val distroId: String? = null,
)

/**
 * Native-resolved target of a tool service a plugin page may start itself. Port, path, command and
 * environment all come from validated native state (installed manifest + provider environment);
 * [ownerPluginId] and [distroId] only label the source for admission bookkeeping.
 */
data class ProotServiceDescriptor(
    val port: Int,
    val basePath: String,
    val launchCommand: String,
    val environment: Map<String, String>,
    val ownerPluginId: String,
    val distroId: String,
    val pluginIdentity: top.tianyan.app.core.model.PluginExecutionIdentity? = null,
) {
    init {
        require(port in 1..65535) { "工具服务端口无效" }
        require(IndexedManagedWebAssets.validBasePath(basePath)) { "工具服务路径无效" }
        require(launchCommand.isNotBlank() && launchCommand.length <= 4096) { "工具服务启动命令无效" }
        require(environment.size <= 128 && environment.all { (key, value) ->
            ENVIRONMENT_KEY.matches(key) && value.length <= 4096
        }) { "工具服务环境变量无效" }
        require(ownerPluginId.length in 1..128 && ownerPluginId.none(Char::isISOControl)) { "工具服务所有者无效" }
        require(distroId.length in 1..128 && distroId.none(Char::isISOControl)) { "工具服务所属系统无效" }
    }

    private companion object {
        val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * Page-owned source that starts an installed PROCESS tool's own local service.
 *
 * Unlike [ProotWebAssetService] there is no fixed bootstrap program: the tool owns its server, so
 * readiness is probed by a loopback socket connect instead of a pipe marker. The source refuses to
 * hand out a lease before allocation when the backend is remote, the runtime is not ready, or the
 * runtime cannot stop a single instance; it only ever stops the instance it started, so closing the
 * page never disturbs a service the tool center already runs. Resources are read through the bounded
 * pinned [IndexedManagedWebAssets], and no host credential is issued here.
 */
class ProotToolServiceSource(
    private val runtime: LinuxRuntime,
    private val descriptor: ProotServiceDescriptor,
    private val isLocal: () -> Boolean,
    private val startupTimeoutMillis: Long = 15_000,
    private val pollIntervalMillis: Long = 200,
) {
    private val instance = "plugin-web-svc:${UUID.randomUUID()}"
    private val process = AtomicReference<ManagedProcess?>(null)
    private val loading = Mutex()
    private val monitor = Any()
    private val assets = IndexedManagedWebAssets(
        port = descriptor.port,
        basePath = descriptor.basePath,
        available = ::backingAvailable,
    )
    private val client = ManagedWebServiceClient(
        port = descriptor.port,
        basePath = descriptor.basePath,
        available = { isAvailable },
    )
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var starting = false

    init {
        require(startupTimeoutMillis in 1..15_000) { "工具服务启动等待超出范围" }
        require(pollIntervalMillis in 50..5_000) { "工具服务轮询间隔超出范围" }
        check(runtime.supportsBackgroundInstanceStop && validTarget()) {
            "工具服务页面来源需要本地就绪运行时与单实例停止能力"
        }
    }

    /** False as soon as the source is closed, a start failed, or the served paths drift away. */
    val isAvailable: Boolean get() = !closed && !failed && assets.isAvailable

    private fun backingAvailable(): Boolean = !closed && !failed && validTarget() &&
        process.get()?.session?.isAlive != false

    private fun validTarget(): Boolean = isLocal() &&
        runtime.state.value is RuntimeState.Ready &&
        runtime.activeDistroId.value == descriptor.distroId

    /** Starts the pinned service instance, waits for its port, then serves a pinned entry snapshot. */
    suspend fun snapshot(): Map<String, ByteArray> = loading.withLock {
        try {
            withTimeout(startupTimeoutMillis) {
                synchronized(monitor) { check(isAvailable); starting = true }
                try {
                    check(process.get() == null) { "工具服务页面来源已加载" }
                    awaitPortFreeBeforeStart()
                    val owned = runtime.startBackground(
                        id = instance,
                        command = ShellCommand(
                            commandLine = descriptor.launchCommand,
                            environment = descriptor.environment,
                            pluginIdentity = descriptor.pluginIdentity,
                        ),
                        toolId = descriptor.ownerPluginId,
                        type = ProcessType.SERVICE,
                        distroId = descriptor.distroId,
                    )
                    process.set(owned)
                } finally { synchronized(monitor) { starting = false } }
                check(isAvailable) { "工具服务页面来源已不可用" }
                awaitReady()
                assets.snapshot()
            }
        } catch (failure: Throwable) { failed = true; throw failure }
    }

    /** Bounded blocking read for a live pinned path; answers nothing once closed or drifted. */
    fun resolve(path: String): ByteArray? = assets.resolve(path)

    /** Bounded dynamic read of the instance this page started; never another endpoint or credential. */
    suspend fun read(path: String): String? = client.read(path)

    suspend fun write(path: String, body: String): String? = client.write(path, body)

    suspend fun stream(path: String, emit: suspend (String) -> Boolean): Boolean = client.stream(path, emit)

    suspend fun close(): Boolean = withContext(NonCancellable) {
        val stillStarting = synchronized(monitor) { closed = true; starting }
        client.close()
        assets.close()
        // A failed start may retain a registered process for cleanup before returning its handle.
        val owned = process.get() ?: runtime.listBackground().singleOrNull {
            it.id == instance && it.toolId == descriptor.ownerPluginId
        }?.also { process.compareAndSet(null, it) } ?: return@withContext !stillStarting
        runtime.stopBackgroundInstance(owned) && !owned.session.isAlive
    }

    /**
     * 页面自建服务前的端口自愈。上一代被强杀的 tracer 可能来不及带走真正持有 LISTEN 套接字的孙进程，
     * 它们沿袭了端口却不在进程表里，新进程绑定必败，而下面的就绪探测又会被旧监听者迷惑。
     * 回收器确认清掉了本运行时的孤儿时才等端口放手；确认不了（外部监听者 / 无 procfs）则保持既有行为，
     * 交给就绪探测处理，不把一个本来就可能是外部服务的端口硬判为错误。
     */
    private suspend fun awaitPortFreeBeforeStart() {
        if (!isPortOpen()) return
        val reclaimed = runCatching { runtime.reclaimListeningPort(descriptor.port) }.getOrDefault(0)
        if (reclaimed <= 0) return
        withTimeoutOrNull(PORT_RELEASE_TIMEOUT_MS) {
            while (isPortOpen()) delay(pollIntervalMillis)
        }
    }

    private suspend fun awaitReady() {
        while (true) {
            coroutineContext.ensureActive()
            check(isAvailable) { "工具服务进程已退出或运行环境已变更" }
            if (isPortOpen()) return
            delay(pollIntervalMillis)
        }
    }

    private fun isPortOpen(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", descriptor.port), PORT_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val PORT_PROBE_TIMEOUT_MS = 250
        const val PORT_RELEASE_TIMEOUT_MS = 2_500L
    }
}

package top.tianyan.app.runtime.service

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.PluginExecutionIdentity
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.ShellCommand
import kotlin.coroutines.coroutineContext

/** A page-owned PRoot source, prepared without allocating a process; load only after ledger registration. */
class ProotWebAssetService(
    private val runtime: LinuxRuntime,
    assets: Map<String, ByteArray>,
    private val isLocal: () -> Boolean,
    private val startupTimeoutMillis: Long = 15_000,
    private val ownerPluginId: String = "tianyan.web-proot-demo",
) {
    private val distro = runtime.activeDistroId.value
    private val instance = UUID.randomUUID().toString()
    private val id = "plugin-web:$instance"
    private val prefix = "/$instance/"
    private val authorization = "Bearer ${UUID.randomUUID()}${UUID.randomUUID()}"
    private val files = assets.mapValues { it.value.copyOf() }
    private val hashes = files.mapValues { ManagedWebAssets.digest(it.value) }
    private val packet: ByteArray
    private val process = AtomicReference<ManagedProcess?>(null)
    private val loading = Mutex()
    private val monitor = Any()
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var starting = false

    init {
        require(startupTimeoutMillis in 1..15_000)
        require(ownerPluginId.length <= 128 && ownerPluginId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")))
        require(files.size in 1..63 && "index.html" in files && files.keys.all(ManagedWebAssets::safePath))
        require(files.values.sumOf { it.size.toLong() } <= 2 * 1024 * 1024)
        check(runtime.supportsBackgroundInstanceStop && validTarget()) { "Local Linux with instance cleanup is required" }
        packet = ProotWebBootstrap.encode(files, instance, authorization)
        // A single initial pipe write fits Linux PIPE_BUF; never stream an unbounded bootstrap.
        check(packet.size <= ProotWebBootstrap.MAX_PACKET_BYTES)
    }

    val isAvailable: Boolean get() = !closed && !failed && validTarget() && (process.get()?.session?.isAlive != false)
    private fun validTarget() = isLocal() && runtime.state.value is RuntimeState.Ready && runtime.activeDistroId.value == distro

    suspend fun snapshot(): Map<String, ByteArray> = loading.withLock {
        try {
            withTimeout(startupTimeoutMillis) {
                synchronized(monitor) { check(isAvailable); starting = true }
                try {
                    check(process.get() == null) { "PRoot Web source already loaded" }
                    val owned = runtime.startBackground(id, ShellCommand(ProotWebAssetProgram.command, workingDirectory = "/",
                        pluginIdentity = PluginExecutionIdentity(ownerPluginId, "host-static")),
                        toolId = ownerPluginId, type = ProcessType.SERVICE, distroId = distro)
                    process.set(owned)
                } finally { synchronized(monitor) { starting = false } }
                check(isAvailable) { "PRoot Web source is no longer available" }
                process.get()!!.session.write(packet)
                val ready = Regex("TIANYAN_WEB_READY_$instance:([0-9]{1,5}):END")
                var port: Int? = null
                while (port == null) {
                    coroutineContext.ensureActive()
                    check(isAvailable) { "PRoot Web process exited or environment changed" }
                    port = ready.find(runtime.getBackgroundLogs(id).takeLast(64).joinToString(""))
                        ?.groupValues?.get(1)?.toInt()?.also { require(it in 1..65535) }
                    if (port == null) delay(50)
                }
                ManagedWebAssets(port, prefix, hashes, authorization, { isAvailable }, { close() }).snapshot()
            }
        } catch (failure: Throwable) { failed = true; throw failure }
    }

    suspend fun close(): Boolean = withContext(NonCancellable) {
        val stillStarting = synchronized(monitor) { closed = true; starting }
        // A failed start may retain a registered process for cleanup before returning its handle.
        val owned = process.get() ?: runtime.listBackground().singleOrNull {
            it.id == id && it.toolId == ownerPluginId
        }?.also { process.compareAndSet(null, it) } ?: return@withContext !stillStarting
        runtime.stopBackgroundInstance(owned) && !owned.session.isAlive
    }
}

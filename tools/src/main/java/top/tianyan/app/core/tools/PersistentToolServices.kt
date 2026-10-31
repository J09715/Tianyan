package top.tianyan.app.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import top.tianyan.app.core.database.ToolSettingsRepository
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.ToolState
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.LocalBackendAccess

/** Driven only by the Runtime foreground service, never by a page or a detached timer. */
class PersistentToolServices(
    private val runtime: LinuxRuntime,
    private val tools: ToolManager,
    private val repository: ToolRepository,
    private val settings: ToolSettingsRepository,
    private val packages: ToolPackageOperations,
    private val access: LocalBackendAccess,
    private val runtimePreferences: RuntimePreferences,
    private val services: ToolServiceController,
) {
    private val gate = Mutex()
    private val policies = mutableMapOf<String, ServiceRecoveryPolicy>()

    suspend fun check() = gate.withLock {
        runCatching { services.reconcileNotifications() }
        if (runCatching { access.requireLocal() }.isFailure) return@withLock
        if (runtime.state.value is RuntimeState.NotInitialized) {
            // A sticky service restart has no Activity to restore Linux. Only an existing opt-in
            // may restore an installed sandbox; never initialize/download a new RootFS here.
            val savedDistro = runtimePreferences.selectedDistribution.first()
            val desired = repository.getForDistro(savedDistro).any {
                it.installedVersion != null && settings.autoStart(savedDistro, it.id).first()
            }
            if (desired) runtime.restoreInstalledState()
        }
        if (runtime.state.value !is RuntimeState.Ready) return@withLock
        val distro = runtime.activeDistroId.value
        val retained = mutableSetOf<String>()
        repository.getForDistro(distro).forEach { tool ->
            if (tool.state !in setOf(ToolState.INSTALLED.name, ToolState.UPDATE_AVAILABLE.name) ||
                tool.installedVersion == null || !settings.autoStart(distro, tool.id).first()) return@forEach
            try {
                val manifest = packages.runtimeManifest(distro, tool.id) ?: return@forEach
                if (!manifest.enabled || manifest.launchType !in setOf("web", "service") || manifest.servicePort == null) return@forEach
                val key = "$distro:${tool.id}:${tool.installedVersion}"
                retained += key
                val policy = policies.getOrPut(key) { ServiceRecoveryPolicy() }
                policy.check(
                    healthy = { runtime.activeDistroId.value != distro || tools.isGatewayRunning(tool.id) },
                    recover = {
                        // Same lock order as installation: package lease, then manager mutation gate.
                        packages.useInstalled(distro, manifest, allowUpdateAvailable = true) {
                            tools.runExclusive {
                                if (runtime.state.value is RuntimeState.Ready && runtime.activeDistroId.value == distro &&
                                    settings.autoStart(distro, tool.id).first() && !tools.isGatewayRunning(tool.id)) {
                                    check(withTimeoutOrNull(30_000) { tools.recoverGateway(distro, manifest); true } == true) { "服务恢复启动超时" }
                                }
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* One broken receipt must not prevent other services recovering. */ }
        }
        policies.keys.retainAll(retained)
    }

    /** Notification Stop is an explicit persistent opt-out, including nonselected distros. */
    suspend fun disableAll() = gate.withLock {
        runtime.installedDistros.value.forEach { distro ->
            repository.getForDistro(distro.id).forEach { settings.setAutoStart(distro.id, it.id, false) }
        }
        services.hideNotifications()
        policies.clear()
    }
}

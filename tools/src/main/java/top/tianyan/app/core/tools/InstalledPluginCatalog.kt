package top.tianyan.app.core.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.model.ToolState
import top.tianyan.app.runtime.LinuxRuntime

data class InstalledPluginBinding(val distroId: String, val manifest: ToolManifest)

/** Installed receipt snapshots, never merely imported upgrade candidates. */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledPluginCatalog(
    private val repository: ToolRepository,
    private val operations: ToolPackageOperations,
    private val runtime: LinuxRuntime,
    private val requireLocal: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    val installed = runtime.activeDistroId.flatMapLatest { distro ->
        repository.observeTools(distro).map { rows ->
            rows.filter { it.installedVersion != null && it.state == ToolState.INSTALLED.name }.mapNotNull { row ->
                try {
                    operations.runtimeManifest(distro, row.id)?.takeIf { it.schemaVersion == 2 && it.plugin?.execution == "PROCESS" }
                        ?.let { InstalledPluginBinding(distro, it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }.onStart { emit(emptyList()) }.retryWhen { cause, _ ->
            if (cause is CancellationException) false else { emit(emptyList()); delay(1_000); true }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun current(): List<InstalledPluginBinding> = try {
        check(scope.isActive)
        requireLocal()
        installed.value.filter { it.distroId == runtime.activeDistroId.value }
    } catch (_: IllegalStateException) { emptyList() }

    suspend fun <T> use(expected: InstalledPluginBinding, action: suspend () -> T): T {
        requireLocal()
        check(runtime.state.value is top.tianyan.app.core.model.RuntimeState.Ready) { "本地系统尚未就绪" }
        check(runtime.activeDistroId.value == expected.distroId) { "插件执行系统已改变" }
        return operations.useInstalled(expected.distroId, expected.manifest) {
            action().also {
                requireLocal()
                check(runtime.activeDistroId.value == expected.distroId) { "插件执行系统已改变" }
            }
        }
    }
    override fun close() { scope.cancel() }
}

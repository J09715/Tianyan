package top.tianyan.app.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import top.tianyan.app.plugin.packages.PackagedPluginCatalog
import top.tianyan.app.plugin.packages.PackagedPluginEntry
import top.tianyan.app.runtime.LinuxRuntime

class PackagePluginInventory(private val repository: ToolRepository, private val runtime: LinuxRuntime) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<PackagedPluginEntry>> = runtime.activeDistroId.flatMapLatest { distro ->
        repository.observeTools(distro).map { tools ->
            PackagedPluginCatalog.create(repository.manifests(), tools.associate { it.id to it.installedVersion }, tools.associate { it.id to it.state })
        }
    }.flowOn(Dispatchers.IO)
}

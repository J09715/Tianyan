package top.tianyan.app.core.tools

import kotlinx.coroutines.flow.MutableStateFlow
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.*

/** Captured install target; selection changes cannot redirect commands, payload copies or links. */
internal class PinnedToolRuntime(private val delegate: LinuxRuntime, private val distribution: String,
    private val identity: top.tianyan.app.core.model.PluginExecutionIdentity? = null) : LinuxRuntime by delegate {
    override val activeDistroId = MutableStateFlow(distribution)
    private fun target(requested: String?): String {
        require(requested == null || requested == distribution) { "插件操作不能切换发行版" }
        return distribution
    }
    override suspend fun execute(command: ShellCommand, distroId: String?) = delegate.execute(command.copy(pluginIdentity = identity ?: command.pluginIdentity), target(distroId))
    override suspend fun startSession(config: SessionConfig, distroId: String?) = delegate.startSession(config.copy(pluginIdentity = identity ?: config.pluginIdentity), target(distroId))
    override suspend fun startBackground(id: String, command: ShellCommand, toolId: String?, type: ProcessType, distroId: String?) =
        delegate.startBackground(id, command.copy(pluginIdentity = identity ?: command.pluginIdentity), toolId, type, target(distroId))
}

internal fun top.tianyan.app.core.model.ToolManifest.executionIdentity() =
    plugin?.takeIf { schemaVersion == 2 }?.let { top.tianyan.app.core.model.PluginExecutionIdentity(it.id, version) }

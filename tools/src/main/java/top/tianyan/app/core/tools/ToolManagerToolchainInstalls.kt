package top.tianyan.app.core.tools

import kotlinx.coroutines.flow.collect
import top.tianyan.app.runtime.doctor.ToolchainInstalls

/**
 * 把工具链补齐接到 [ToolManager] 的安装锁上。
 * 套件组件走 [ToolManager.batchInstallComponents]（其内部已持同一把锁），
 * apt 步骤走 [ToolManager.runExclusive]，避免和设置页的开发套件安装并发动 dpkg。
 */
internal class ToolManagerToolchainInstalls(
    private val toolManager: ToolManager,
) : ToolchainInstalls {
    override suspend fun installComponents(componentIds: Set<String>) {
        toolManager.batchInstallComponents(componentIds, reinstall = false).collect { }
    }

    override suspend fun <T> runExclusive(block: suspend () -> T): T = toolManager.runExclusive(block)
}

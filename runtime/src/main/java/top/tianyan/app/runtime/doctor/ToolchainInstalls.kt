package top.tianyan.app.runtime.doctor

/**
 * 开发套件安装与 apt 互斥闸。
 *
 * 实现在 tools（[top.tianyan.app.core.tools.ToolManager]），runtime 不反向依赖 tools。
 * [runExclusive] 必须用套件安装同一把锁，避免补齐 apt 与设置页开发套件安装同时动 dpkg。
 */
interface ToolchainInstalls {
    suspend fun installComponents(componentIds: Set<String>)
    suspend fun <T> runExclusive(block: suspend () -> T): T
}

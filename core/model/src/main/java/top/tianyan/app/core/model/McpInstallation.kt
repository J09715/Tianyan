package top.tianyan.app.core.model

/** 就绪是当前执行环境的观察值；Room 中的开关仅保留用户选择及默认投影。 */
fun McpServerConfig.withInstalledComponents(installed: Set<String>?): McpServerConfig {
    if (!requiresInstall) return this
    val ready = installed?.contains(installComponentId)
    return copy(
        installationReady = ready,
        isEnabled = ready == true && (!userToggled || isEnabled),
    )
}

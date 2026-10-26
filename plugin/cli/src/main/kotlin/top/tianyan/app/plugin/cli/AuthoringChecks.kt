package top.tianyan.app.plugin.cli

import top.tianyan.app.core.model.ToolManifest

/** Mirrors the v2-relevant subset of the host's ToolManifestValidator (Android tools module). */
internal object AuthoringChecks {
    private val launchTypes = setOf("one_shot", "pty", "web", "service", "command")
    private val permissions = setOf("NETWORK", "WORKSPACE_READ", "WORKSPACE_WRITE", "LOCAL_WEB")
    private val updateStrategies = setOf("REINSTALL", "IN_PLACE")
    private val installMethods = setOf("SCRIPT", "LOCAL_PACKAGE")

    fun validate(manifest: ToolManifest) {
        require(manifest.name.isNotBlank()) { "插件名称不能为空" }
        require(manifest.description.isNotBlank()) { "插件描述不能为空" }
        require(manifest.publisher.length <= 128) { "插件发布者名称不能超过 128 字符" }
        require(manifest.latestVersion?.isNotBlank() != false) { "最新版本号不能为空白" }
        require(manifest.architectures.any { it.equals("ARM64", ignoreCase = true) }) { "插件必须声明 ARM64 架构" }
        require(manifest.launchType in launchTypes) { "不支持的启动类型：${manifest.launchType}" }
        require(manifest.updateStrategy in updateStrategies) { "不支持的更新策略：${manifest.updateStrategy}" }
        require(manifest.installMethod in installMethods) { "不支持的安装方式：${manifest.installMethod}" }
        require(manifest.source == "LOCAL") { "v2 作者工程必须使用 LOCAL 来源" }
        require(manifest.offlineOnly) { "本地插件必须声明 offlineOnly=true" }
        require(manifest.installMethod == "LOCAL_PACKAGE") { "本地插件必须使用 LOCAL_PACKAGE" }
        manifest.permissions.forEach { require(it in permissions) { "不支持的工具权限：$it" } }
        manifest.homepage?.let { require(it.startsWith("https://", ignoreCase = true)) { "插件主页必须使用 HTTPS" } }
        manifest.servicePort?.let { port ->
            require(port in 1..65535) { "无效的本地服务端口：$port" }
            require(manifest.launchType in setOf("web", "service")) { "只有 web/service 插件可以声明服务端口" }
            require(manifest.servicePath.startsWith('/') && !manifest.servicePath.contains("..")) { "本地服务路径不安全" }
        }
    }
}

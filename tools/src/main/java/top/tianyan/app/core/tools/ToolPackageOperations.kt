package top.tianyan.app.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.model.PluginInstallReceipt
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PackageInstallPolicy
import top.tianyan.app.plugin.packages.PluginPackageProtocol

/** Serializes install/uninstall reference checks; receipts are minimal, version-specific metadata. */
class ToolPackageOperations(private val repository: ToolRepository, private val receipts: PluginReceiptStore,
    private val hostGrants: top.tianyan.app.runtime.bridge.FullAccessGrant? = null) {
    private val operations = Mutex()

    suspend fun <T> useInstalled(distro: String, expected: ToolManifest, action: suspend () -> T): T = operations.withLock {
        val installed = repository.findById(distro, expected.id)
        check(installed?.installedVersion != null && installed.state == "INSTALLED") { "插件未安装或正在改变" }
        check(runtimeManifest(distro, expected.id) == expected) { "插件安装版本已改变，请重新发现扩展" }
        action()
    }

    suspend fun <T> install(distro: String, id: String, expected: ToolManifest? = null, action: suspend (ToolManifest) -> T): T = operations.withLock {
        val manifest = requireNotNull(repository.manifest(id)) { "工具清单不存在" }
        require(expected == null || manifest == expected) { "已确认的插件清单已变化，请重新导入确认" }
        val installed = installed(distro)
        val previous = installed.find { it.toolId == id }
        if (manifest.schemaVersion == 2 && previous != null && !previous.pluginId.startsWith("legacy.")) {
            val oldCommands = previous.resources.filter { it.path.startsWith("/opt/tianyan/bin/") }.map { it.path }
            val nextCommands = PluginPackageProtocol.receipt(manifest).resources.map { it.path }.toSet()
            require(oldCommands.all { it in nextCommands }) { "更新暂不能移除既有命令入口，请先卸载旧版本" }
        }
        PackageInstallPolicy.requireInstall(manifest, installed)
        manifest.plugin?.let { hostGrants?.revokePlugin(distro, it.id) }
        action(manifest)
    }

    suspend fun <T> uninstall(distro: String, id: String, action: suspend (ToolManifest) -> T): T = operations.withLock {
        val installed = installed(distro)
        PackageInstallPolicy.requireUninstall(id, installed)
        val manifest = requireNotNull(runtimeManifest(distro, id))
        // Use the installed receipt to preserve v2 framework-only uninstall after selecting another version.
        val receipt = installed.find { it.toolId == id }
        receipt?.let { hostGrants?.revokePlugin(distro, it.pluginId) }
        require(receipt == null || receipt.pluginId.startsWith("legacy.") || manifest.schemaVersion == 2) { "已安装插件与清单形态不一致" }
        val commandLinks = receipt?.resources?.filter { it.path.startsWith("/opt/tianyan/bin/") }?.map { it.path.removePrefix("/opt/tianyan/bin/") }
        action(if (manifest.schemaVersion == 2 && !commandLinks.isNullOrEmpty()) manifest.copy(commandLinks = commandLinks) else manifest)
    }

    suspend fun captureLegacy(distros: List<String>) = operations.withLock {
        val manifests = repository.manifests().associateBy { it.id }
        distros.distinct().forEach { distro -> repository.getForDistro(distro).forEach toolLoop@ { tool ->
            val version = tool.installedVersion ?: return@toolLoop
            val manifest = manifests[tool.id] ?: return@toolLoop
            if (manifest.schemaVersion == 1 && receipts.read(distro, tool.id, version) == null) receipts.record(distro, manifest, version)
        } }
    }

    fun record(distro: String, manifest: ToolManifest, version: String) = receipts.record(distro, manifest, version)

    /** Selected packages are installation candidates, never replacements for installed runtime config. */
    suspend fun runtimeManifest(distro: String, id: String): ToolManifest? {
        val selected = repository.manifest(id) ?: return null
        val version = repository.findById(distro, id)?.installedVersion ?: return selected
        val receipt = receipts.read(distro, id, version)
        val packageVersion = receipt?.packageVersion ?: version
        val installed = repository.localManifest(id, packageVersion)
        if (installed != null) {
            require(receipt == null || PluginPackageProtocol.identity(installed) == receipt.pluginId) { "已安装插件清单身份不匹配" }
            return installed
        }
        require(selected.schemaVersion != 2 && (receipt == null || receipt.pluginId.startsWith("legacy."))) { "已安装插件版本清单缺失" }
        // Legacy online/static tools have no local package directory. Old receipts lacked the binding.
        require(receipt?.packageVersion == null || selected.source != "LOCAL") { "已安装本地插件版本清单缺失" }
        return selected
    }

    suspend fun addedCapabilities(distro: String, candidate: ToolManifest): List<String> = operations.withLock {
        PackageInstallPolicy.addedCapabilitiesFromReceipt(candidate, installed(distro).find { it.toolId == candidate.id })
    }

    private suspend fun installed(distro: String): List<PluginInstallReceipt> {
        val manifests = repository.manifests().associateBy { it.id }
        return repository.getForDistro(distro).mapNotNull { tool ->
            val version = tool.installedVersion ?: return@mapNotNull null
            val manifest = manifests[tool.id]
            receipts.read(distro, tool.id, version) ?: run {
                require(manifest?.schemaVersion != 2) { "已安装插件缺少匹配的版本收据，请恢复原清单后重试" }
                manifest?.let { PluginPackageProtocol.receipt(it).copy(version = version, capabilities = emptyList()) }
                    ?: PluginInstallReceipt(toolId = tool.id, pluginId = "legacy.tool-${tool.id}", version = version)
            }
        }
    }
}

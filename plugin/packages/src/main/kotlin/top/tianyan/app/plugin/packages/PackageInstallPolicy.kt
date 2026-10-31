package top.tianyan.app.plugin.packages

import top.tianyan.app.core.model.PluginInstallReceipt
import top.tianyan.app.core.model.PackageResource
import top.tianyan.app.core.model.ToolManifest

/** Checks installed-version receipts, never a selected upgrade's declarations. No script execution. */
object PackageInstallPolicy {
    fun requireInstall(candidate: ToolManifest, installed: List<PluginInstallReceipt>) {
        PluginPackageProtocol.validate(candidate)
        val next = PluginPackageProtocol.receipt(candidate)
        val others = installed.filter { it.toolId != candidate.id }
        require(others.none { it.pluginId == next.pluginId }) { "插件身份已被其他安装占用" }
        val proposed = others + next
        require(proposed.map { it.pluginId }.distinct().size == proposed.size) { "已装插件身份冲突" }
        proposed.forEach { owner ->
            owner.dependencies.forEach { dependency ->
                val target = proposed.singleOrNull { it.pluginId == dependency.id } ?: error("缺少已安装依赖：${dependency.id}")
                val actual = requireNotNull(SemanticVersion.parse(target.version)) { "依赖安装版本不是 SemVer：${dependency.id}" }
                require(actual >= requireNotNull(SemanticVersion.parse(dependency.minimumVersion)) &&
                    (dependency.maximumVersionExclusive?.let { actual < requireNotNull(SemanticVersion.parse(it)) } != false)) {
                    "已安装依赖版本不兼容：${dependency.id}"
                }
            }
        }
        // A cycle may arise only after replacing an already-installed dependency.
        val completed = mutableSetOf<String>()
        repeat(proposed.size) {
            proposed.filter { entry -> entry.dependencies.all { it.id in completed } }.forEach { completed += it.pluginId }
        }
        require(completed.size == proposed.size) { "插件依赖存在循环" }
        proposed.forEachIndexed { index, first ->
            proposed.drop(index + 1).forEach { second ->
                first.resources.forEach { left -> second.resources.forEach { right ->
                    require((left.key != right.key && !overlaps(left.path, right.path)) || shareable(left, right)) { "插件资源归属冲突" }
                } }
            }
        }
    }

    fun requireUninstall(toolId: String, installed: List<PluginInstallReceipt>) {
        val target = installed.singleOrNull { it.toolId == toolId } ?: return
        require(installed.none { it.toolId != toolId && it.dependencies.any { dependency -> dependency.id == target.pluginId } }) {
            "仍有已安装插件依赖此插件，请先卸载依赖者"
        }
    }

    fun addedCapabilities(candidate: ToolManifest, previous: ToolManifest?): List<String> {
        val before = previous?.let { declaredCapabilities(it) }.orEmpty().toSet()
        return declaredCapabilities(candidate).filterNot { it in before }.sorted()
    }

    fun addedCapabilitiesFromReceipt(candidate: ToolManifest, previous: PluginInstallReceipt?): List<String> {
        val before = previous?.capabilities.orEmpty().toSet()
        return PluginPackageProtocol.receipt(candidate).capabilities.filterNot { it in before }
            .map { if (it.target.isEmpty()) it.name else "${it.name}:${it.target}" }.sorted()
    }

    private fun declaredCapabilities(manifest: ToolManifest): List<String> =
        manifest.permissions + manifest.plugin?.capabilities.orEmpty().map { "${it.name}:${it.target}" }

    private fun overlaps(left: String, right: String) = left == right || left.startsWith("$right/") || right.startsWith("$left/")
    private fun shareable(left: PackageResource, right: PackageResource) =
        left.path == right.path && left.key == right.key && left.sharing == "SHARED" && right.sharing == "SHARED" &&
            left.contentSha256 != null && left.contentSha256 == right.contentSha256
}

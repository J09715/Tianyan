package top.tianyan.app.plugin.packages

import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.api.PluginDescriptor

data class PackagedPluginEntry(
    val descriptor: PluginDescriptor,
    val toolId: String,
    val name: String,
    val source: String,
    val installedVersion: String?,
    val state: String,
    val manifest: ToolManifest,
)

/** Metadata inventory, separate from the compiled-in page activation controller. */
object PackagedPluginCatalog {
    fun create(manifests: List<ToolManifest>, installed: Map<String, String?>, states: Map<String, String>): List<PackagedPluginEntry> {
        val descriptors = manifests.map(PluginPackageProtocol::descriptor)
        require(descriptors.map { it.id }.distinct().size == descriptors.size) { "插件目录身份冲突" }
        return manifests.zip(descriptors).map { (manifest, descriptor) ->
            PackagedPluginEntry(descriptor, manifest.id, manifest.name, manifest.source, installed[manifest.id],
                states[manifest.id] ?: "AVAILABLE", manifest)
        }.sortedBy { it.descriptor.id }
    }
}

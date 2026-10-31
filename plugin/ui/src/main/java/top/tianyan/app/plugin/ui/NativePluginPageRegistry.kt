package top.tianyan.app.plugin.ui

import top.tianyan.app.plugin.api.PluginApiRange
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.api.PluginExtensionRef

interface PluginPages {
    val catalog: PluginCatalog
    fun resolve(reference: PluginExtensionRef): NativePluginPage?
}

/** Published only after declarations, implementations and the complete dependency graph agree. */
class NativePluginPageRegistry(
    plugins: List<NativePagePlugin>,
    hostApi: Int = PluginApiRange.CURRENT,
) : PluginPages {
    private val contributions = plugins.map { it.descriptor to it.pages.toList() }
    override val catalog = PluginCatalog.create(contributions.map { it.first }, hostApi)
    private val pages: Map<PluginExtensionRef, NativePluginPage>

    init {
        val registered = mutableMapOf<PluginExtensionRef, NativePluginPage>()
        contributions.forEach { (descriptor, implementations) ->
            val declared = catalog.plugin(descriptor.id)!!.extensions
                .filter { it.point == PluginExtensionPoint.PAGE }.map { it.id }.toSet()
            require(implementations.map { it.id }.distinct().size == implementations.size) {
                "Duplicate page implementation: ${descriptor.id}"
            }
            require(implementations.map { it.id }.toSet() == declared) {
                "Page declarations and implementations differ: ${descriptor.id}"
            }
            implementations.forEach { page ->
                registered[PluginExtensionRef(descriptor.id, page.id)] = page
            }
        }
        plugins.forEach { plugin ->
            require(plugin.entries.map { it.id }.distinct().size == plugin.entries.size) { "Duplicate plugin entry" }
            require(plugin.entries.all { entry ->
                entry.id.matches(Regex("[a-z][a-z0-9-]*")) &&
                    catalog.extension(PluginExtensionRef(plugin.descriptor.id, entry.pageId))?.point == PluginExtensionPoint.PAGE
            }) { "Invalid plugin entry target" }
        }
        pages = registered.toMap()
    }

    override fun resolve(reference: PluginExtensionRef): NativePluginPage? = pages[reference]
}

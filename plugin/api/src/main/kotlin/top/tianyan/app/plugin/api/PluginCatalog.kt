package top.tianyan.app.plugin.api

import java.util.Collections
import java.util.PriorityQueue

/**
 * Immutable, side-effect-free catalog. Validation completes before any entry is published.
 * This is a compiled-in registration boundary, not a code loader or permission sandbox.
 */
class PluginCatalog private constructor(val plugins: List<PluginDescriptor>) {
    private val byId = plugins.associateBy { it.id }

    fun plugin(id: String): PluginDescriptor? = byId[id]

    fun extension(reference: PluginExtensionRef): PluginExtension? =
        byId[reference.pluginId]?.extensions?.find { it.id == reference.extensionId }

    companion object {
        private val pluginId = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")
        private val extensionId = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")

        fun create(
            descriptors: List<PluginDescriptor>,
            hostApi: Int = PluginApiRange.CURRENT,
        ): PluginCatalog {
            require(hostApi > 0) { "Invalid host plugin API" }
            // Copy caller-owned collections before validation; later mutations cannot change the catalog.
            val entries = descriptors.map { descriptor ->
                descriptor.copy(
                    dependencies = Collections.unmodifiableSet(descriptor.dependencies.toSet()),
                    extensions = Collections.unmodifiableList(descriptor.extensions.toList()),
                )
            }
            require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate plugin ID" }
            val byId = entries.associateBy { it.id }
            entries.forEach { descriptor ->
                require(pluginId.matches(descriptor.id)) { "Invalid plugin ID: ${descriptor.id}" }
                require(descriptor.version.isNotBlank()) { "Missing plugin version: ${descriptor.id}" }
                require(hostApi in descriptor.api) { "Incompatible plugin API: ${descriptor.id}" }
                require(descriptor.dependencies.all { it in byId }) { "Missing plugin dependency: ${descriptor.id}" }
                require(descriptor.extensions.map { it.id }.distinct().size == descriptor.extensions.size) {
                    "Duplicate extension ID: ${descriptor.id}"
                }
                require(descriptor.extensions.all { extensionId.matches(it.id) }) {
                    "Invalid extension ID: ${descriptor.id}"
                }
            }

            val remaining = entries.associate { it.id to it.dependencies.size }.toMutableMap()
            val dependents = mutableMapOf<String, MutableList<String>>()
            entries.forEach { entry ->
                entry.dependencies.forEach { dependency ->
                    dependents.getOrPut(dependency) { mutableListOf() }.add(entry.id)
                }
            }
            val ready = PriorityQueue<String>()
            remaining.filterValues { it == 0 }.keys.forEach(ready::add)
            val ordered = mutableListOf<PluginDescriptor>()
            while (ready.isNotEmpty()) {
                val id = ready.remove()
                ordered += byId.getValue(id)
                dependents[id].orEmpty().forEach { dependent ->
                    val count = remaining.getValue(dependent) - 1
                    remaining[dependent] = count
                    if (count == 0) ready.add(dependent)
                }
            }
            require(ordered.size == entries.size) { "Plugin dependency cycle" }
            return PluginCatalog(Collections.unmodifiableList(ordered))
        }
    }
}

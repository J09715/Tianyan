package top.tianyan.app.plugin.api

/** Host API compatibility is independent of a plugin's release version. */
data class PluginApiRange(val minimum: Int = CURRENT, val maximum: Int = CURRENT) {
    init {
        require(minimum > 0 && maximum >= minimum) { "Invalid plugin API range" }
    }

    operator fun contains(api: Int): Boolean = api in minimum..maximum

    companion object {
        const val CURRENT = 1
    }
}

/** Only implemented extension points are published. Add others with their host adapters. */
enum class PluginExtensionPoint { PAGE, PROCESS, TOOL, CONTEXT, PROVIDER, WORKFLOW, CHECKPOINT }

data class PluginExtension(val id: String, val point: PluginExtensionPoint)

/** A stable reference, independent of labels, navigation keys and implementation classes. */
data class PluginExtensionRef(val pluginId: String, val extensionId: String)

/** Metadata for trusted, compiled-in contributions; not an external package manifest. */
data class PluginDescriptor(
    val id: String,
    val version: String,
    val api: PluginApiRange = PluginApiRange(),
    val dependencies: Set<String> = emptySet(),
    val extensions: List<PluginExtension> = emptyList(),
)

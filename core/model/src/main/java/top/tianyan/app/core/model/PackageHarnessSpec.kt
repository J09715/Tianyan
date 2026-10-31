package top.tianyan.app.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** MCP entry is one installed private bin name; no remote address or arbitrary launch command. */
@Serializable data class PackageHarnessSpec(
    val entry: String,
    val tools: List<PackageCallable> = emptyList(),
    val nodes: List<PackageCallable> = emptyList(),
    val providers: List<PackageProvider> = emptyList(),
)
@Serializable data class PackageCallable(val id: String, val description: String, val schema: JsonObject, val rpcTool: String = id)
@Serializable data class PackageProvider(val id: String, val rpcTool: String, val api: String = "OPENAI_COMPLETIONS",
    val models: List<String> = listOf("plugin-default"))

/** Trusted host metadata, never accepted from model tool arguments or exported MCP config. */
data class PluginExecutionIdentity(val pluginId: String, val version: String)

package top.tianyan.app.harness.plugins

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.PluginExecutionIdentity
import top.tianyan.app.core.tools.InstalledPluginBinding
import top.tianyan.app.core.tools.InstalledPluginCatalog
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.mcp.McpStdioTransport
import top.tianyan.app.harness.mcp.McpTransport
import top.tianyan.app.plugin.api.PluginApiRange
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.packages.PackageHarnessPolicy
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext

/** Metadata discovery has no process side effects. Each approved call owns one MCP instance. */
class ExternalHarnessPlugins(private val installed: InstalledPluginCatalog, private val transport: McpTransport,
    private val close: suspend (String) -> Unit) {
    constructor(installed: InstalledPluginCatalog, transport: McpStdioTransport) : this(installed, transport, transport::closeConnection)
    private val unsettled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun plugins(): List<HarnessPlugin> = installed.current().mapNotNull { binding ->
        val manifest = binding.manifest
        val spec = manifest.plugin ?: return@mapNotNull null
        val harness = spec.harness ?: return@mapNotNull null
        HarnessPlugin(
            descriptor = PluginDescriptor(domain(spec.id, manifest.version), manifest.version, PluginApiRange(spec.api.minimum, spec.api.maximum),
                extensions = PackageHarnessPolicy.extensions(manifest).map { PluginExtension(it.id, PluginExtensionPoint.valueOf(it.point)) }),
            tools = harness.tools.map { declaration ->
                PluginTool(declaration.id, "[${spec.id} @ ${manifest.version}] ${declaration.description.take(1600)}", declaration.schema) { request ->
                    invoke(binding, declaration.rpcTool, request.arguments)
                }
            },
            nodes = harness.nodes.map { declaration ->
                PluginWorkflowNode(declaration.id, "[${spec.id} @ ${manifest.version}] ${declaration.description.take(1600)}", declaration.schema) { request ->
                    invoke(binding, declaration.rpcTool, request.arguments)
                }
            },
            providers = harness.providers.map { declaration ->
                PluginProvider(declaration.id, LlmApi.valueOf(declaration.api), "plugin.${spec.id}.${declaration.id}") { _, _ ->
                    ExternalPluginProvider(LlmApi.valueOf(declaration.api)) { arguments -> invoke(binding, declaration.rpcTool, arguments) }
                }
            },
            packageId = spec.id,
        )
    }

    private fun domain(id: String, version: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$id@$version".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return id.take(80).trimEnd('.', '-') + ".v$digest"
    }

    private suspend fun invoke(binding: InstalledPluginBinding, name: String, arguments: JsonObject): PluginToolOutput =
        installed.use(binding) {
            for (previous in unsettled.toList()) {
                withContext(NonCancellable) { withTimeout(15_000) { close(previous) } }
                unsettled.remove(previous)
            }
            val environment = currentCoroutineContext()[ExecutionEnvironmentContext]?.environment
            check(environment == null || (environment.id.backend == "local-proot" && environment.id.distribution == binding.distroId)) {
                "外部插件只能在其安装的本地系统调用"
            }
            val spec = requireNotNull(binding.manifest.plugin)
            val entry = requireNotNull(spec.harness).entry
            val server = McpServerConfig(id = "package-${java.util.UUID.randomUUID()}", name = binding.manifest.name,
                command = "/opt/tianyan/tools/${binding.manifest.id}/bin/$entry", args = listOf("--mcp"),
                pluginIdentity = PluginExecutionIdentity(spec.id, binding.manifest.version), pluginDistribution = binding.distroId)
            try {
                val result = withTimeout(120_000) { transport.execute(server, name, arguments) }
                currentCoroutineContext().ensureActive()
                require(result.second.toByteArray(Charsets.UTF_8).size <= 262_144) { "外部扩展返回结果过大" }
                PluginToolOutput(result.first, result.second)
            } finally {
                try { withContext(NonCancellable) { withTimeout(15_000) { close(server.id) } } }
                catch (failure: Exception) { unsettled.add(server.id); throw failure }
            }
        }
}

package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.withInstalledComponents
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

/** 批量本地文件探针；不问 registry，也不启动 MCP 服务器。失败时关闭按需能力。 */
internal suspend fun probeMcpCatalog(
    ids: Set<String> = McpCatalog.installable.map { it.id }.toSet(),
    execute: suspend (ShellCommand) -> CommandResult,
): Set<String> {
    val command = McpCatalog.installable.filter { it.id in ids }.joinToString("\n") {
        "if ${it.probeCommand}; then printf '%s\\n' '${it.installComponentId}'; fi"
    } + "\nexit 0"
    return try {
        val result = execute(ShellCommand(commandLine = command, timeoutMs = 8_000L))
        if (result.isSuccess) result.stdout.lineSequence().map { it.trim() }
            .filter { id -> McpCatalog.installable.any { it.installComponentId == id } }.toSet() else emptySet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }
}

class McpCatalogAvailability(
    private val runtime: LinuxRuntime,
    private val environments: ExecutionEnvironmentFactory? = null,
    private val remoteServers: top.tianyan.app.core.datastore.RemoteServerRepository? = null,
) {
    suspend fun resolve(servers: List<McpServerConfig>): List<McpServerConfig> {
        if (servers.none { it.requiresInstall }) return servers
        val bound = currentCoroutineContext()[ExecutionEnvironmentContext]?.environment
        if (bound == null && environments == null && remoteServers?.activeId?.value != null)
            return servers.map { it.withInstalledComponents(emptySet()) }
        val owned = try {
            if (bound == null && environments != null) {
                val scope = remoteServers?.let { StandaloneMcpScope(it, { runtime.activeDistroId.value }).captureKey() }
                    ?: "local:${runtime.activeDistroId.value}"
                environments.open("mcp:catalog-probe@environment:$scope", "", null)
            } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return servers.map { it.withInstalledComponents(emptySet()) }
        }
        var distribution = ""
        val installed = try {
            val world = bound ?: owned
            distribution = if (world?.id?.backend == "ssh") "" else world?.id?.distribution ?: runtime.activeDistroId.value
            val ids = servers.filter { it.requiresInstall }.map { it.id }.toSet()
            if (world != null) probeMcpCatalog(ids) { world.execute(it) }
            else probeMcpCatalog(ids) { runtime.execute(it) }
        } finally {
            if (owned != null) withContext(NonCancellable) { owned.close() }
        }
        return servers.map { server ->
            val resolved = server.withInstalledComponents(installed)
            if (!server.requiresInstall || distribution.isBlank()) resolved else resolved.copy(
                catalogDistribution = distribution,
                catalogRevision = top.tianyan.app.core.common.McpCatalogRevisions.current(distribution, server.id),
            )
        }
    }
}

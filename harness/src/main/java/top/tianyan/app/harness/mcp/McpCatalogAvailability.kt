package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.withInstalledComponents
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory
import top.tianyan.app.runtime.environment.ExecutionEnvironment
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
    private val logger: AppLogger? = null,
) {
    private val probeMutex = Mutex()
    private val pendingCleanup = mutableMapOf<String, ExecutionEnvironment>()

    suspend fun resolve(servers: List<McpServerConfig>): List<McpServerConfig> =
        probeMutex.withLock { resolveLocked(servers) }

    private suspend fun resolveLocked(servers: List<McpServerConfig>): List<McpServerConfig> {
        if (servers.none { it.requiresInstall }) return servers
        val bound = currentCoroutineContext()[ExecutionEnvironmentContext]?.environment
        if (bound == null && environments == null && remoteServers?.activeId?.value != null)
            return servers.map { it.withInstalledComponents(emptySet()) }
        val owner = try {
            if (bound == null && environments != null) {
                val scope = remoteServers?.let { StandaloneMcpScope(it, { runtime.activeDistroId.value }).captureKey() }
                    ?: "local:${runtime.activeDistroId.value}"
                "mcp:catalog-probe@environment:$scope"
            } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return servers.map { it.withInstalledComponents(emptySet()) }
        }
        if (owner != null && !retryCleanup(owner)) {
            return servers.map { it.withInstalledComponents(emptySet()) }
        }
        val owned = try {
            if (owner != null) environments?.open(owner, "", null) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return servers.map { it.withInstalledComponents(emptySet()) }
        }
        var distribution = ""
        var cleanupSucceeded = true
        val installed = try {
            val world = bound ?: owned
            distribution = if (world?.id?.backend == "ssh") "" else world?.id?.distribution ?: runtime.activeDistroId.value
            val ids = servers.filter { it.requiresInstall }.map { it.id }.toSet()
            if (world != null) probeMcpCatalog(ids) { world.execute(it) }
            else probeMcpCatalog(ids) { runtime.execute(it) }
        } finally {
            if (owned != null && owner != null) {
                pendingCleanup[owner] = owned
                cleanupSucceeded = retryCleanup(owner)
            }
        }
        if (!cleanupSucceeded) return servers.map { it.withInstalledComponents(emptySet()) }
        return servers.map { server ->
            val resolved = server.withInstalledComponents(installed)
            if (!server.requiresInstall || distribution.isBlank()) resolved else resolved.copy(
                catalogDistribution = distribution,
                catalogRevision = top.tianyan.app.core.common.McpCatalogRevisions.current(distribution, server.id),
            )
        }
    }

    /** Failed probe resources remain owned; the next probe retries before opening a replacement. */
    private suspend fun retryCleanup(owner: String): Boolean = withContext(NonCancellable) {
        val environment = pendingCleanup[owner] ?: return@withContext true
        try {
            environment.close()
            pendingCleanup.remove(owner)
            true
        } catch (failure: Exception) {
            logger?.w("MCP 安装状态探测资源清理失败，保留环境等待下次重试", failure)
            false
        }
    }
}

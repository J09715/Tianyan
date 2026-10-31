package top.tianyan.app.harness.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.runtime.LinuxRuntime
import kotlin.time.Duration.Companion.milliseconds

/** Thin MCP registry coordinator; transports own protocol and process details. */
class McpManager(
    private val repository: McpServerRepository,
    private val stdio: McpStdioTransport,
    private val http: McpHttpTransport,
    private val commandBuilder: McpCommandBuilder,
    private val linuxRuntime: LinuxRuntime,
    private val logger: AppLogger,
    private val agentEventLogger: AgentEventLogger,
    private val remoteServers: top.tianyan.app.core.datastore.RemoteServerRepository? = null,
    private val catalogAvailability: McpCatalogAvailability = McpCatalogAvailability(linuxRuntime, remoteServers = remoteServers),
    private val toolPolicy: top.tianyan.app.core.datastore.McpToolPolicyPreferences? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standaloneScope = stdio.standaloneScope ?: remoteServers?.let { StandaloneMcpScope(it) }
    private val cache = ConcurrentHashMap<String, CachedMcpTools>()
    private val discoveryMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastErrors = ConcurrentHashMap<String, String>()
    /** 工具发现失败统一指数退避，成功即清零。 */
    private val discoveryCooldownUntil = ConcurrentHashMap<String, Long>()
    private val discoveryFailureStreak = ConcurrentHashMap<String, Int>()
    private val statuses = McpScopedConnectionStates(standaloneScope, scope, stdio.processStates)
    private val metadata = McpMetadataScopes { key, owned ->
        cache.remove(key); lastErrors.remove(key); discoveryCooldownUntil.remove(key); discoveryFailureStreak.remove(key)
        statuses.forgetKey(key); stdio.forgetProcessState(key); if (owned) discoveryMutexes.remove(key)
    }
    val connectionStates: StateFlow<Map<String, McpConnectionState>> = statuses.visible
    init { scope.expireCatalogTools(cache) { key -> lastErrors.remove(key); discoveryCooldownUntil.remove(key); discoveryFailureStreak.remove(key); if (cache[key] == null) statuses.forgetKey(key) } }
    init { scope.launch { stdio.toolListChanged.collect { key -> cache.remove(key); lastErrors.remove(key); discoveryCooldownUntil.remove(key); discoveryFailureStreak.remove(key) } } }

    /** 最近发现错误附退避剩余时间，避免把冷却误判为永久离线。 */
    fun getLastError(serverId: String): String? {
        val key = runCatching { standaloneScope?.key()?.let { "$serverId@environment:$it" } ?: serverId }
            .getOrElse { return it.message }
        return errorForKey(key)
    }
    suspend fun lastErrorForCall(serverId: String): String? = errorForKey(discoveryKey(McpServerConfig(serverId, serverId)))
    private fun errorForKey(key: String): String? {
        val error = lastErrors[key] ?: return null
        val remaining = remainingDiscoveryCooldownMs(key) ?: return error
        return "退避中，${remaining / 1000 + 1}s 后自动重试（$error）"
    }

    suspend fun checkConnection(server: McpServerConfig): Boolean = withContext(Dispatchers.IO) {
        val ready = catalogAvailability.resolve(listOf(server)).single()
        if (server.requiresInstall && ready.installationReady != true) return@withContext false
        // B1: 与 executeTool 一致先做 workspace 绑定，避免 fingerprint 乒乓导致进程反复重启；
        // 在线清掉旧错误、离线记录原因：状态点在线却仍挂旧故障描述会误导用户反复手动刷新。
        val bound = boundConfig(ready)
        val key = discoveryKey(bound)
        val result = withContext(McpConnectionScope(key)) { withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(bound).check(bound) } }
        if (result?.online == true) lastErrors.remove(key) else result?.error?.let { lastErrors[key] = it }
        state(server.id, if (result?.online == true) McpConnectionState.ONLINE else McpConnectionState.OFFLINE, key)
        result?.online == true
    }

    suspend fun invalidateServer(serverId: String) {
        removeScoped(cache, serverId)
        removeScoped(lastErrors, serverId)
        clearDiscoveryCooldown(serverId)
        effectiveServers().firstOrNull { it.id == serverId }?.let { closeTransportConnection(it) }
        statuses.forget(serverId)
    }

    suspend fun refreshConnections(probeSleeping: Boolean = true) = withContext(Dispatchers.IO) {
        val servers = effectiveServers()
        servers.filterNot { it.isEnabled }.forEach { server ->
            removeScoped(cache, server.id)
            removeScoped(lastErrors, server.id)
            clearDiscoveryCooldown(server.id)
            closeTransportConnection(server)
        }
        // 用户手动刷新是明确的重试意图：清掉退避，不让冷却把这次刷新变成空操作。
        val targets = servers.filter { it.isEnabled }.filter { server ->
            val key = discoveryKey(server)
            probeSleeping || stdio.processStates.value[key] != McpConnectionState.SLEEPING ||
                cache[key]?.fingerprint != fingerprint(boundConfig(server))
        }
        targets.forEach { clearDiscoveryCooldown(it.id); state(it.id, McpConnectionState.CHECKING) }
        servers.filterNot { it.isEnabled }.forEach { state(it.id, McpConnectionState.UNKNOWN) }
        coroutineScope {
            targets.map { server ->
                launch {
                    checkConnection(server)
                }
            }.joinAll()
        }
    }

    /** 开关仅影响当前服务，避免重新启动和探测列表里的其他 MCP 服务。 */
    suspend fun refreshConnection(serverId: String) = withContext(Dispatchers.IO) {
        val server = effectiveServers().firstOrNull { it.id == serverId } ?: return@withContext
        if (!server.isEnabled) {
            removeScoped(cache, serverId)
            removeScoped(lastErrors, serverId)
            clearDiscoveryCooldown(serverId)
            statuses.forget(serverId)
            closeTransportConnection(server)
            return@withContext
        }
        clearDiscoveryCooldown(serverId)
        state(serverId, McpConnectionState.CHECKING)
        checkConnection(server)
    }

    /** inspect 的缓存缺失兜底：就绪后按需发现，失败返回空列表。 */
    suspend fun discoverServerTools(serverId: String): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val server = effectiveServers().firstOrNull { it.id == serverId && it.isEnabled }
            ?: return@withContext emptyList()
        discoverServerIfNeeded(server)
    }

    suspend fun getActiveMcpTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        sweepDisabledServers()
        val enabledServers = effectiveServers().filter { it.isEnabled }
        if (enabledServers.isEmpty()) return@withContext emptyList()

        coroutineScope {
            enabledServers.map { server -> async { discoverServerIfNeeded(server) } }
        }.awaitAll().flatten()
    }

    /** 禁用服务的清理：清缓存/冷却并关闭传输连接（不产生任何新进程）。 */
    private suspend fun sweepDisabledServers() {
        effectiveServers().filterNot { it.isEnabled }.forEach { server ->
            removeScoped(cache, server.id)
            clearDiscoveryCooldown(server.id)
            closeTransportConnection(server)
        }
    }

    /** 每次先核对当前环境就绪状态，再复用缓存或按需连接。 */
    private suspend fun discoverServerIfNeeded(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        if (server.requiresInstall && catalogAvailability.resolve(listOf(server)).single().installationReady != true) {
            closeTransportConnection(server)
            removeScoped(cache, server.id)
            return@withContext emptyList()
        }
        val key = discoveryKey(server)
        val world = kotlinx.coroutines.currentCoroutineContext()[top.tianyan.app.runtime.environment.ExecutionEnvironmentContext]?.environment
        if (server.transportType == McpTransportType.STDIO && (world?.id?.backend?.let { it != "ssh" } ?: (remoteServers?.activeId?.value == null))) {
            val linuxReady = awaitLinuxRuntimeReady(linuxRuntime.state)
            if (!linuxReady) {
                lastErrors[key] = RUNTIME_NOT_READY_MSG
                state(server.id, McpConnectionState.OFFLINE, key)
                logger.w("MCP[${server.name}] 工具发现推迟：Linux runtime 尚未就绪，下一轮对话将重试")
                return@withContext emptyList()
            }
        }
        // B1: 用绑定后的配置计算 fingerprint 并发现，与 executeTool 路径一致，避免指纹乒乓
        val bound = boundConfig(server)
        val fingerprint = fingerprint(bound)
        cache[key]?.takeIf { it.fingerprint == fingerprint && it.isFresh() }?.tools
            ?: discoveryMutexes.getOrPut(metadata.mutexKey(key)) { Mutex() }.withLock {
                cache[key]?.takeIf { it.fingerprint == fingerprint && it.isFresh() }?.tools
                    ?.let { return@withLock it }
                // 退避窗口内直接跳过：持续故障的服务不该在每轮对话和每次
                // 子智能体初始化时都重新空耗一遍发现超时。
                remainingDiscoveryCooldownMs(key)?.let { remaining ->
                    state(server.id, McpConnectionState.OFFLINE, key)
                    logger.w(
                        "MCP[${server.name}] 工具发现冷却中（剩余 ${remaining / 1000}s，" +
                            "连续失败 ${discoveryFailureStreak[key] ?: 0} 次），本轮跳过；" +
                            "最近错误：${lastErrors[key] ?: "未知"}",
                    )
                    return@withLock emptyList<McpToolInfo>()
                }
                // 总超时兜底：沙箱会话拉起或 MCP 进程挂起时不能阻塞每轮对话（挂起是无日志的），
                // 超时按失败处理，本轮不注入该服务工具，退避到期后重试。
                agentEventLogger.log(DISCOVERY_LOG_SESSION, "McpDiscovery", "MCP[${server.name}] 工具发现开始（transport=${server.transportType}）")
                val startedAt = System.currentTimeMillis()
                cancellableResult { discoverWithTimeout(bound, key) }.onSuccess {
                    agentEventLogger.log(
                        DISCOVERY_LOG_SESSION,
                        "McpDiscovery",
                        "MCP[${server.name}] 发现 ${it.size} 个工具，耗时 ${System.currentTimeMillis() - startedAt}ms",
                    )
                    cache[key] = CachedMcpTools(fingerprint, it, bound.catalogDistribution, bound.catalogRevision)
                    lastErrors.remove(key)
                    clearDiscoveryCooldown(key)
                    state(server.id, McpConnectionState.ONLINE, key)
                }.onFailure {
                    agentEventLogger.log(
                        DISCOVERY_LOG_SESSION,
                        "McpDiscovery",
                        "MCP[${server.name}] 工具发现失败，耗时 ${System.currentTimeMillis() - startedAt}ms：${it.message ?: it::class.simpleName}",
                        it,
                    )
                    val msg = it.message ?: "工具发现异常"
                    lastErrors[key] = msg
                    val cooldownMs = recordDiscoveryFailure(key)
                    // 静默失败会让"模型不调用 MCP 工具"无从排查，这里必须留下线索；
                    // 冷却期内的重复失败只记一行，不再打整段堆栈刷屏。
                    val inCooldown = msg.contains("冷却中")
                    logger.w(
                        "MCP[${server.name}] 工具发现失败，本轮对话不注入该服务的工具" +
                            "（后续 ${cooldownMs / 1000}s 内不再重试）: $msg",
                        if (inCooldown) null else it,
                    )
                    cache.remove(key)
                    state(server.id, McpConnectionState.OFFLINE, key)
                }.getOrDefault(emptyList())
            }
    }
    /** 当前环境的可用服务摘要；不启动 MCP，工具数来自缓存并按用户策略过滤。 */
    suspend fun enabledServerSummaries(): List<EnabledServerSummary> = withContext(Dispatchers.IO) {
        sweepDisabledServers()
        val policies = toolPolicy?.snapshot().orEmpty()
        effectiveServers().filter { it.isEnabled }.map { server ->
            val cached = cache[discoveryKey(server)]?.takeIf { it.fingerprint == fingerprint(boundConfig(server)) && it.isFresh() }
            EnabledServerSummary(
                id = server.id,
                name = server.name,
                cachedToolCount = cached?.tools?.count { tool -> policies[server.id]?.get(tool.name)?.enabled != false } ?: 0,
                connected = cached != null, processState = if (server.transportType == McpTransportType.STDIO) stdio.processStates.value[discoveryKey(server)] else null,
            )
        }
    }

    /** UI 列表用：只读缓存工具数（按用户策略过滤），不启动进程、不做禁用清理。 */
    suspend fun cachedToolCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        val policies = toolPolicy?.snapshot().orEmpty()
        effectiveServers().filter { it.isEnabled }.associate { server -> server.id to (cache[discoveryKey(server)]?.takeIf { it.fingerprint == fingerprint(boundConfig(server)) && it.isFresh() }?.tools?.count { tool -> policies[server.id]?.get(tool.name)?.enabled != false } ?: 0) }
    }
    /** use_capability 的 inspect 数据源：只读缓存，绝不触发发现/连接。 */
    suspend fun cachedToolsOf(serverId: String): List<McpToolInfo> {
        val server = effectiveServers().firstOrNull { it.id == serverId && it.isEnabled } ?: return emptyList()
        return cache[discoveryKey(server)]?.takeIf { it.fingerprint == fingerprint(boundConfig(server)) && it.isFresh() }?.tools.orEmpty()
    }
    /** 按 (serverId, 工具名) 执行；就绪服务首次调用时发现。 */
    suspend fun executeCapabilityTool(
        serverId: String,
        toolName: String,
        arguments: JsonObject,
        workspace: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val server = effectiveServers().firstOrNull { it.id == serverId && it.isEnabled }
            ?: return@withContext false to "未找到或未启用的 MCP 服务：$serverId"
        val bound = boundConfig(server, workspace)
        val tools = discoverServerIfNeeded(bound)
        val tool = tools.firstOrNull { it.name == toolName }
            ?: return@withContext false to buildString {
                append("MCP[${server.name}] 没有名为 `$toolName` 的工具。")
                val available = tools.take(12).joinToString("、") { it.name }
                if (available.isNotBlank()) {
                    append("可用：$available")
                    if (tools.size > 12) append(" 等 ${tools.size} 个")
                    append("。")
                }
                append("可用 use_capability(action=\"inspect\", server=\"$serverId\") 查看工具清单与参数。")
            }
        if (toolPolicy?.policyFor(server.id, tool.name)?.enabled == false) return@withContext false to "MCP[${server.name}] 工具 `${tool.name}` 已在设置中禁用，本次调用未执行。"
        cancellableResult { transport(bound).execute(bound, tool.name, arguments) }
            .onFailure { logger.e("MCP[${server.name}] 工具 ${tool.name} 执行异常: ${it.message}", it) }
            .onSuccess { (ok, output) ->
                state(server.id, McpConnectionState.ONLINE, discoveryKey(bound))
                if (!ok) logger.w("MCP[${server.name}] 工具 ${tool.name} 返回错误: $output".take(500))
            }
            .getOrElse { false to "MCP 工具执行异常：${it.message ?: it::class.simpleName}" }
    }

    suspend fun discoverTools(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val ready = catalogAvailability.resolve(listOf(server)).single()
        check(!server.requiresInstall || ready.installationReady == true) { "MCP 未启用或当前环境未安装：${server.id}" }
        val bound = boundConfig(ready)
        transport(bound).discover(bound)
    }

    suspend fun testServer(server: McpServerConfig): Result<List<McpToolInfo>> = withContext(Dispatchers.IO) {
        val ready = catalogAvailability.resolve(listOf(server)).single()
        if (server.requiresInstall && ready.installationReady != true) return@withContext Result.failure(IllegalStateException("MCP 未启用或当前环境未安装：${server.id}"))
        // B1: 测试路径同样按绑定后配置发现并写缓存，保证缓存 fingerprint 与其他路径一致
        val bound = boundConfig(ready)
        // 设置页手动测试不受冷却限制，成功时也顺带清掉对话路径的退避。
        val key = discoveryKey(bound)
        clearDiscoveryCooldown(key)
        cancellableResult { discoverWithTimeout(bound, key) }
        .onSuccess {
            cache[key] = CachedMcpTools(fingerprint(bound), it, bound.catalogDistribution, bound.catalogRevision)
            state(server.id, McpConnectionState.ONLINE, key)
            // 测试成功 = 服务实际可用，旧错误与退避一并清除（getLastError 会持续显示旧故障误导用户）。
            lastErrors.remove(key)
            clearDiscoveryCooldown(key)
        }
        .onFailure { cache.remove(key); lastErrors[key] = it.message.orEmpty(); state(server.id, McpConnectionState.OFFLINE, key) }
    }

    suspend fun executeTool(
        fullToolName: String,
        arguments: JsonObject,
        workspace: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!fullToolName.startsWith("mcp__")) return@withContext false to "无效的 MCP 工具名称：$fullToolName"
        val servers = effectiveServers().filter { it.isEnabled }
        val parsedServerId = fullToolName.removePrefix("mcp__").substringBefore("__")
        val candidateServers = servers.filter { server ->
            server.id == parsedServerId || McpToolApiName.isServerPrefix(fullToolName, server.id)
        }
        val tools = if (candidateServers.size == 1) {
            discoverServerIfNeeded(candidateServers.single())
        } else {
            candidateServers.flatMap { discoverServerIfNeeded(it) }
        }
        val tool = tools.firstOrNull { McpToolApiName.matches(it, fullToolName) }
            ?: return@withContext false to "未找到 MCP 工具：$fullToolName"
        val server = servers.firstOrNull { it.id == tool.serverId }
            ?: return@withContext false to "未找到 MCP 服务：${tool.serverId}"
        if (toolPolicy?.policyFor(server.id, tool.name)?.enabled == false) return@withContext false to "MCP[${server.name}] 工具 `${tool.name}` 已在设置中禁用，本次调用未执行。"
        val bound = boundConfig(server, workspace)
        return@withContext cancellableResult { transport(bound).execute(bound, tool.name, arguments) }
            .onFailure { logger.e("MCP[${server.name}] 工具 ${tool.name} 执行异常: ${it.message}", it) }
            .onSuccess { (ok, output) ->
                state(server.id, McpConnectionState.ONLINE, discoveryKey(bound))
                if (!ok) logger.w("MCP[${server.name}] 工具 ${tool.name} 返回错误: $output".take(500))
            }
            .getOrElse { false to "MCP 工具执行异常：${it.message ?: it::class.simpleName}" }
    }

    private suspend fun discoverWithTimeout(server: McpServerConfig, key: String? = null): List<McpToolInfo> =
        withContext(McpConnectionScope(key ?: discoveryKey(server))) { withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(server).discover(server) } }
            ?: error("工具发现超时（${DISCOVERY_TIMEOUT_MS / 1000}s）：沙箱会话或 MCP 进程可能已挂起")

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private suspend fun state(id: String, state: McpConnectionState, key: String? = null) {
        val scoped = key ?: discoveryKey(McpServerConfig(id, id))
        metadata.retain(scoped) { statuses.update(state, scoped) }
    }

    /** 纯读退避剩余时间；过期自然失效，由下一次成功/失败更新。 */
    private fun remainingDiscoveryCooldownMs(serverId: String): Long? =
        discoveryCooldownUntil[serverId]?.minus(System.currentTimeMillis())?.takeIf { it > 0L }

    /** 记录一次发现失败并推进退避，返回本次生效的冷却时长。 */
    private fun recordDiscoveryFailure(serverId: String): Long {
        val streak = discoveryFailureStreak.merge(serverId, 1) { old, delta -> old + delta } ?: 1
        val cooldown = mcpDiscoveryCooldownMs(streak)
        discoveryCooldownUntil[serverId] = System.currentTimeMillis() + cooldown
        return cooldown
    }

    private fun clearDiscoveryCooldown(serverId: String) {
        removeScoped(discoveryCooldownUntil, serverId)
        removeScoped(discoveryFailureStreak, serverId)
    }
    private suspend fun discoveryKey(server: McpServerConfig) = scopedMcpKey(server, standaloneScope).also { metadata.register(it) }
    private fun removeScoped(map: ConcurrentHashMap<String, *>, serverId: String) {
        map.keys.removeIf { it == serverId || it.startsWith("$serverId@environment:") }
    }

    /** Bound owners always take precedence over another session's last workspace. */
    private suspend fun boundConfig(server: McpServerConfig, workspace: String? = null): McpServerConfig {
        val world = kotlinx.coroutines.currentCoroutineContext()[top.tianyan.app.runtime.environment.ExecutionEnvironmentContext]?.environment
        return commandBuilder.bindWorkspaceRepository(server, world?.workingDirectory() ?: workspace.orEmpty())
    }

    /** B10: 禁用/删除的 server 关闭其常驻传输资源（STDIO 进程 / HTTP legacy SSE 会话） */
    private suspend fun closeTransportConnection(server: McpServerConfig) {
        when (server.transportType) {
            McpTransportType.STDIO -> stdio.closeConnection(server.id)
            McpTransportType.SSE -> http.closeSession(server.id)
        }
    }
    private fun fingerprint(server: McpServerConfig): String =
        "${server.transportType}|${server.serverUrl.trim()}|${server.command}|${server.args}|${server.env.toSortedMap()}|${server.catalogRevision}"
    private fun transport(server: McpServerConfig): McpTransport = when (server.transportType) {
        McpTransportType.STDIO -> stdio
        McpTransportType.SSE -> http
    }

    private suspend fun effectiveServers(): List<McpServerConfig> =
        catalogAvailability.resolve(repository.servers.first())

    private companion object {
        /** 单服务器工具发现总超时：覆盖沙箱会话拉起 + initialize + tools/list，超时即本轮跳过注入。 */
        const val DISCOVERY_TIMEOUT_MS = 8_000L

        /** 工具发现无会话上下文，agent 事件日志用占位 sessionId。 */
        const val DISCOVERY_LOG_SESSION = "-"

        const val RUNTIME_NOT_READY_MSG = "Linux runtime is not ready. Call initialize() first."
    }
}

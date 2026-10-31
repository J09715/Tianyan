package top.tianyan.app.harness.mcp

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpIdlePolicy
import top.tianyan.app.core.model.McpToolInfo
import kotlin.time.Duration.Companion.milliseconds

internal class McpStdioChannelException(message: String) : IOException(message)

/** server 正常返回的 JSON-RPC error 响应（unknown tool / invalid params 等）：连接本身健康，错误应作为结果传回调用方 */
internal class McpJsonRpcErrorException(val code: Int, message: String) :
    IllegalStateException("MCP JSON-RPC $code: $message")

/** Reusable JSON-RPC sessions; subprocess ownership belongs to McpStdioChannelFactory. */
class McpStdioTransport(
    private val json: Json,
    private val commandBuilder: McpCommandBuilder,
    private val channelFactory: McpStdioChannelFactory,
    internal val standaloneScope: StandaloneMcpScope? = null,
    private val idleTimeoutMinutes: Flow<Int> = flowOf(McpIdlePolicy.DEFAULT_MINUTES),
    internal val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, McpStdioConnection>()
    private val startupMutexes = ConcurrentHashMap<String, Mutex>()
    private val downUntil = ConcurrentHashMap<String, Long>()
    private val processState = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    internal val processStates = processState.asStateFlow()

    /** server 主动声明工具目录变更（scoped server id）：上层据此丢弃缓存、下次调用重新发现。 */
    private val toolListChanges = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val toolListChanged: SharedFlow<String> = toolListChanges.asSharedFlow()
    internal fun forgetProcessState(key: String) { if (connections[key] == null) processState.update { it - key } }

    private fun publishState(connection: McpStdioConnection, state: McpConnectionState) {
        processState.update { states ->
            if (connections[connection.serverId] === connection) states + (connection.serverId to state) else states
        }
    }
    private fun setProcessState(id: String, state: McpConnectionState) { processState.update { it + (id to state) } }

    init {
        scope.launch {
            top.tianyan.app.core.common.McpCatalogRevisions.changes.collect { changes ->
                connections.values.forEach { conn ->
                    val revision = changes[top.tianyan.app.core.common.McpCatalogRevisions.Key(conn.distribution, conn.serverId.mcpServerKey())] ?: 0L
                    if (conn.distribution.isNotBlank() && revision > conn.revision) retire(conn)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS.milliseconds)
                runCatching { sweepIdleOnce(clock()) }
            }
        }
    }

    override suspend fun check(server: McpServerConfig): McpCheckResult {
        val conn = try {
            connection(server, bypassCooldown = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return McpCheckResult(false, t.message ?: t::class.simpleName)
        }
        return try {
            conn.withInitialized { McpCheckResult(true) }
        } catch (cancellation: CancellationException) {
            // B2: 取消（如 checkConnection 8s 超时、等待 initialize 握手锁被取消）不代表进程损坏， 不销毁连接——握手锁只挡毫秒级握手，绝不会误杀在飞的最长 600s 的 tools/call
            throw cancellation
        } catch (t: Throwable) {
            // B2: 仅传输层故障（进程退出/EOF/IO）才销毁；server 返回错误响应等本次探测失败保留连接
            if (isChannelFailure(t)) discardConnection(conn.serverId, conn)
            McpCheckResult(false, t.message ?: t::class.simpleName)
        } finally { conn.markActive(); conn.uses.release() }
    }

    override suspend fun discover(server: McpServerConfig): List<McpToolInfo> {
        val conn = connection(server)
        return try {
            conn.withInitialized {
                val response = requestReadOnly("tools/list", JsonObject(emptyMap()))
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpToolsListResponse.serializer(), it)
                } ?: error("MCP tools/list did not return a result")
                result.tools.map { dto -> dto.toInfo(server, json.encodeToString(JsonObject.serializer(), dto.inputSchema)) }
            }
        } catch (t: Throwable) {
            // B2: 取消（含发现总超时）与业务错误响应不销毁连接，仅传输层故障销毁
            if (t !is CancellationException && isChannelFailure(t)) discardConnection(conn.serverId, conn)
            throw t
        } finally { conn.markActive(); conn.uses.release() }
    }

    override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> {
        val conn = connection(server)
        return try {
            conn.withInitialized {
                val params = json.encodeToJsonElement(McpCallToolParams.serializer(), McpCallToolParams(toolName, arguments))
                val response = requestCall("tools/call", params)
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpCallToolResult.serializer(), it)
                } ?: error("MCP tools/call did not return a result")
                !result.isError to result.content.joinToString("\n") { it.text.orEmpty() }
                    .ifBlank { if (result.isError) "执行失败" else "执行成功" }
            }
        } catch (e: McpJsonRpcErrorException) {
            // B5: server 返回的 JSON-RPC 错误响应是正常协议行为，连接健康； 错误作为结果返回给模型，而不是销毁子进程重启（丢失有状态上下文）
            false to (e.message ?: "MCP JSON-RPC 错误")
        } catch (cancellation: CancellationException) {
            // B2: 调用被取消不代表进程损坏，保留连接
            throw cancellation
        } catch (t: Throwable) {
            // B5: 仅传输层失败（EOF/IO/进程死亡）才 discard，其余（序列化/业务异常等）透传且保留连接
            if (isChannelFailure(t)) discardConnection(conn.serverId, conn)
            throw t
        } finally { conn.markActive(); conn.uses.release() }
    }

    private fun isChannelFailure(t: Throwable): Boolean = t is McpStdioChannelException || t is IOException

    suspend fun closeConnection(serverId: String) = startupMutexes.getOrPut(serverId) { Mutex() }.withLock {
        val ids = (connections.keys + downUntil.keys + processStates.value.keys + serverId).filter { it == serverId || it.startsWith("$serverId@environment:") }
        closeMcpActions(listOf(
            { closeMcpResources(ids) { id -> downUntil.remove(id); discardConnection(id) } },
            { channelFactory.closePending(serverId) },
        ))
        processState.update { it - ids.toSet() }
    }

    private suspend fun discardConnection(serverId: String, failed: McpStdioConnection? = null) {
        withContext(NonCancellable + Dispatchers.IO) {
            // 释放成功后按引用删除；失败保留重试入口，迟到的旧连接清理不能误删替代连接。
            val target = failed ?: connections[serverId] ?: return@withContext
            target.close()
            publishState(target, McpConnectionState.OFFLINE)
            connections.remove(serverId, target)
        }
    }
    internal suspend fun sweepIdleOnce(nowMs: Long): Int {
        val timeout = McpIdlePolicy.timeoutMillis(idleTimeoutMinutes.first())
        var closed = 0
        var failure: Throwable? = null
        val entries = connections.entries.toList()
        for ((id, connection) in entries) {
            if (connection.inFlight) continue
            if (nowMs - connection.lastActivityMs < timeout) continue
            // 获取连接与登记使用租约共用启动锁；锁内复核活跃时间，避免清扫误关新请求。
            val removed = try { startupMutexes.getOrPut(id.mcpServerKey()) { Mutex() }.withLock {
                !connection.inFlight &&
                    nowMs - connection.lastActivityMs >= timeout &&
                    connections[id] === connection && run {
                        discardConnection(id, connection)
                        setProcessState(id, McpConnectionState.SLEEPING)
                        true
                    }
            } } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    setProcessState(id, McpConnectionState.OFFLINE)
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                    false // Preserve failed ownership; sweep other servers before reporting the failure.
                }
            if (removed) {
                closed++
            }
        }
        failure?.let { throw it }
        return closed
    }

    private fun retire(conn: McpStdioConnection) = conn.uses.retire(scope) {
        startupMutexes.getOrPut(conn.serverId.mcpServerKey()) { Mutex() }.withLock {
            if (connections[conn.serverId] === conn) discardConnection(conn.serverId, conn)
        }
    }
    private suspend fun connection(server: McpServerConfig, bypassCooldown: Boolean = false): McpStdioConnection {
        val scopedId = scopedMcpKey(server, standaloneScope)
        while (true) {
            var waiting: McpStdioConnection? = null
            val acquired = startupMutexes.getOrPut(server.id.mcpServerKey()) { Mutex() }.withLock {
                val revision = if (server.catalogDistribution.isBlank()) server.catalogRevision else
                    top.tianyan.app.core.common.McpCatalogRevisions.current(server.catalogDistribution, server.id.mcpServerKey())
                val scoped = server.copy(id = scopedId, catalogRevision = revision)
                val old = connections[scopedId]
                if (old != null && (old.uses.retiring || old.fingerprint != commandBuilder.fingerprint(scoped))) {
                    retire(old)
                    if (old.uses.busy()) { waiting = old; return@withLock null }
                }
                val conn = connectionLocked(scoped, bypassCooldown)
                if (scoped.catalogDistribution.isNotBlank() && top.tianyan.app.core.common.McpCatalogRevisions.current(scoped.catalogDistribution, server.id.mcpServerKey()) != revision) { retire(conn); null }
                else conn.takeIf { it.uses.acquire() }
            }
            if (acquired != null) return acquired
            waiting?.uses?.awaitIdle()
        }
    }
    private suspend fun connectionLocked(server: McpServerConfig, bypassCooldown: Boolean): McpStdioConnection {
        connections[server.id]?.takeIf { !it.uses.retiring && it.dead == null && it.channel.isAlive && it.fingerprint == commandBuilder.fingerprint(server) }?.let {
            it.markActive()
            return it
        }
        val now = System.currentTimeMillis()
        if (!bypassCooldown) {
            val until = downUntil[server.id]
            if (until != null && now < until) {
                throw IllegalStateException(
                    "MCP[" + server.name + "] 沙箱会话拉起冷却中（剩余 " + (until - now) / 1000 + "s），跳过本次连接",
                )
            }
        }
        discardConnection(server.id)
        setProcessState(server.id, McpConnectionState.WAKING)
        val channel = try {
            withTimeoutOrNull(STARTUP_TIMEOUT_MS.milliseconds) {
                channelFactory.open(server)
            } ?: run {
                downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                error("MCP 沙箱会话启动超时（" + STARTUP_TIMEOUT_MS / 1000 + "s）：" + server.command)
            }
        } catch (t: Throwable) {
            setProcessState(server.id, if (t is CancellationException) McpConnectionState.UNKNOWN else McpConnectionState.OFFLINE)
            downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            throw t
        }
        downUntil.remove(server.id)
        return McpStdioConnection(json, scope, { requestTimeoutOverrideMs }, { discardConnection(it.serverId, it) }, ::publishState, clock, server.id, channel, commandBuilder.fingerprint(server), server.catalogDistribution, server.catalogRevision, { toolListChanges.tryEmit(server.id) }).also { connection ->
            connections[server.id] = connection
            startReaderLoop(connection)
            try { connection.ownership = currentCoroutineContext()[ExecutionEnvironmentContext]?.own { discardConnection(server.id, connection) } }
            catch (t: Throwable) { rollbackMcpFailure(t) { discardConnection(server.id, connection) } }
        }
    }
    private fun startReaderLoop(connection: McpStdioConnection) {
        connection.readerJob = scope.launch {
            try {
                while (true) connection.route(connection.channel.incoming.receive())
            } catch (cancellation: CancellationException) {
                throw cancellation // close() 主动取消，等待者已由 close 收尾
            } catch (t: Throwable) {
                // B5/B2: EOF/进程退出/IO 是传输层故障，只有此类失败才值得销毁重建
                connection.failPending(McpStdioChannelException("MCP 通道读取终止：" +
                    if (t is kotlinx.coroutines.channels.ClosedReceiveChannelException) "子进程输出已结束（无诊断输出）" else (t.message ?: t::class.simpleName)))
            }
        }
    }
    internal fun injectConnectionForTest(server: McpServerConfig, channel: McpStdioChannel) {
        McpStdioConnection(json, scope, { requestTimeoutOverrideMs }, { discardConnection(it.serverId, it) }, ::publishState, clock, server.id, channel, commandBuilder.fingerprint(server), server.catalogDistribution, server.catalogRevision, { toolListChanges.tryEmit(server.id) }).also { connection ->
            connections[server.id] = connection
            startReaderLoop(connection)
        }
    }

    @Volatile internal var requestTimeoutOverrideMs: Long? = null

    companion object {
        internal const val STARTUP_TIMEOUT_MS = 3500L
        private const val FAILURE_COOLDOWN_MS = 3L * 60L * 1000L
        internal const val REQUEST_TIMEOUT_MS = 120_000L
        internal const val CALL_REQUEST_TIMEOUT_MS = 600_000L
        internal const val MAX_BUFFERED_LINES = 64
        internal const val MAX_FRAME_CHARS = 1 * 1024 * 1024
        /** STDIO 超长行落盘转存硬顶：8MB（超过则回退熔断跳过），与 McpResponseSizeLimiter 的 SSE 硬顶对齐。 */
        internal const val MAX_SPILL_FRAME_CHARS = 8L * 1024 * 1024
        internal const val MAX_IGNORED_FRAMES = 256
        internal const val IDLE_TIMEOUT_MS = 10L * 60L * 1000L
        internal const val SWEEP_INTERVAL_MS = 60L * 1000L
    }
}

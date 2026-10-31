package top.tianyan.app.harness.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import top.tianyan.app.core.model.McpConnectionState
import kotlin.time.Duration.Companion.milliseconds

/** 只读请求（tools/list 等）超时：连接本身仍可用，不应按通道故障处理。 */
internal class McpRequestTimeoutException(message: String) : IllegalStateException(message)

/** Requests multiplex by ID; writes and initialization serialize without locking long calls. */
internal class McpStdioConnection(
    private val json: Json,
    private val scope: CoroutineScope,
    private val timeoutOverrideMs: () -> Long?,
    private val discard: suspend (McpStdioConnection) -> Unit,
    private val publish: (McpStdioConnection, McpConnectionState) -> Unit,
    private val clockMs: () -> Long,
    val serverId: String,
    val channel: McpStdioChannel,
    val fingerprint: String,
    val distribution: String,
    val revision: Long,
    private val onToolsChanged: () -> Unit = {},
) {
    val uses = McpConnectionUses()
    var ownership: AutoCloseable? = null
    private val cleanup = McpResourceCleanup()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
    private val initMutex = Mutex()
    private var initialized = false

    /** 连续不可路由帧（垃圾/回显/无主响应）计数：成功路由任何响应即清零——毒化只判定 "通道持续输出不可解析"。旧实现每请求重置；多路复用后由读泵统一计数，若改为连接 生命周期累计，PTY 回显与 server 进度通知随正常流量穿插也会在长会话中累积到阈值， 毒杀健康连接。仅读泵线程访问。 */
    private var consecutiveIgnoredFrames = 0

    @Volatile internal var readerJob: Job? = null

    @Volatile internal var dead: String? = null

    @Volatile internal var lastActivityMs: Long = clockMs()
    val inFlight: Boolean get() = uses.busy() || pending.isNotEmpty()
    fun markActive() {
        lastActivityMs = clockMs()
    }
    suspend fun <T> withInitialized(block: suspend McpStdioConnection.() -> T): T {
        markActive()
        initMutex.withLock {
            if (!initialized) {
                markActive()
                val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
                val response = requestInternal("initialize", params, McpStdioTransport.REQUEST_TIMEOUT_MS, dropConnectionOnTimeout = true)
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpInitializeResult.serializer(), it)
                } ?: error("MCP initialize did not return a result")
                require(result.protocolVersion.isNotBlank())
                notify("notifications/initialized")
                initialized = true
                publish(this, McpConnectionState.ONLINE)
            }
        }
        return block()
    }

    suspend fun requestReadOnly(method: String, params: JsonElement): JsonRpcResponse =
        requestInternal(method, params, McpStdioTransport.REQUEST_TIMEOUT_MS, dropConnectionOnTimeout = false)

    suspend fun requestCall(method: String, params: JsonElement): JsonRpcResponse =
        requestInternal(method, params, McpStdioTransport.CALL_REQUEST_TIMEOUT_MS, dropConnectionOnTimeout = true)

    private suspend fun requestInternal(
        method: String,
        params: JsonElement,
        timeoutMs: Long,
        dropConnectionOnTimeout: Boolean,
    ): JsonRpcResponse {
        dead?.let { throw McpStdioChannelException("MCP 通道已失效：" + it) }
        markActive()
        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<JsonRpcResponse>()
        pending[id] = waiter
        try {
            writeLine(json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params)))
        } catch (cancellation: CancellationException) {
            // 写锁上被取消 ≠ 通道故障：必须原样重抛，否则用户取消会误判为传输失败销毁连接； 等待者同步移除，否则 inFlight 永真、空闲清扫永不回收此连接
            pending.remove(id)
            throw cancellation
        } catch (t: Throwable) {
            pending.remove(id)
            throw McpStdioChannelException("MCP 请求 " + method + " 写入失败：" + (t.message ?: t::class.simpleName))
        }
        // 超时必须以普通异常而非 TimeoutCancellationException 暴露；是否销毁连接取决于请求是否可能已产生副作用。
        val response = try {
            withTimeoutOrNull((timeoutOverrideMs() ?: timeoutMs).milliseconds) { waiter.await() }
        } catch (cancellation: CancellationException) {
            pending.remove(id)
            withContext(NonCancellable) { cancelRequest(id, "cancelled by client") }
            throw cancellation
        } finally {
            pending.remove(id)
        } ?: run {
            val message = "MCP 请求 $method 响应超时（${timeoutMs / 1000}s）"
            if (dropConnectionOnTimeout) {
                // tools/call 可能已产生副作用，不能重放：丢弃整条连接，下次从干净会话重建。
                failPending(McpStdioChannelException(message))
                scope.launch { discard(this@McpStdioConnection) }
                throw McpStdioChannelException(message)
            }
            // 只读请求超时不会污染会话：通知 server 取消该请求，连接保留给其他在途请求复用。
            withContext(NonCancellable) { cancelRequest(id, "timeout after ${timeoutMs}ms") }
            throw McpRequestTimeoutException(message)
        }
        // B5: server 的 JSON-RPC error 响应用专用异常承载，调用方将其作为结果返回而非传输故障
        response.error?.let { throw McpJsonRpcErrorException(it.code, it.message) }
        return response
    }

    fun route(rawLine: String) {
        val element = runCatching { json.parseToJsonElement(rawLine) }.getOrNull()
        if (element == null) {
            countIgnored("MCP 输出了过多无效 JSON 行")
            return
        }
        if (element is JsonObject && element.containsKey("method")) {
            // Valid server notifications have no id and are allowed by JSON-RPC; they must not poison a healthy stream. A request echo with an id remains suspicious.
            if (!element.containsKey("id")) {
                // 工具目录变化通知必须让缓存失效，否则长连接会一直用旧清单。
                if ((element["method"] as? JsonPrimitive)?.contentOrNull == "notifications/tools/list_changed") {
                    onToolsChanged()
                }
                return
            }
            countIgnored("MCP 输出了过多请求回显")
            return
        }
        val parsed = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), element) }.getOrNull()
            ?: run {
                countIgnored("MCP 输出了过多无效响应帧")
                return
            }
        val waiter = parsed.id?.let { pending.remove(it) }
        if (waiter != null) {
            consecutiveIgnoredFrames = 0
            waiter.complete(parsed)
            return
        }
        if (parsed.id == null && parsed.error != null && pending.isNotEmpty()) {
            // 熔断帧或无 ID 错误响应：优先路由给在途请求，避免请求白白等待最长 600s 超时
            consecutiveIgnoredFrames = 0
            if (pending.size == 1) {
                val entry = pending.entries.firstOrNull() ?: return
                if (pending.remove(entry.key, entry.value)) {
                    entry.value.complete(parsed.copy(id = entry.key))
                }
            } else {
                // 多请求在途且无法确认归属时，为避免挂起，令全部在途请求快速失败（按快照条件原子移除）
                val entries = pending.entries.toList()
                entries.forEach { (id, waiter) ->
                    if (pending.remove(id, waiter)) {
                        waiter.complete(parsed.copy(id = id))
                    }
                }
            }
            return
        }
        // 无等待者的响应 = 超时后的迟到响应或 id 错乱的 server：计入连续不可路由帧—— 迟到响应每次超时至多一条且随后必有成功路由清零；持续错乱说明 server 坏了
        countIgnored("MCP 输出了过多无主响应帧")
    }

    private fun countIgnored(reason: String) {
        // 输出持续不可解析说明通道已"中毒"，后续请求同样无法工作，判定为传输层故障
        if (++consecutiveIgnoredFrames > McpStdioTransport.MAX_IGNORED_FRAMES) {
            failPending(McpStdioChannelException(reason))
        }
    }

    fun failPending(cause: Throwable) {
        if (dead == null) {
            dead = cause.message ?: cause::class.simpleName
            publish(this, McpConnectionState.OFFLINE)
        }
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }
    private suspend fun notify(method: String, params: JsonElement? = null) =
        writeLine(json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method, params = params)))

    /** 尽力取消：server 可能已经收到请求，通知失败不能影响调用方对超时/取消的处理。 */
    private suspend fun cancelRequest(requestId: String, reason: String) {
        val params = buildJsonObject {
            put("requestId", requestId)
            put("reason", reason)
        }
        runCatching {
            withTimeoutOrNull(CANCEL_NOTIFY_TIMEOUT_MS.milliseconds) {
                notify("notifications/cancelled", params)
            }
        }
    }

    private suspend fun writeLine(payload: String) = writeMutex.withLock { channel.writeLine(payload) }

    suspend fun close() = cleanup.close {
        if (dead == null) dead = "connection closed"
        readerJob?.cancelAndJoin()
        failPending(McpStdioChannelException("MCP 通道已关闭"))
        channel.close()
        ownership?.close()
    }
}

private const val CANCEL_NOTIFY_TIMEOUT_MS = 500L

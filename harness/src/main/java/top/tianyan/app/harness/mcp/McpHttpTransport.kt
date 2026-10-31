package top.tianyan.app.harness.mcp

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.utf8Size
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.harness.mcp.oauth.McpOAuthTokenProvider
import top.tianyan.app.harness.mcp.server.BuiltinBrowserMcpAccess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import top.tianyan.app.core.model.McpAuthMode

/**
 * 远程 MCP 传输：同时支持 Streamable HTTP（2025-03-26+）与 legacy HTTP+SSE（2024-11-05）。
 *
 * 连接策略：
 * - 会话按服务 id 复用，一次握手（initialize + notifications/initialized）后所有请求直接复用；
 * - 自动协商：URL 以 /sse 结尾优先尝试 legacy SSE，否则先试 Streamable HTTP，失败回落另一种；
 * - initialize/tools-list 等只读发现请求遇到传输故障时允许重建并重试一次；
 * - tools/call 仅在 401/403（OAuth 模式）刷新令牌后重试一次；响应丢失等传输故障不重试，避免重复执行副作用。
 */
class McpHttpTransport(
    client: OkHttpClient,
    private val json: Json,
    private val logger: AppLogger,
    private val oauthTokens: McpOAuthTokenProvider? = null,
    private val spillDirectory: File? = null,
) : McpTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, HttpSession>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    /** 连接失败冷却：服务 id → 冷却截止时间戳。冷却期内直接快速失败，不再空耗连接超时。 */
    private val downUntil = ConcurrentHashMap<String, Long>()

    // 握手/列表用短超时；工具调用可能耗时较长；SSE 长连接读流不设超时。
    // connectTimeout 单独收紧：本地端口无服务时（ECONNREFUSED 被系统延迟上报）也要快速失败。
    private val fastClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(FAST_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    /** POSTs that may already have taken effect must not be transparently replayed by OkHttp. */
    private val nonRetryingFastClient = fastClient.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val callClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CALL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val sseClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CALL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun check(server: McpServerConfig) = withContext(Dispatchers.IO) {
        try {
            // 设置页手动测试连接不受冷却限制
            ensureSession(server, bypassCooldown = true)
            McpCheckResult(true)
        } catch (c: CancellationException) {
            // B4: 结构化取消必须透传，不能被吞成 false
            throw c
        } catch (t: Throwable) {
            McpCheckResult(false, t.message ?: t::class.simpleName)
        }
    }

    override suspend fun discover(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        withRetryableSession(server) { session ->
            val response = exchange(session, "tools/list", JsonObject(emptyMap()))
            val result = response.result?.let { json.decodeFromJsonElement(McpToolsListResponse.serializer(), it) }
                ?: error("MCP tools/list did not return a result")
            result.tools.map { it.toInfo(server, json.encodeToString(JsonObject.serializer(), it.inputSchema)) }
        }
    }

    override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val session = ensureSession(server)
            try {
                callTool(session, toolName, arguments)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!isTransportFailure(t)) throw t
                dropSession(server.id, session)
                if (t is McpHttpStatusException && t.statusCode in setOf(401, 403) &&
                    server.authMode == McpAuthMode.OAUTH
                ) {
                    // 401/403 表示请求在执行前被拒绝，刷新令牌后重试不会重复副作用；
                    // 其他传输故障（响应丢失）仍不重试 tools/call。
                    logger.w("MCP[${server.name}] tools/call 认证失效（HTTP ${t.statusCode}），刷新令牌后重试一次")
                    oauthTokens?.forceRefresh(server.id)
                    val retrySession = ensureSession(server)
                    try {
                        callTool(retrySession, toolName, arguments)
                    } catch (retry: Throwable) {
                        if (retry is CancellationException) throw retry
                        if (isTransportFailure(retry)) dropSession(server.id, retrySession)
                        throw retry
                    }
                } else {
                    throw IOException("MCP 工具响应丢失；为避免副作用重复，未自动重试 tools/call", t)
                }
            }
        }

    private suspend fun callTool(session: HttpSession, toolName: String, arguments: JsonObject): Pair<Boolean, String> {
        val params = json.encodeToJsonElement(
            McpCallToolParams.serializer(),
            McpCallToolParams(toolName, arguments),
        )
        val response = exchange(session, "tools/call", params)
        val result = response.result?.let { json.decodeFromJsonElement(McpCallToolResult.serializer(), it) }
            ?: error("MCP tools/call did not return a result")
        return !result.isError to result.content.joinToString("\n") { it.text.orEmpty() }
            .ifBlank { if (result.isError) "执行失败" else "执行成功" }
    }

    // ---------- 会话管理 ----------

    private suspend fun <T> withRetryableSession(server: McpServerConfig, block: suspend (HttpSession) -> T): T {
        val session = ensureSession(server)
        return try {
            block(session)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!isTransportFailure(t)) throw t
            logger.w("MCP[${server.name}] 会话失效（${t.message}），重建后重试一次")
            dropSession(server.id, session)
            if (t is McpHttpStatusException && t.statusCode in setOf(401, 403) &&
                server.authMode == McpAuthMode.OAUTH
            ) {
                oauthTokens?.forceRefresh(server.id)
            }
            block(ensureSession(server))
        }
    }

    private class McpHttpStatusException(val statusCode: Int) : IOException("MCP HTTP $statusCode")

    private fun isTransportFailure(t: Throwable): Boolean =
        t is IOException || (t is IllegalStateException && t.message?.startsWith("MCP HTTP ") == true)

    private suspend fun ensureSession(server: McpServerConfig, bypassCooldown: Boolean = false): HttpSession =
        sessionMutexes.getOrPut(server.id) { Mutex() }.withLock {
            ensureSessionLocked(server, bypassCooldown)
        }

    private suspend fun ensureSessionLocked(server: McpServerConfig, bypassCooldown: Boolean): HttpSession {
        val url = effectiveUrlOf(server)
        val currentHeaders = headersOf(server)
        sessions[server.id]?.let { existing ->
            // Token rotation/logout must never reuse a session initialized with an old Bearer.
            if (existing.serverUrl == url && existing.headers == currentHeaders && existing.isOpen) return existing
            dropSession(server.id, existing)
        }
        // 冷却期内直接快速失败：上一轮刚整体握手失败，短时间内大概率仍是同一个故障
        // （进程未启动 / 端口不对），不值得每轮对话都空耗连接超时。
        val now = System.currentTimeMillis()
        if (!bypassCooldown) {
            val until = downUntil[server.id]
            if (until != null && now < until) {
                throw IllegalStateException(
                    "MCP[${server.name}] 连接冷却中（剩余 ${(until - now) / 1000}s），跳过本次连接；" +
                        "请确认服务端已启动后再在设置页手动测试",
                )
            }
        }
        val preferLegacy = runCatching {
            validatedMcpHttpEndpoint(url).encodedPath.trimEnd('/').endsWith("sse")
        }.getOrDefault(false)
        val attempts = if (preferLegacy) {
            listOf(TransportMode.LEGACY_SSE, TransportMode.STREAMABLE_HTTP)
        } else {
            listOf(TransportMode.STREAMABLE_HTTP, TransportMode.LEGACY_SSE)
        }
        val failures = mutableListOf<String>()
        for (mode in attempts) {
            val label = modeLabel(mode)
            try {
                val session = if (mode == TransportMode.STREAMABLE_HTTP) {
                    createStreamableSession(server)
                } else {
                    createLegacySession(server)
                }
                sessions[server.id] = session
                downUntil.remove(server.id)
                logger.i("MCP[${server.name}] 连接成功（$label）endpoint=${session.endpoint}")
                return session
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failures += "$label: ${t.message ?: t::class.simpleName}"
                logger.w("MCP[${server.name}] $label 握手失败: ${t.message}", t)
            }
        }
        // B7: bypassCooldown（设置页手动测试）失败不写冷却，避免一次手动测试失败后对话发现快速失败 5 分钟
        if (!bypassCooldown) {
            downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        }
        throw IllegalStateException("MCP HTTP 连接失败（${failures.joinToString("；")}）")
    }

    /** B10: 禁用/删除 server 时关闭其会话（含 legacy SSE 长连接）并清冷却记录 */
    suspend fun closeSession(serverId: String) {
        sessionMutexes.getOrPut(serverId) { Mutex() }.withLock {
            downUntil.remove(serverId)
            sessions.remove(serverId)?.legacy?.close()
        }
    }

    private fun dropSession(serverId: String, session: HttpSession) {
        sessions.remove(serverId, session)
        session.legacy?.close()
    }

    /**
     * 合并经过校验的自定义请求头及 OAuth / 内置自环凭据。
     */
    private suspend fun headersOf(server: McpServerConfig): Map<String, String> =
        resolveMcpHttpHeaders(server, oauthTokens)

    /**
     * 解析目标 server 的实际 URL。内置 browser server 的首选端口被占用时，
     * server 会顺延绑定到相邻端口（见 [McpServerRuntime]），此时静态预设 URL 已过期，
     * 以 [BuiltinBrowserMcpAccess.port] 为准替换 URL 中的端口。
     */
    private fun effectiveUrlOf(server: McpServerConfig): String =
        resolveBrowserEffectiveUrl(server.serverUrl.trim(), server.id, BuiltinBrowserMcpAccess.port)

    private suspend fun createStreamableSession(server: McpServerConfig): HttpSession {
        val url = effectiveUrlOf(server)
        val endpoint = validatedMcpHttpEndpoint(url)
        val headers = headersOf(server)
        val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
        val init = postStreamable(endpoint, sessionId = null, method = "initialize", params = params, headers = headers)
        val result = init.response.result?.let { json.decodeFromJsonElement(McpInitializeResult.serializer(), it) }
            ?: error("MCP initialize did not return a result")
        val session = HttpSession(
            mode = TransportMode.STREAMABLE_HTTP,
            endpoint = endpoint,
            sessionId = init.sessionId,
            protocolVersion = result.protocolVersion,
            serverUrl = url,
            headers = headers,
        )
        postNotification(session, "notifications/initialized")
        return session
    }

    private suspend fun createLegacySession(server: McpServerConfig): HttpSession {
        val url = effectiveUrlOf(server)
        val sseUrl = validatedMcpHttpEndpoint(url)
        val headers = headersOf(server)
        val channel = LegacySseChannel(sseUrl, sseClient, json, scope, headers)
        try {
            val messageEndpoint = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { channel.awaitEndpoint() }
                ?: error("Legacy SSE 握手超时：${HANDSHAKE_TIMEOUT_MS / 1000}s 内未收到 endpoint 事件")
            val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
            val init = postViaLegacy(channel, "initialize", params, headers)
            val result = init.result?.let { json.decodeFromJsonElement(McpInitializeResult.serializer(), it) }
                ?: error("MCP initialize did not return a result")
            postViaLegacyNotification(channel, "notifications/initialized", headers)
            return HttpSession(
                mode = TransportMode.LEGACY_SSE,
                endpoint = messageEndpoint,
                sessionId = null,
                protocolVersion = result.protocolVersion,
                serverUrl = url,
                legacy = channel,
                headers = headers,
            )
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    // ---------- 请求执行 ----------

    private suspend fun exchange(session: HttpSession, method: String, params: JsonElement): JsonRpcResponse =
        when (session.mode) {
            TransportMode.STREAMABLE_HTTP -> postStreamable(
                endpoint = session.endpoint,
                sessionId = session.sessionId,
                method = method,
                params = params,
                longRunning = method == "tools/call",
                headers = session.headers,
            ).response
            TransportMode.LEGACY_SSE -> postViaLegacy(
                channel = session.legacy ?: error("Legacy SSE 会话已关闭"),
                method = method,
                params = params,
                headers = session.headers,
            )
        }

    private suspend fun postStreamable(
        endpoint: HttpUrl,
        sessionId: String?,
        method: String,
        params: JsonElement,
        longRunning: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): StreamableExchange {
        val id = UUID.randomUUID().toString()
        val payload = json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
        val request = requestBuilder(endpoint, sessionId, headers)
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .post(payload.toRequestBody(JSON))
            .build()
        val client = if (longRunning) callClient else fastClient
        return client.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) throw McpHttpStatusException(response.code)
            val rpc = readResponse(response, id, isToolCall = method == "tools/call")
            rpc.error?.let { error("MCP JSON-RPC ${it.code}: ${it.message}") }
            StreamableExchange(rpc, response.header("Mcp-Session-Id"))
        }
    }

    private suspend fun postNotification(session: HttpSession, method: String) {
        val payload = json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method))
        val request = requestBuilder(session.endpoint, session.sessionId, session.headers)
            .header("MCP-Protocol-Version", session.protocolVersion)
            .post(payload.toRequestBody(JSON))
            .build()
        fastClient.newCall(request).executeCancellable().use {
            if (!it.isSuccessful) throw McpHttpStatusException(it.code)
        }
    }

    private suspend fun postViaLegacy(
        channel: LegacySseChannel,
        method: String,
        params: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): JsonRpcResponse {
        val id = UUID.randomUUID().toString()
        val payload = json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
        val deferred = channel.register(id)
        return try {
            postLegacyPayload(channel, payload, retryable = method != "tools/call", headers = headers)
            withTimeoutOrNull(CALL_TIMEOUT_MS.milliseconds) { channel.await(id, deferred) }
                ?: error("MCP 响应超时（${CALL_TIMEOUT_MS / 1000}s）")
        } catch (t: Throwable) {
            channel.unregister(id)
            throw t
        }
    }

    private suspend fun postViaLegacyNotification(channel: LegacySseChannel, method: String, headers: Map<String, String> = emptyMap()) {
        val payload = json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method))
        postLegacyPayload(channel, payload, retryable = true, headers = headers)
    }

    private suspend fun postLegacyPayload(channel: LegacySseChannel, payload: String, retryable: Boolean, headers: Map<String, String> = emptyMap()) {
        val endpoint = channel.messageEndpoint ?: error("Legacy SSE 消息端点未就绪")
        val request = Request.Builder().url(endpoint).header("Accept", ACCEPT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(payload.toRequestBody(JSON)).build()
        val client = if (retryable) fastClient else nonRetryingFastClient
        client.newCall(request).executeCancellable().use {
            if (!it.isSuccessful) throw McpHttpStatusException(it.code)
        }
    }

    private suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun requestBuilder(endpoint: HttpUrl, sessionId: String?, headers: Map<String, String> = emptyMap()) = Request.Builder()
        .url(endpoint).header("Accept", ACCEPT)
        .apply {
            sessionId?.let { header("Mcp-Session-Id", it) }
            headers.forEach { (name, value) -> header(name, value) }
        }

    // ---------- 响应解析 ----------

    private fun readResponse(response: Response, requestId: String, isToolCall: Boolean = false): JsonRpcResponse =
        if (response.header("Content-Type").orEmpty().lowercase().startsWith("text/event-stream")) {
            readSse(response, requestId)
        } else {
            val body = readLimited(response, requestId, isToolCall)
            if (body.isBlank()) {
                // B6: 202 Accepted 空体（服务器把结果放到 GET SSE 流上返回）时本客户端无法取回响应；
                // 抛带 "MCP HTTP " 前缀的 IOException 使其归类为传输失败，tools/list 可重建会话重试。
                // TODO: 按 Streamable HTTP 规范实现 GET SSE 流读取响应（成本可控时补齐）
                throw IOException("MCP HTTP ${response.code} accepted without response body (GET SSE stream not implemented)")
            }
            json.decodeFromString(JsonRpcResponse.serializer(), body)
        }

    private fun readSse(response: Response, requestId: String): JsonRpcResponse {
        val source = response.body.source()
        val data = mutableListOf<String>()
        var bytes = 0L
        while (!source.exhausted()) {
            val line = try {
                source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
            } catch (_: EOFException) {
                // readUtf8LineStrict 单行超限时抛裸 EOFException：转换为与累积流熔断同口径的
                // 结构化异常，避免一个巨大的 tools/call 结果让整个响应以传输故障的面目失败
                throw IOException(
                    "[MCP SSE 响应熔断拦截：单条 SSE 事件行超过 ${MAX_SSE_LINE_BYTES / 1024 / 1024}MB 硬上限，" +
                        "已强制中断流式传输以保护应用内存]",
                )
            }
            bytes += line.utf8Size() + 1
            if (bytes > McpResponseSizeLimiter.DEFAULT_HARD_LIMIT_BYTES) {
                throw IOException("[MCP SSE 响应熔断拦截：流事件累计已达 ${bytes / 1024 / 1024}MB，超过系统硬上限]")
            }
            if (line.isBlank()) {
                decodeSse(data, requestId)?.let { return it }
                data.clear()
            } else if (line.startsWith("data:")) data += line.removePrefix("data:").trimStart()
        }
        return decodeSse(data, requestId) ?: error("MCP SSE response did not contain request $requestId")
    }

    private fun decodeSse(lines: List<String>, id: String) =
        runCatching { json.decodeFromString(JsonRpcResponse.serializer(), lines.joinToString("\n")) }
            .getOrNull()?.takeIf { it.id == id }

    private fun readLimited(response: Response, requestId: String, isToolCall: Boolean = false): String {
        return when (val payload = readMcpHttpPayload(response, isToolCall, spillDirectory)) {
            is McpResponseSizeLimiter.Payload.Inline -> payload.text
            is McpResponseSizeLimiter.Payload.Spilled -> {
                if (isToolCall) {
                    val escapedText = json.encodeToString(payload.formatForToolResult())
                    val escapedId = json.encodeToString(requestId)
                    """{"jsonrpc":"2.0","id":$escapedId,"result":{"content":[{"type":"text","text":$escapedText}],"isError":false}}"""
                } else {
                    throw IOException("[MCP 响应过大：元数据超出内存安全阈值 (${payload.totalBytes / 1024}KB)]")
                }
            }
            is McpResponseSizeLimiter.Payload.CircuitBroken -> {
                if (isToolCall) {
                    val escapedText = json.encodeToString(payload.formatErrorMessage())
                    val escapedId = json.encodeToString(requestId)
                    """{"jsonrpc":"2.0","id":$escapedId,"result":{"content":[{"type":"text","text":$escapedText}],"isError":true}}"""
                } else {
                    throw IOException(payload.formatErrorMessage())
                }
            }
            is McpResponseSizeLimiter.Payload.SpillUnavailable -> {
                // 本机落盘环境问题：即便 isToolCall 也直接抛传输异常——
                // 返回 isError=true 会让模型误以为是响应过大而去缩小请求范围重试，永远无法恢复
                throw IOException(payload.formatErrorMessage())
            }
        }
    }

    private fun modeLabel(mode: TransportMode) = when (mode) {
        TransportMode.STREAMABLE_HTTP -> "Streamable HTTP"
        TransportMode.LEGACY_SSE -> "Legacy SSE"
    }

    private enum class TransportMode { STREAMABLE_HTTP, LEGACY_SSE }

    private class StreamableExchange(val response: JsonRpcResponse, val sessionId: String?)

    private class HttpSession(
        val mode: TransportMode,
        val endpoint: HttpUrl,
        val sessionId: String?,
        val protocolVersion: String,
        val serverUrl: String,
        val legacy: LegacySseChannel? = null,
        val headers: Map<String, String> = emptyMap(),
    ) {
        /** Streamable 会话视为一直可用（失效由传输层错误触发重建）；Legacy 会话看读流是否存活 */
        val isOpen: Boolean get() = legacy == null || legacy.isAlive
    }

    /**
     * legacy HTTP+SSE 通道：GET 打开长连接接收服务端事件，
     * 请求 POST 到握手时下发的消息端点，响应经长连接按 id 回流。
     */
    private class LegacySseChannel(
        private val url: HttpUrl,
        sseClient: OkHttpClient,
        private val json: Json,
        scope: CoroutineScope,
        private val headers: Map<String, String>,
    ) {
        private val call = sseClient.newCall(
            Request.Builder().url(url).header("Accept", "text/event-stream").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build(),
        )
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
        private val endpointDeferred = CompletableDeferred<HttpUrl>()

        @Volatile private var closed = false

        @Volatile var messageEndpoint: HttpUrl? = null
            private set

        val isAlive: Boolean get() = !closed && endpointDeferred.isCompleted

        private val reader = scope.launch { readLoop() }

        private suspend fun readLoop() {
            runCatching {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw McpHttpStatusException(response.code)
                    var event = ""
                    val data = mutableListOf<String>()
                    var eventBytes = 0
                    val source = response.body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
                        eventBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        require(eventBytes <= MAX_BYTES) { "MCP SSE event is too large" }
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
                            line.isBlank() -> {
                                onEvent(event, data)
                                event = ""
                                data.clear()
                                eventBytes = 0
                            }
                        }
                    }
                    error("SSE 连接已关闭")
                }
            }.onFailure { fail(it) }
        }

        private fun onEvent(event: String, data: List<String>) {
            if (data.isEmpty()) return
            if (event == "endpoint") {
                val resolved = url.resolve(data.first())
                if (resolved == null) {
                    endpointDeferred.completeExceptionally(
                        IllegalStateException("无法解析 endpoint 地址：${data.first()}"),
                    )
                } else {
                    runCatching { validatedDerivedMcpEndpoint(url, resolved) }
                        .onSuccess {
                            messageEndpoint = it
                            endpointDeferred.complete(it)
                        }
                        .onFailure { endpointDeferred.completeExceptionally(it) }
                }
                return
            }
            val rpc = runCatching {
                json.decodeFromString(JsonRpcResponse.serializer(), data.joinToString("\n"))
            }.getOrNull() ?: return
            rpc.id?.let { key -> pending.remove(key)?.complete(rpc) }
        }

        fun register(id: String): CompletableDeferred<JsonRpcResponse> {
            val deferred = CompletableDeferred<JsonRpcResponse>()
            pending[id] = deferred
            return deferred
        }

        fun unregister(id: String) {
            pending.remove(id)
        }

        suspend fun awaitEndpoint(): HttpUrl = endpointDeferred.await()

        suspend fun await(id: String, deferred: CompletableDeferred<JsonRpcResponse>): JsonRpcResponse =
            try {
                deferred.await()
            } finally {
                pending.remove(id)
            }

        fun close() {
            if (closed) return
            closed = true
            call.cancel()
            reader.cancel()
        }

        private fun fail(t: Throwable) {
            closed = true
            // B3: close() 主动 cancel readLoop 产生的 CancellationException 若灌给 pending，
            // 会让调用方协程被"伪取消"（上层对 CancellationException 直接 rethrow，agent 回合误判中止）；
            // 统一改用 IOException 传达"传输已关闭"语义
            val failure = if (t is CancellationException) IOException("MCP HTTP transport closed") else t
            endpointDeferred.completeExceptionally(failure)
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
    }

    companion object {
        private const val ACCEPT = "application/json, text/event-stream"
        private const val MAX_BYTES = 4 * 1024 * 1024
        // 单行上限与 SSE 累积硬顶（DEFAULT_HARD_LIMIT_BYTES）同值：一个完整的 tools/call
        // JSON 结果通常被压成单条 data 行，1MiB 的旧行限会让合法大结果直接失败
        private const val MAX_SSE_LINE_BYTES = McpResponseSizeLimiter.DEFAULT_HARD_LIMIT_BYTES.toInt()

        /** B6: 握手/列表超时从 5s 放宽到 20s：慢网络/冷启动下 5s 偏紧导致 tools/list 频繁失败 */
        private const val FAST_TIMEOUT_MS = 20_000L
        private const val CALL_TIMEOUT_MS = 120_000L
        private const val HANDSHAKE_TIMEOUT_MS = 4_000L

        /** TCP 连接超时：本地地址端口无服务时快速失败，避免 30s 级别的空等。 */
        private const val FAST_CONNECT_TIMEOUT_MS = 3_000L
        private const val CALL_CONNECT_TIMEOUT_MS = 10_000L

        /** 整体握手失败后的冷却时长：期间该服务的工具发现直接快速失败。 */
        private const val FAILURE_COOLDOWN_MS = 5 * 60_000L
        private val JSON = "application/json".toMediaType()
    }
}

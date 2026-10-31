package top.tianyan.app.harness.mcp.server

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.harness.mcp.MCP_PROTOCOL_VERSION
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

/**
 * 进程内 MCP server：单端口（默认 8787）上提供 POST /mcp 与 GET /mcp/stats、/mcp/health。
 *
 * 实现：Ktor 3.x + CIO engine（CIO 是纯 Kotlin 协程 HTTP server，不引入 Netty 重型运行时）。
 * 协议：JSON-RPC 2.0 + MCP 2024-11-05（initialize / tools/list / tools/call / resources/list / resources/read / ping）。
 *
 * 端口冲突处理：CIO 的 bind 发生在 engine 内部协程中，BindException 不会从 [start] 抛出，
 * 而是作为未处理协程异常直接炸掉进程。因此：
 *  - engine 环境统一挂 [CoroutineExceptionHandler] 兜底（只记日志）；
 *  - start 后用 resolvedConnectors() 显式确认绑定成功（bind 失败会抛出/超时）；
 *  - 首选端口被占用时自动顺延尝试 [portFallbackRange] 个相邻端口，全部失败才返回 false。
 */
class McpServerRuntime(
    private val toolDispatcher: McpToolDispatcher,
    private val resourceDispatcher: McpResourceDispatcher,
    /** initialize 握手返回的 serverInfo.name；浏览器端与被控端各用各的名字便于客户端区分。 */
    private val serverName: String = DEFAULT_SERVER_NAME,
) {
    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var currentToken: String? = null
        private set
    @Volatile var currentAllowRemote: Boolean = false
        private set
    @Volatile private var boundPort: Int = defaultPort

    val isRunning: Boolean get() = engine != null
    val port: Int get() = boundPort
    val host: String get() = if (currentAllowRemote) "0.0.0.0" else loopbackHost

    /** 绑定成功与否的确认窗口：CIO bind 异步进行，超出该时长视为失败。 */
    private val bindConfirmTimeoutMs = 3_000L

    /** 首选端口被占用时向后顺延尝试的端口数。 */
    private val portFallbackRange = 10

    @Synchronized
    fun start(loopbackOnly: Boolean, token: String?, port: Int = defaultPort): Boolean {
        if (engine != null) return true
        val bindHost = if (loopbackOnly) loopbackHost else "0.0.0.0"
        currentAllowRemote = !loopbackOnly
        currentToken = token
        // engine 内部协程（accept/bind 等）的未处理异常兜底：只记日志，绝不冒泡到默认 handler 崩掉进程
        val engineExceptionHandler = CoroutineExceptionHandler { _, t ->
            Log.w(TAG, "mcp engine coroutine error: ${t.message}")
        }
        for (attempt in 0 until portFallbackRange) {
            val candidate = port + attempt
            val connector = EngineConnectorBuilder().apply {
                this.host = bindHost
                this.port = candidate
            }
            val srv = CoroutineScope(Dispatchers.IO + engineExceptionHandler).embeddedServer(
                CIO,
                connectors = arrayOf(connector),
                watchPaths = emptyList(),
                parentCoroutineContext = engineExceptionHandler,
            ) {
                routing {
                    get("/mcp/health") {
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }
                    get("/mcp/stats") {
                        call.respondText(writeStatsJson(), ContentType.Application.Json)
                    }
                    post("/mcp") {
                        val auth = call.request.header("Authorization")
                        if (!McpAuthFilter.isAcceptable(
                                authHeader = auth,
                                configuredToken = currentToken,
                            )
                        ) {
                            call.respondText(
                                """{"error":"unauthorized"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Unauthorized,
                            )
                            return@post
                        }
                        val body = call.receiveText()
                        val payload = handleJsonRpc(body)
                        if (payload == null) {
                            // JSON-RPC notification（无 id）：不返回响应体，仅回 202 Accepted
                            call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
                        } else {
                            call.respondText(payload, ContentType.Application.Json)
                        }
                    }
                    post("/mcp/sse") {
                        call.respondText(
                            """{"error":"sse-not-implemented"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotImplemented,
                        )
                    }
                }
            }
            try {
                srv.start(wait = false)
                // 显式确认绑定成功：bind 失败时 resolvedConnectors 立即抛异常，挂死由超时兜底。
                // 正常绑定成功时 resolvedConnectors 瞬时返回，runBlocking 不会造成可感知阻塞。
                val bindOk = runCatching {
                    runBlocking { withTimeout(bindConfirmTimeoutMs) { srv.engine.resolvedConnectors() } }
                }.onFailure { t ->
                    Log.w(TAG, "bind $bindHost:$candidate 失败: ${t.message}")
                    runCatching { srv.stop(100, 300) }
                }.isSuccess
                if (bindOk) {
                    engine = srv
                    boundPort = candidate
                    // 实际绑定端口由调用方（BrowserMcpBootstrap / AgentMcpBootstrap）读取 [port] 后自行落盘，
                    // 运行时类不再直接写自环访问点：同进程可能并存多个实例（浏览器 8787 / 被控 8890），
                    // 在此处写死会互相覆盖。
                    if (attempt > 0) {
                        Log.w(TAG, "端口 $port 被占用，已顺延使用 $candidate")
                    }
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start(port=$candidate) 失败: ${t.message}")
                runCatching { srv.stop(100, 300) }
            }
        }
        // B11: 全端口绑定失败时重置本次 start 写入/残留的状态，避免暴露过期的 token 与端口信息
        engine = null
        currentToken = null
        currentAllowRemote = false
        boundPort = defaultPort
        return false
    }

    fun stop() {
        try { engine?.stop(100, 500) } catch (_: Throwable) {}
        engine = null
        // B11: stop 时清理 token 等运行时状态，避免残留上一次启动的认证信息与绑定端口
        currentToken = null
        currentAllowRemote = false
        boundPort = defaultPort
    }

    /**
     * 处理单条 JSON-RPC 消息。
     *
     * @return 序列化的响应体；null 表示这是一条 notification（无 id），调用方不得返回响应体。
     */
    private suspend fun handleJsonRpc(body: String): String? {
        val req = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return jsonrpcError(null, -32700, "bad-json")
        val method = (req["method"] as? JsonPrimitive)?.content ?: ""
        // B11: 区分"id 字段缺失"（notification，不回响应体）与"id 显式为 null"（按 JSON-RPC 规范
        // 属无效请求，回 -32600）；id 原样保留（数字/字符串都按原始 JsonElement 回显）
        if (!req.containsKey("id")) return null
        val id = req["id"]?.takeUnless { it is JsonNull }
            ?: return jsonrpcError(JsonNull, -32600, "invalid request: id must not be null")
        return when (method) {
            "initialize" -> jsonrpcOk(id, buildJsonObject {
                put("protocolVersion", JsonPrimitive(MCP_PROTOCOL_VERSION))
                put("serverInfo", buildJsonObject {
                    put("name", JsonPrimitive(serverName))
                    put("version", JsonPrimitive("0.1.0"))
                })
                put("capabilities", buildJsonObject {
                    put("tools", JsonObject(emptyMap()))
                    put("resources", JsonObject(emptyMap()))
                })
            })
            "tools/list" -> jsonrpcOk(id, buildJsonObject {
                put("tools", JsonArray(toolDispatcher.listTools()))
            })
            "tools/call" -> {
                val params = req["params"] as? JsonObject
                val name = (params?.get("name") as? JsonPrimitive)?.content.orEmpty()
                val args = (params?.get("arguments") as? JsonObject) ?: JsonObject(emptyMap())
                try {
                    jsonrpcOk(id, toolDispatcher.dispatch(name, args))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 工具执行异常不允许变成 Ktor 500：按 JSON-RPC internal error（-32603）回给调用方
                    jsonrpcError(id, -32603, t.message ?: "internal error")
                }
            }
            "resources/list" -> jsonrpcOk(id, buildJsonObject {
                put("resources", JsonArray(resourceDispatcher.listResources()))
            })
            "resources/read" -> {
                val params = req["params"] as? JsonObject
                val uri = (params?.get("uri") as? JsonPrimitive)?.content.orEmpty()
                val resource = resourceDispatcher.readResource(uri)
                    ?: return jsonrpcError(id, -32004, "not_found")
                jsonrpcOk(id, buildJsonObject {
                    put("contents", JsonArray(listOf(resource)))
                })
            }
            "ping" -> jsonrpcOk(id, JsonObject(emptyMap()))
            else -> jsonrpcError(id, -32601, "Method not found: " + method)
        }
    }

    private fun writeStatsJson(): String = buildJsonObject {
        put("running", JsonPrimitive(isRunning))
        put("bind", JsonPrimitive(host))
        put("allowRemote", JsonPrimitive(currentAllowRemote))
        put("tools", JsonPrimitive(toolDispatcher.listTools().size))
        put("port", JsonPrimitive(port))
    }.toString()

    private fun jsonrpcOk(id: JsonElement?, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        id?.let { put("id", it) }
        put("result", result)
    }.toString()

    private fun jsonrpcError(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        id?.let { put("id", it) }
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        })
    }.toString()

    companion object {
        private const val TAG = "TianyanMcpServer"

        /** initialize 握手默认 serverInfo.name（内置浏览器 MCP）。 */
        const val DEFAULT_SERVER_NAME = "Tianyan Browser MCP Server"

        /** 被控端（外部客户端控制本 App）的默认 serverInfo.name。 */
        const val AGENT_SERVER_NAME = "Tianyan Agent MCP Server"

        const val defaultPort = 8787

        /** 被控端默认端口段起点：与浏览器端 8787 段互不重叠。 */
        const val agentDefaultPort = 8890

        const val loopbackHost = "127.0.0.1"
    }
}

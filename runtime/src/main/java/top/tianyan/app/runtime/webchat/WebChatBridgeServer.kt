package top.tianyan.app.runtime.webchat

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.QuickPhraseRepository
import top.tianyan.app.core.database.WorkspaceRepository
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.WorkspaceFileService
import top.tianyan.app.runtime.WorkspaceManager

const val DEFAULT_WEBCHAT_PORT = 8899

data class WebChatServerStatus(
    val isRunning: Boolean = false,
    val port: Int = DEFAULT_WEBCHAT_PORT,
    val localIp: String = "127.0.0.1",
    val pinCode: String = "",
    val activeConnections: Int = 0,
) {
    val accessUrl: String get() = "http://$localIp:$port"
}

/** LAN bridge for Tianyan's own Harness sessions and registered Linux workspaces. */
class WebChatBridgeServer(
    private val context: Context,
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val quickPhrases: QuickPhraseRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
    internal val workspaceFiles: WorkspaceFileService,
    private val linuxRuntime: LinuxRuntime,
    private val agentGateway: WebChatAgentGateway,
    private val logger: AppLogger,
) {
    private val notification = WebBridgeNotification(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: AndroidHttpServer? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val _status = MutableStateFlow(WebChatServerStatus())
    val status: StateFlow<WebChatServerStatus> = _status.asStateFlow()

    // 远程终端桥与主桥共享 scope 与 PIN；服务停止时一并回收全部 PTY。
    private val terminalBridge by lazy {
        WebTerminalBridge(linuxRuntime, logger, scope) { _status.value.pinCode }
    }

    private val sseEmitters = ConcurrentHashMap.newKeySet<AndroidHttpExchange>()
    private val runTracker = WebChatRunTracker(scope, agentGateway,
        messages = { sessionId, snapshot -> broadcastEvent("messages_replaced", buildJsonObject {
            put("conversationId", sessionId); put("conversationMode", "normal")
            put("messages", messageArray(snapshot.messages))
        }.toString()) },
        event = { taskId, kind, sessionId, approvals -> broadcastEvent("chat_task_event", taskEvent(taskId, kind, sessionId, approvals)) },
        failure = { logger.e("Web task observation failed", it) },
    )
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start(port: Int = DEFAULT_WEBCHAT_PORT, pin: String? = null): Boolean {
        if (_status.value.isRunning) return true
        return try {
            val generatedPin = pin ?: generatePin()
            // 工作线程池由 AndroidHttpServer 自己持有：stop() 时随服务一起回收，
            // 不再让本类持有一个永不 shutdown 的 fixedThreadPool。
            val server = AndroidHttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/webchat/api/session/bootstrap", SessionBootstrapHandler())
            server.createContext("/webchat/api/bootstrap", BootstrapHandler())
            server.createContext("/webchat/api/conversations", ConversationsHandler())
            server.createContext("/webchat/api/tasks", TasksHandler())
            server.createContext("/webchat/api/events", SseEventsHandler())
            server.createContext("/webchat/api/workspaces", WorkspacesHandler())
            // 远程终端桥：/webchat/api/terminal*（列表/创建/SSE 输出/输入/缩放/关闭）。
            terminalBridge.registerRoutes(server)
            server.createContext("/", StaticAssetHandler())
            runTracker.enable()
            server.start()
            httpServer = server

            val localIp = resolveLocalIp()
            _status.value = WebChatServerStatus(true, port, localIp, generatedPin)
            acquireLocks()
            notification.show("http://$localIp:$port", generatedPin)
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(25_000)
                    if (sseEmitters.isNotEmpty()) {
                        broadcastEvent("ping", "{}")
                    }
                }
            }
            logger.i("天衍智枢 Web 协作服务启动成功：http://$localIp:$port")
            true
        } catch (exception: Exception) {
            logger.e("天衍智枢 Web 协作服务启动失败", exception)
            false
        }
    }

    fun stop() {
        runCatching {
            // 先回收终端 PTY（会向订阅者发 exit），再关 HTTP 与其余资源。
            terminalBridge.shutdown()
            heartbeatJob?.cancel()
            heartbeatJob = null
            releaseLocks()
            notification.hide()
            runTracker.stop()
            sseEmitters.forEach { runCatching { it.close() } }
            sseEmitters.clear()
            httpServer?.stop(0)
            httpServer = null
            _status.value = _status.value.copy(isRunning = false, activeConnections = 0)
        }.onFailure { logger.e("天衍智枢 Web 协作服务停止异常", it) }
    }

    fun broadcastEvent(eventName: String, dataJson: String) {
        val payload = "event: $eventName\ndata: $dataJson\n\n".toByteArray(Charsets.UTF_8)
        val iterator = sseEmitters.iterator()
        while (iterator.hasNext()) {
            val exchange = iterator.next()
            try {
                exchange.responseBody.write(payload)
                exchange.responseBody.flush()
            } catch (_: Exception) {
                runCatching { exchange.close() }
                iterator.remove()
            }
        }
        _status.value = _status.value.copy(activeConnections = sseEmitters.size)
    }

    /**
     * 请求协程的统一兜底。handler 在 [scope] 里异步执行，抛出的异常不会回到
     * [AndroidHttpServer] 的 accept 循环，socket 会连同工作线程一起挂住：
     * 这里保证异常路径也回一个 500 并最终关闭连接（[AndroidHttpExchange.close] 幂等）。
     *
     * 返回 Unit 而不是 Job：调用点是 `override fun handle(...) = launchRequest(...) { }`，
     * 返回 Job 会迫使每个 handler 末尾补一个 `.let { }` 才能满足 Unit 覆写。
     */
    private fun launchRequest(
        exchange: AndroidHttpExchange,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("天衍智枢 Web 请求处理失败：${exchange.requestURI.path}", throwable)
                if (!exchange.isResponseStarted) {
                    runCatching { sendJson(exchange, 500, errorJson(throwable.message ?: "请求处理失败")) }
                }
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    private inner class StaticAssetHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/").trimStart('/')
            val assetPath = if (path.isBlank() || !hasFileExtension(path)) "webchat/index.html" else "webchat/$path"
            val stream: InputStream = runCatching { context.assets.open(assetPath) }.getOrElse {
                if (hasFileExtension(path)) {
                    sendText(exchange, 404, "资源不存在")
                    return
                }
                context.assets.open("webchat/index.html")
            }
            stream.use { sendResponse(exchange, 200, getMimeType(assetPath), it.readBytes()) }
        }
    }

    private inner class SessionBootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            val token = requestJson(exchange)["token"]?.jsonPrimitive?.content.orEmpty()
            if (token != _status.value.pinCode) sendJson(exchange, 401, errorJson("配对码不正确"))
            else sendJson(exchange, 200, buildJsonObject { put("authenticated", true) })
        }
    }

    private inner class BootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val configuredModels = models.observeAll().firstValue()
            // 与 ChatViewModel 一致先播种内置短语，避免聊天页从未打开时列表为空
            runCatching { quickPhrases.ensureInitialized() }
            val enabledPhrases = runCatching {
                quickPhrases.getAll()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
            }.getOrDefault(emptyList())
            sendJson(exchange, 200, buildJsonObject {
                put("authenticated", true)
                put("appName", "天衍智枢")
                put("version", "1.0")
                putJsonArray("models") {
                    configuredModels.forEach { model ->
                        add(buildJsonObject {
                            put("id", model.id)
                            put("name", model.name)
                            put("model", model.model)
                            put("active", model.isActive)
                        })
                    }
                }
                putJsonArray("quickPhrases") {
                    enabledPhrases.forEach { phrase ->
                        add(buildJsonObject {
                            put("id", phrase.id)
                            put("title", phrase.title)
                            put("content", phrase.content)
                            if (phrase.description.isNotBlank()) put("description", phrase.description)
                        })
                    }
                }
                put("workspace", buildJsonObject {
                    put("workspace", buildJsonObject { put("rootPath", "/workspace") })
                    put("root", buildJsonObject { put("path", "/workspace") })
                })
            })
        }
    }

    private inner class ConversationsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/conversations", "").trim('/')
                val parts = suffix.split('/').filter(String::isNotBlank)
                when {
                    parts.isEmpty() && exchange.requestMethod == "GET" -> {
                        sendJson(exchange, 200, buildJsonArray {
                            sessions.listAll().sortedByDescending(HarnessSessionEntity::updatedAt).forEach { add(conversationJson(it)) }
                        })
                    }
                    parts.isEmpty() && exchange.requestMethod == "POST" -> {
                        val body = requestJson(exchange)
                        val workspace = body["workspace"]?.jsonPrimitive?.content.orEmpty()
                        val id = agentGateway.createSession(
                            body["title"]?.jsonPrimitive?.content.orEmpty(),
                            workspace.takeIf { isRegisteredWorkspacePath(it) }.orEmpty(),
                        )
                        val session = requireNotNull(sessions.findById(id))
                        sendJson(exchange, 200, conversationJson(session))
                        broadcastEvent("conversation_created", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        agentGateway.deleteSession(id)
                        sendJson(exchange, 200, buildJsonObject { put("deleted", true) })
                        broadcastEvent("conversation_deleted", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 2 && parts[1] == "messages" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        sendJson(exchange, 200, messageArray(agentGateway.messages(id)))
                    }
                    parts.size == 2 && parts[1] == "approvals" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        sendJson(exchange, 200, approvalArray(agentGateway.pendingApprovals(id)))
                    }
                    parts.size == 3 && parts[1] == "approvals" && exchange.requestMethod == "POST" -> {
                        val sessionId = parts[0]
                        requireNotNull(sessions.findById(sessionId)) { "会话不存在" }
                        val body = requestJson(exchange)
                        val approved = body["approved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("缺少审批决定")
                        val accepted = agentGateway.resolveApproval(sessionId, parts[2], approved)
                        require(accepted) { "审批请求不存在、已处理或不属于当前会话" }
                        val taskId = runTracker.requestForSession(sessionId)
                        sendJson(exchange, 200, buildJsonObject {
                            put("accepted", true)
                            taskId?.let { put("taskId", it) }
                        })
                    }
                    parts.size == 2 && parts[1] == "runs" && exchange.requestMethod == "POST" -> {
                        startRun(exchange, parts[0])
                    }
                    parts.size == 2 && parts[1] == "feedback" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        sendJson(exchange, 200, buildJsonObject {
                            put("feedbacks", buildJsonArray {
                                agentGateway.messageFeedbacks(id).forEach { add(feedbackJson(it)) }
                            })
                        })
                    }
                    parts.size == 2 && parts[1] == "feedback" && exchange.requestMethod == "POST" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        val body = requestJson(exchange)
                        val messageId = body["messageId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("缺少 messageId")
                        val rating = body["rating"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 rating")
                        val note = body["note"]?.jsonPrimitive?.content
                        val saved = agentGateway.setMessageFeedback(id, messageId, rating, note)
                        sendJson(exchange, 200, buildJsonObject { put("feedback", feedbackJson(saved)) })
                    }
                    else -> sendText(exchange, 404, "接口不存在")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val error = WebChatRunProtocol.failure(throwable)
                sendJson(exchange, error.status, errorJson(error.message))
            }
        }
    }

    private suspend fun startRun(exchange: AndroidHttpExchange, sessionId: String) {
        requireNotNull(sessions.findById(sessionId)) { "会话不存在" }
        val body = requestJson(exchange)
        val input = WebChatRunProtocol.parse(body)
        val taskId = body["taskId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: "web-${UUID.randomUUID()}"
        val receipt = runTracker.submit(sessionId, taskId) { agentGateway.send(sessionId, input.text, input.images, input.mode) }
        sendJson(exchange, 200, buildJsonObject {
            put("taskId", taskId)
            put("conversationMode", "normal")
            put("input", WebChatRunProtocol.receiptJson(receipt))
            put("conversation", conversationJson(requireNotNull(sessions.findById(sessionId))))
        })
    }

    private inner class TasksHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val suffix = exchange.requestURI.path.substringAfter("/tasks", "").trim('/')
            val parts = suffix.split('/').filter(String::isNotBlank)
            if (parts.size == 2 && parts[1] == "cancel" && exchange.requestMethod == "POST") {
                val taskId = parts[0]
                sendJson(exchange, 200, buildJsonObject { put("cancelled", runTracker.cancel(taskId)) })
            } else {
                sendText(exchange, 404, "任务接口不存在")
            }
        }
    }

    private inner class SseEventsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            if (handlePreflight(exchange)) return
            if (!isAuthenticated(exchange)) {
                sendJson(exchange, 401, errorJson("请先配对"))
                return
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0)
            sseEmitters.add(exchange)
            _status.value = _status.value.copy(activeConnections = sseEmitters.size)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
        }
    }

    private inner class WorkspacesHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/workspaces", "").trim('/')
                when {
                    suffix.isEmpty() && exchange.requestMethod == "GET" -> listWorkspace(exchange)
                    suffix == "file" && exchange.requestMethod == "GET" -> readWorkspaceFile(exchange)
                    suffix == "file" && exchange.requestMethod == "PUT" -> writeWorkspaceFile(exchange)
                    suffix == "download" && exchange.requestMethod == "GET" -> downloadWorkspaceFile(exchange)
                    suffix == "upload" && exchange.requestMethod == "POST" -> uploadWorkspaceFile(exchange)
                    suffix == "item" && exchange.requestMethod == "POST" -> workspaceItemAction(exchange)
                    else -> sendText(exchange, 404, "工作区接口不存在")
                }
            } catch (throwable: Throwable) {
                sendJson(exchange, 400, errorJson(throwable.message ?: "工作区操作失败"))
            }
        }
    }

    private suspend fun listWorkspace(exchange: AndroidHttpExchange) {
        val path = getQueryParam(exchange, "path").orEmpty().ifBlank { "/workspace" }
        if (path.trimEnd('/') == "/workspace") {
            val projects = workspaceManager.listProjects()
            sendJson(exchange, 200, buildJsonObject {
                put("path", "/workspace")
                putJsonArray("items") {
                    projects.forEach { project ->
                        add(buildJsonObject {
                            put("name", project.name)
                            put("path", project.linuxPath)
                            put("isDirectory", true)
                            put("size", project.sizeBytes)
                        })
                    }
                }
            })
            return
        }
        val (project, relative) = parseWorkspacePath(path)
        val items = workspaceFiles.listFiles(project, relative).orThrow()
        sendJson(exchange, 200, buildJsonObject {
            put("path", workspacePath(project, relative))
            putJsonArray("items") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", workspacePath(project, item.relativePath))
                        put("isDirectory", item.isDirectory)
                        put("size", item.sizeBytes)
                    })
                }
            }
        })
    }

    private suspend fun readWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        sendJson(exchange, 200, buildJsonObject {
            put("content", workspaceFiles.readFile(project, relative).orThrow())
        })
    }

    private suspend fun writeWorkspaceFile(exchange: AndroidHttpExchange) {
        val body = requestJson(exchange)
        val (project, relative) = parseWorkspacePath(body["path"]?.jsonPrimitive?.content.orEmpty())
        workspaceFiles.writeFile(project, relative, body["content"]?.jsonPrimitive?.content.orEmpty()).orThrow()
        sendJson(exchange, 200, buildJsonObject { put("saved", true) })
        broadcastEvent("workspace_changed", buildJsonObject { put("path", workspacePath(project, relative)) }.toString())
    }

    private suspend fun downloadWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        val entity = requireNotNull(workspaces.findByName(project)) { "工作区不存在" }
        val root = File(entity.path).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.isFile && (file == root || file.path.startsWith(root.path + File.separator))) { "文件路径无效" }
        // 流式回传：工作区产物最大 128MB，整份 readBytes 会在手机上直接 OOM。
        sendFileResponse(exchange, 200, "application/octet-stream", file, fileName = file.name)
    }

    private fun conversationJson(session: HarnessSessionEntity) = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("mode", "normal")
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("workspace", session.workspace)
    }

    private fun messageArray(messages: List<WebChatMessage>) = buildJsonArray {
        messages.forEach { add(json.encodeToJsonElement(WebChatMessage.serializer(), it)) }
    }

    private fun feedbackJson(entry: top.tianyan.app.core.database.AgentMessageFeedbackEntity) = buildJsonObject {
        put("id", entry.id)
        put("messageId", entry.messageId)
        put("rating", entry.rating)
        entry.note?.let { put("note", it) }
        put("createdAt", entry.createdAt)
        put("updatedAt", entry.updatedAt)
    }

    private fun approvalArray(approvals: List<WebChatApproval>) = buildJsonArray {
        approvals.forEach { add(json.encodeToJsonElement(WebChatApproval.serializer(), it)) }
    }

    private fun taskEvent(
        taskId: String,
        kind: String,
        sessionId: String,
        approvals: List<WebChatApproval> = emptyList(),
    ) = buildJsonObject {
        put("taskId", taskId)
        put("kind", kind)
        put("conversationId", sessionId)
        if (approvals.isNotEmpty()) put("approvals", approvalArray(approvals))
    }.toString()

    private fun handlePreflight(exchange: AndroidHttpExchange): Boolean {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendResponse(exchange, 204, "text/plain", ByteArray(0))
            return true
        }
        return false
    }

    private fun isAuthenticated(exchange: AndroidHttpExchange): Boolean {
        val token = getQueryParam(exchange, "token")
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return token != null && token == _status.value.pinCode
    }

    private fun requireAuthenticated(exchange: AndroidHttpExchange): Boolean {
        if (isAuthenticated(exchange)) return true
        sendJson(exchange, 401, errorJson("请先使用配对码连接"))
        return false
    }

    private suspend fun isRegisteredWorkspacePath(path: String): Boolean = runCatching {
        val (project, _) = parseWorkspacePath(path)
        workspaces.findByName(project) != null
    }.getOrDefault(false)

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private fun acquireLocks() {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tianyan:webchat_bridge_wake")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // API 34+ 用 LOW_LATENCY 模式更契合实时桥接；旧版本保留 HIGH_PERF（已废弃但仍是该级别的最优档）
        val wifiMode = if (Build.VERSION.SDK_INT >= 34) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi?.createWifiLock(wifiMode, "tianyan:webchat_bridge_wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun getMimeType(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    private fun hasFileExtension(path: String): Boolean = path.substringAfterLast('/', "").contains('.')
    private fun generatePin(): String = (100000..999999).random().toString()

    private fun resolveLocalIp(): String = runCatching {
        var fallback = "127.0.0.1"
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (network.isLoopback || !network.isUp) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress.orEmpty()
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return@runCatching host
                    fallback = host
                }
            }
        }
        fallback
    }.getOrDefault("127.0.0.1")

    companion object {
        const val DEFAULT_PORT = DEFAULT_WEBCHAT_PORT
    }
}

package top.tianyan.app.runtime.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.privilege.PrivilegeManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import top.tianyan.app.runtime.bridge.HostBridgeHttp.HttpRequest
import top.tianyan.app.runtime.bridge.HostBridgeHttp.HttpResponse
import top.tianyan.app.runtime.bridge.HostBridgeHttp.parseHttpRequest
import top.tianyan.app.runtime.bridge.HostBridgeHttp.writeResponse
import top.tianyan.app.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 宿主桥接 (Host Bridge) — 沙箱与 Android 宿主之间的 localhost HTTP 通道。
 *
 * 沙箱（PRoot）与 App 共享网络命名空间，因此沙箱内可通过 `127.0.0.1:7980` 访问本服务。
 *
 * 提供以下端点：
 * - `GET  /api/health`       — 桥接健康检查 + 当前特权状态与 ADB 状态
 * - `POST /api/install-apk`  — 在宿主侧安装 APK（无线 ADB 静默安装或系统安装器）
 * - `POST /api/shell`        — 在宿主侧执行 Shell 命令（Shizuku/Root 或内置无线 ADB）
 * - `POST /api/logcat`       — 抓取目标应用或系统 Logcat 日志
 *
 * 安全：仅监听 127.0.0.1；每个写操作都要绑定一张由宿主签发、可撤销的凭据（[HostCredentialAuthority]），
 * 并校验端点所需的结构化动作。凭据只经原生代码路径注入 guest 环境，不存在共享密钥文件或签发端点。
 */
class HostBridge(
    private val context: Context,
    private val logger: AppLogger,
    private val privilegeManager: PrivilegeManager,
    private val embeddedAdbManager: EmbeddedAdbManager,
    private val pathManager: RuntimePathManager,
    private val credentialAuthority: HostCredentialAuthority,
) {
    companion object {
        const val BRIDGE_PORT = 7980
        const val BRIDGE_HOST = "127.0.0.1"
        const val BRIDGE_URL = "http://127.0.0.1:7980"
        private const val READ_TIMEOUT_MS = 10_000

        /** The plugin-facing logcat request is fixed here, so a page can never widen it. */
        private const val PLUGIN_LOGCAT_BODY = """{"lines":200}"""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val apkInstall = HostBridgeApkInstall(context, logger, embeddedAdbManager, pathManager)

    private val pluginGateway = PluginHostGateway(
        appInfo = {
            buildJsonObject {
                put("package", context.packageName)
                put("androidApi", android.os.Build.VERSION.SDK_INT)
            }.toString()
        },
        clipboardWrite = { PluginHostClipboard.write(context, it) },
        installApkFile = { path ->
            // The page named a sandbox path; this host resolves it and reuses the guest channels.
            val response = apkInstall.installAt(path)
            check(response.status == 200) { "APK install refused" }
            response.body
        },
        captureLogcat = {
            // A page never chooses tag, priority or line count: one fixed bounded snapshot.
            val response = handleLogcat(PLUGIN_LOGCAT_BODY)
            check(response.status == 200) { "Log capture refused" }
            response.body
        },
        authority = credentialAuthority,
    )

    /** Native-only call after document approval; the credential never leaves this host adapter. */
    fun readPluginAppInfo(scope: PluginHostScope, available: () -> Boolean): String =
        pluginGateway.readAppInfo(scope, available)

    fun writePluginClipboard(scope: PluginHostScope, text: String, available: () -> Boolean): String =
        pluginGateway.writeClipboard(scope, text, available)

    /** Privileged plugin actions reuse the same one-shot authority; only native callers reach them. */
    suspend fun installPluginApk(scope: PluginHostScope, path: String, available: () -> Boolean): String =
        pluginGateway.installApk(scope, path, available)

    suspend fun readPluginLogcat(scope: PluginHostScope, available: () -> Boolean): String =
        pluginGateway.readLogcat(scope, available)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 启动 HTTP 监听。重复调用安全，且绑定操作不会被并发启动打断。 */
    @Synchronized
    fun start() {
        if (_isRunning.value) return
        try {
            val socket = ServerSocket(BRIDGE_PORT, 16, InetAddress.getByName(BRIDGE_HOST))
            serverSocket = socket
            _isRunning.value = true
            serverJob = bridgeScope.launch {
                logger.i("HostBridge listening on $BRIDGE_HOST:$BRIDGE_PORT")
                while (isActive) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (isActive) logger.w("HostBridge accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            serverSocket?.runCatching { close() }
            serverSocket = null
            _isRunning.value = false
            logger.e("Failed to start HostBridge on port $BRIDGE_PORT", e)
        }
    }

    /** 停止 HTTP 监听，并撤销所有插件与沙箱凭据（代次推进，缓存层随后自愈）。 */
    @Synchronized
    fun stop() {
        credentialAuthority.revokeAll()
        serverJob?.cancel()
        serverSocket?.runCatching { close() }
        serverSocket = null
        _isRunning.value = false
        logger.i("HostBridge stopped")
    }

    // ============================ 请求处理 ============================

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val request = parseHttpRequest(client.getInputStream()) ?: run {
                writeResponse(client, 400, errorJson("Failed to parse HTTP request"))
                return
            }
            val response = route(request)
            writeResponse(client, response.status, response.body)
        } catch (e: Exception) {
            runCatching { writeResponse(client, 500, errorJson("Internal error: ${e.message}")) }
        } finally {
            runCatching { client.close() }
        }
    }

    /** Visible to in-module tests so the converged credential path can be asserted without a socket. */
    internal suspend fun route(request: HttpRequest): HttpResponse {
        pluginGateway.route(request)?.let { return HttpResponse(it.status, it.body) }
        // health 端点免认证（只暴露特权/ADB 状态，无凭证）
        if (request.method == "GET" && request.path.startsWith("/api/health")) return handleHealth()

        // 每个写端点绑定一个结构化动作；未知路径也先过"有效凭据"这一关再 404，避免未认证探测面。
        val action = when {
            request.method == "POST" && request.path.startsWith("/api/install-apk") -> "host.install-apk"
            request.method == "POST" && request.path.startsWith("/api/shell") -> "host.shell"
            request.method == "POST" && request.path.startsWith("/api/logcat") -> "host.logcat"
            else -> null
        }
        if (!credentialAuthority.authorize(bearerToken(request.headers), action)) {
            return HttpResponse(401, errorJson("Unauthorized: missing or invalid bridge credential"))
        }
        return when (action) {
            "host.install-apk" -> handleInstallApk(request.body)
            "host.shell" -> handleShell(request.body)
            "host.logcat" -> handleLogcat(request.body)
            else -> HttpResponse(404, errorJson("Not found: ${request.method} ${request.path}"))
        }
    }

    private fun bearerToken(headers: Map<String, String>): String? =
        headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()?.ifEmpty { null }

    // ============================ 端点实现 ============================

    private suspend fun handleHealth(): HttpResponse {
        val info = privilegeManager.getPrivilegeInfo()
        val adbState = embeddedAdbManager.state.value
        val adbConnected = adbState is EmbeddedAdbManager.ConnectionState.Connected
        val body = buildJsonObject {
            put("status", "ok")
            put("bridge", "1.0")
            put("port", BRIDGE_PORT)
            put("mode", info.mode.id)
            put("modeLabel", info.mode.shortLabel)
            put("modeActive", info.modeActive || adbConnected)
            put("shizuku", info.shizukuAvailable)
            put("root", info.rootAvailable)
            put("adb", adbConnected)
            put("adbPort", (adbState as? EmbeddedAdbManager.ConnectionState.Connected)?.port ?: 0)
            put("shellSupported", info.shizukuAvailable || info.rootAvailable || adbConnected)
        }.toString()
        return HttpResponse(200, body)
    }

    private suspend fun handleInstallApk(body: String): HttpResponse {
        val apkPath = try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["path"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        if (apkPath.isNullOrBlank()) {
            return HttpResponse(400, errorJson("Missing 'path' field"))
        }
        return apkInstall.installAt(apkPath)
    }

    private suspend fun handleShell(body: String): HttpResponse {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        val command = obj["command"]?.jsonPrimitive?.content
        if (command.isNullOrBlank()) {
            return HttpResponse(400, errorJson("Missing 'command' field"))
        }

        val info = privilegeManager.getPrivilegeInfo()
        val responseJson = if (info.modeActive && (info.shizukuAvailable || info.rootAvailable)) {
            val result = privilegeManager.executeShellCommand(command)
            buildJsonObject {
                put("success", result.success)
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("channel", "privilege")
            }
        } else {
            // 端口固定由内置无线 ADB 管理器持有；沙箱不得传入任意端口作为回退通道。
            val adbResult = embeddedAdbManager.executeShell(command, null)
            buildJsonObject {
                put("success", adbResult.success)
                put("exitCode", adbResult.exitCode ?: if (adbResult.success) 0 else 1)
                put("stdout", if (adbResult.success) adbResult.output else "")
                put("stderr", if (!adbResult.success) adbResult.output else "")
                put("channel", "wireless-adb")
            }
        }
        return HttpResponse(200, responseJson.toString())
    }

    private suspend fun handleLogcat(body: String): HttpResponse {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        val clear = obj["clear"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (clear) {
            val clearResult = embeddedAdbManager.clearLogcat()
            return HttpResponse(200, buildJsonObject {
                put("success", clearResult.success)
                put("message", if (clearResult.success) "Logcat buffer cleared" else clearResult.output)
            }.toString())
        }

        val pkg = obj["package"]?.jsonPrimitive?.content?.trim().orEmpty()
        val tag = obj["tag"]?.jsonPrimitive?.content?.trim().orEmpty()
        val priority = obj["priority"]?.jsonPrimitive?.content?.trim()?.uppercase()?.firstOrNull() ?: 'V'
        val keyword = obj["keyword"]?.jsonPrimitive?.content?.trim().orEmpty()
        val lines = obj["lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200

        val result = embeddedAdbManager.captureLogcat(
            EmbeddedAdbManager.LogcatRequest(
                packageName = pkg,
                tag = tag,
                priority = priority,
                keyword = keyword,
                lines = lines,
            ),
            explicitPort = null,
        )

        return HttpResponse(200, buildJsonObject {
            put("success", result.success)
            put("exitCode", result.exitCode ?: if (result.success) 0 else 1)
            put("output", result.output)
        }.toString())
    }

    // ============================ 工具方法 ============================

    private fun errorJson(message: String): String = buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString()
}

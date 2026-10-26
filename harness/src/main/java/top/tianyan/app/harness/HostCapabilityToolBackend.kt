package top.tianyan.app.harness

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.AndroidAppRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.model.ExecutionMode
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.core.ToolBackend
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.runtime.apps.AndroidAppManager
import top.tianyan.app.runtime.bridge.adb.EmbeddedAdbManager
import top.tianyan.app.runtime.gui.HostGuiController
import top.tianyan.app.runtime.gui.ScrollDirection
import top.tianyan.app.runtime.privilege.BinderOutcome
import top.tianyan.app.runtime.privilege.PrivilegeManager
import top.tianyan.app.runtime.privilege.ShizukuSystemApis
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.tianyan.app.runtime.virtualdisplay.VirtualScreenToolkit

data class HostToolRequest(
    val args: JsonObject,
    val operationId: String?,
    val sessionId: String,
    val metadata: MutableMap<String, String>,
)

/**
 * Agent 工具后端：宿主 Android 特权通道（host 工具）。
 *
 * 包含：shell 命令执行（exec）、系统设置读写（settings_get/put）、
 * 包管理（package_list/disable/enable/uninstall/freeze/unfreeze/grant_permission）、
 * logcat、GUI 操作（screen_observe/click/swipe 等）、应用数据库查询（app_list）。
 *
 * 职责边界：
 * - 只负责宿主能力调用，不持有审批逻辑或输出脱敏逻辑——由 ToolExecutor 管道层处理。
 * - host 侧输出截断由 capHostOutput() 外部函数统一处理。
 */
class HostCapabilityToolBackend(
    private val privilegeManager: PrivilegeManager? = null,
    private val androidAppManager: AndroidAppManager? = null,
    private val androidAppRepository: AndroidAppRepository? = null,
    private val shizukuApis: ShizukuSystemApis? = null,
    private val hostGuiController: HostGuiController? = null,
    private val virtualDisplayCoordinator: VirtualDisplayCoordinator? = null,
    private val virtualScreenToolkit: VirtualScreenToolkit? = null,
    private val providerClient: ProviderClient? = null,
    private val settingsDataStore: AgentPreferences? = null,
    private val phoneAgentServices: PhoneAgentServices? = null,
    private val embeddedAdbManager: EmbeddedAdbManager? = null,
    private val secretRedactor: SecretRedactor,
    private val eventBus: HarnessEventBus? = null,
) : ToolBackend<HostToolRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: HostToolRequest): Pair<Boolean, String> {
        val raw = executeHostUncapped(request.args, request.operationId, request.sessionId, request.metadata)
        return raw.first to capHostOutput(raw.second)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeHostUncapped(args: JsonObject, operationId: String?, sessionId: String, metadata: MutableMap<String, String>): Pair<Boolean, String> {
        val action = JsonArgs.requireString(args, "action").trim().lowercase()

        // Logcat 优先走内置无线 ADB，不依赖 Shizuku/Root；不可用时再回退原特权通道。
        if (action == "logcat" && embeddedAdbManager != null) {
            val explicitPort = args["port"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
            val adbResult = embeddedAdbManager.captureLogcat(
                EmbeddedAdbManager.LogcatRequest(
                    packageName = args["package"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    tag = args["tag"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    priority = args["priority"]?.jsonPrimitive?.content?.trim()?.uppercase()?.firstOrNull() ?: 'V',
                    keyword = args["keyword"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    lines = JsonArgs.optionalLong(args, "tail_lines", 200L, 1L, 2_000L).toInt(),
                ),
                explicitPort = explicitPort,
            )
            if (adbResult.success) {
                return true to "mode wireless-adb · exit ${adbResult.exitCode ?: 0}\n${adbResult.output.trim()}"
            }
            val fallbackManager = privilegeManager ?: return false to adbResult.output.ifBlank {
                "无线 ADB 未连接；请在开发者控制台开启无线调试并完成一次配对。"
            }
            val info = fallbackManager.getPrivilegeInfo()
            if (info.mode == ExecutionMode.PROOT || !info.modeActive) {
                return false to adbResult.output.ifBlank {
                    "无线 ADB 未连接；请在开发者控制台开启无线调试并完成一次配对。"
                }
            }
        }
        val manager = privilegeManager ?: return false to "未初始化宿主权限执行器"
        if (VirtualScreenHostActions.handles(action)) {
            return virtualScreenHostActions { metadata["image_payload"] = it }.execute(action, args)
        }

        // settings_put system 命名空间优先走 Android ContentResolver API（需 WRITE_SETTINGS），
        // 避免 Shizuku shell 在部分国产 ROM 上被 SettingsProvider 静默拒绝（exit 22）。
        // secure/global 命名空间需 WRITE_SECURE_SETTINGS（第三方应用不可得），仍走 shell。
        if (action == "settings_put") {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            val value = JsonArgs.requireString(args, "value")
            if (namespace == "system") {
                val apiOk = manager.writeSystemSetting(key, value)
                if (apiOk) {
                    Log.i(
                        "Tianyan-Host",
                        secretRedactor.redact("action=settings_put via API success: system.$key=$value"),
                    )
                    return true to "mode api · exit 0\n[Android API] settings put system $key = $value"
                }
                // API 写入失败（通常是未授权 WRITE_SETTINGS），发事件引导用户授权，然后回退 shell
                eventBus?.emit(
                    HarnessEvent.PermissionRequired(
                        sessionId = sessionId.ifBlank { "unknown" },
                        timestamp = System.currentTimeMillis(),
                        permission = "WRITE_SETTINGS",
                        reason = "修改系统设置（如亮度）需要授权「修改系统设置」权限",
                    )
                )
            }
        }

        return when (action) {
            "status" -> {
                val info = manager.getPrivilegeInfo()
                true to buildString {
                    append("当前生效模式：").append(info.mode.title)
                    append("\n权限状态：").append(if (info.modeActive) "已授权" else "未授权")
                    append("\nShizuku：").append(if (info.shizukuAvailable) "可用 (shell UID 2000)" else "不可用")
                    append("\nRoot：").append(if (info.rootAvailable) "可用 (UID 0)" else "不可用或未选择")
                }
            }
            "app_list" -> executeCachedApps(args)
            "screen_observe" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val onlyInteractive = args["only_interactive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val res = gui.observeScreen(onlyInteractive)
                res.fold(
                    onSuccess = { obs -> true to obs.toAgentSummary() },
                    onFailure = { err -> false to "感知屏幕失败：${err.message}" }
                )
            }
            "screen_click" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val x = JsonArgs.requireInt(args, "x")
                val y = JsonArgs.requireInt(args, "y")
                val res = gui.click(x, y)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_double_click" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                gui.doubleClick(JsonArgs.requireInt(args, "x"), JsonArgs.requireInt(args, "y")).fold(
                    onSuccess = { true to it },
                    onFailure = { false to it.message.orEmpty() },
                )
            }
            "screen_long_press" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val duration = JsonArgs.optionalLong(args, "duration_ms", 800L, 200L, 5_000L)
                gui.longPress(JsonArgs.requireInt(args, "x"), JsonArgs.requireInt(args, "y"), duration).fold(
                    onSuccess = { true to it },
                    onFailure = { false to it.message.orEmpty() },
                )
            }
            "screen_swipe" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val x1 = JsonArgs.requireInt(args, "x1")
                val y1 = JsonArgs.requireInt(args, "y1")
                val x2 = JsonArgs.requireInt(args, "x2")
                val y2 = JsonArgs.requireInt(args, "y2")
                val durationMs = JsonArgs.optionalLong(args, "duration_ms", 300L, 50L, 3000L)
                val res = gui.swipe(x1, y1, x2, y2, durationMs)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_scroll" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val direction = when (JsonArgs.requireString(args, "direction").lowercase()) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> return false to "direction 仅支持 up/down/left/right"
                }
                val ratio = args["distance_ratio"]?.jsonPrimitive?.content?.toFloatOrNull()?.coerceIn(0.15f, 0.8f) ?: 0.45f
                val durationMs = JsonArgs.optionalLong(args, "duration_ms", 350L, 50L, 5_000L)
                gui.scroll(direction, ratio, durationMs).fold(
                    onSuccess = { true to it },
                    onFailure = { false to it.message.orEmpty() },
                )
            }
            "screen_input_text", "paste_text" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val text = JsonArgs.requireString(args, "text")
                val res = gui.inputText(text)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_key" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val key = JsonArgs.requireString(args, "key")
                val res = gui.sendKey(key)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "app_launch" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val packageName = requireHostIdentifier(args, "package", PACKAGE_NAME)
                val res = gui.launchApp(packageName)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_capture" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val targetPath = JsonArgs.requireString(args, "path")
                val res = gui.captureScreenshot(targetPath)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            else -> {
                val packageName = if (action in APP_DATABASE_GUARDED_ACTIONS || action == "app_grant_permission") {
                    requireHostIdentifier(args, "package", PACKAGE_NAME)
                } else ""
                if (action in APP_DATABASE_GUARDED_ACTIONS) {
                    (androidAppManager ?: return false to "未初始化应用管理器").requireInitialized(packageName)
                }
                val info = manager.getPrivilegeInfo()
                require(info.mode != ExecutionMode.PROOT && info.modeActive) {
                    "权限不足：冻结、启用、卸载或授权应用前，请先在设置中授权并切换到 Shizuku 或 Root 模式。"
                }

                // Shizuku 生效时优先 Binder 直调：免 shell 转义、异常结构化。
                // 仅通道不可用时才回退 shell；远端明确拒绝则直接报告不重试。
                if (info.mode == ExecutionMode.SHIZUKU && shizukuApis != null) {
                    val userId = JsonArgs.optionalLong(args, "user", 0L, 0L, 999L).toInt()
                    val binderOutcome = when (action) {
                        "app_grant_permission" -> {
                            val permission = requireHostIdentifier(args, "permission", ANDROID_PERMISSION)
                            shizukuApis.grantRuntimePermission(packageName, permission, userId)
                        }
                        "app_freeze", "package_disable" ->
                            shizukuApis.setApplicationEnabledSetting(packageName, enabled = false, userId = userId)
                        "app_unfreeze", "package_enable" ->
                            shizukuApis.setApplicationEnabledSetting(packageName, enabled = true, userId = userId)
                        else -> null
                    }
                    when (binderOutcome) {
                        is BinderOutcome.Success -> {
                            Log.i("Tianyan-Host", "action=$action via binder success pkg=$packageName")
                            if (action in APP_DATABASE_GUARDED_ACTIONS) androidAppManager?.synchronize()
                            return true to buildString {
                                append("mode shizuku-api · exit 0")
                                append("\n[Android Binder] $action $packageName 成功")
                                if (action == "app_grant_permission") {
                                    append(" 权限=").append(requireHostIdentifier(args, "permission", ANDROID_PERMISSION))
                                }
                            }
                        }
                        is BinderOutcome.Failed ->
                            return false to "宿主侧拒绝该操作：${binderOutcome.message}（模式=${info.mode.shortLabel}）。请核对包名/权限名后重试。"
                        else -> Unit
                    }
                }

                val command = HostShellCommandBuilder.build(action, args)
                require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
                val hostOperationId = operationId?.takeIf { it.isNotBlank() } ?: "host-${UUID.randomUUID()}"
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
                    if (cause is CancellationException) manager.cancelShellCommand(hostOperationId)
                }
                val result = try {
                    manager.executeShellCommand(command, hostOperationId)
                } finally {
                    cancelHandle?.dispose()
                }
                Log.i(
                    "Tianyan-Host",
                    secretRedactor.redact(
                        "action=$action exit=${result.exitCode} success=${result.success}\n" +
                            "cmd=$command\nstdout=${result.stdout.take(500)}\nstderr=${result.stderr.take(300)}",
                    ),
                )
                val body = buildString {
                    append("mode ").append(info.mode.shortLabel).append(" · exit ").append(result.exitCode)
                    if (result.stdout.isNotBlank()) append("\n").append(result.stdout.trim())
                    if (result.stderr.isNotBlank()) append("\n").append(result.stderr.trim())
                }
                if (result.success && action in APP_DATABASE_GUARDED_ACTIONS) {
                    // Keep the agent's next app_list read coherent with the mutation it just made.
                    androidAppManager?.synchronize()
                }
                result.success to body
            }
        }
    }

    private suspend fun executeCachedApps(args: JsonObject): Pair<Boolean, String> {
        val repository = androidAppRepository ?: return false to "未初始化应用数据库"
        if (repository.count() == 0) return false to "应用数据库尚未初始化；请先到设置 → 应用管理完成初始化和同步。"
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val limit = JsonArgs.optionalLong(args, "limit", 50L, 1L, 200L).toInt()
        val includeSystem = args["include_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val apps = repository.search(query, if (includeSystem) limit else 200)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .take(limit)
            .toList()
        if (apps.isEmpty()) return true to "应用数据库中未找到：${query.ifBlank { "全部应用" }}"
        return true to apps.joinToString("\n") { app ->
            buildString {
                append(app.label).append(" | ").append(app.packageName)
                append(" | ").append(if (app.isSystemApp) "系统" else "用户")
                append(" | ").append(if (app.isEnabled) "启用" else "禁用")
                if (app.isSuspended) append(" | 冻结")
                if (app.isNetworkRestricted) append(" | 后台联网受限")
            }
        }
    }

    private fun virtualScreenHostActions(attachImage: (String) -> Unit = {}) = VirtualScreenHostActions(
        virtualDisplayCoordinator, virtualScreenToolkit, PACKAGE_NAME,
        ::requireHostIdentifier, JsonArgs::requireString, JsonArgs::requireInt, JsonArgs::optionalLong, ::virtualScreenSession,
        providerClient, settingsDataStore, attachImage, phoneAgentServices,
    )

    private fun requireSettingsNamespace(args: JsonObject): String {
        val namespace = JsonArgs.requireString(args, "namespace").trim().lowercase()
        require(namespace in setOf("system", "secure", "global")) { "namespace 仅支持 system/secure/global" }
        return namespace
    }

    private fun requireHostIdentifier(args: JsonObject, key: String, pattern: Regex): String {
        val value = JsonArgs.requireString(args, key).trim()
        require(pattern.matches(value)) { "$key 格式不合法" }
        return value
    }

    // VirtualScreenHostActions 需要此方法引用：
    private fun virtualScreenSession(args: JsonObject): String =
        args["session"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { ToolExecutor.VIRTUAL_SCREEN_DEFAULT_SESSION }

    companion object {
        const val MAX_COMMAND_LENGTH = 32 * 1024
        private val SETTINGS_KEY = Regex("^[A-Za-z0-9._-]{1,160}$")
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val ANDROID_PERMISSION = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val APP_DATABASE_GUARDED_ACTIONS = setOf(
            "package_disable", "package_enable", "package_uninstall_user", "app_freeze", "app_unfreeze", "app_grant_permission",
        )
        private val LOGCAT_TAG = Regex("^[A-Za-z0-9_.-]{1,80}$")
    }
}

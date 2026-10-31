package top.tianyan.app.core.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.runtime.BackgroundTaskRegistry
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.scripts.RuntimeAssetSynchronizer
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.tools.InstallEvent

/**
 * 开发套件子组件的装配、重新安装和卸载。
 * 与单个插件安装共用同一把锁，避免 apt/dpkg 同时跑两套事务。
 */
internal class BundleComponentBatch(
    private val linuxRuntime: LinuxRuntime,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
    private val notificationNotifier: ToolNotificationNotifier,
    private val installLogRepository: InstallLogRepository,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
    private val flutterSdkDownloader: FlutterSdkDownloader,
    private val installMutex: Mutex,
    private val isBatchInstalling: MutableStateFlow<Boolean>,
    private val bundleInstallState: MutableStateFlow<String?>,
    private val bundleInstallLog: MutableStateFlow<List<String>>,
    private val scope: CoroutineScope,
    private val currentDistroId: () -> String,
    private val probeInstalledComponents: suspend () -> Set<String>,
    private val localAccess: top.tianyan.app.runtime.environment.LocalBackendAccess? = null,
) {
    fun install(componentIds: Set<String>, reinstall: Boolean): Flow<InstallEvent> = runPipeline(
        componentIds = componentIds,
        title = if (reinstall) "开发套件重新安装" else "开发套件装配",
        syncAssets = true,
        prepareMessage = { names ->
            if (reinstall) "正在准备 [$names] 重新安装..." else "正在准备 [$names] 批量装配流水线..."
        },
        steps = {
            BuiltinPluginBundles.buildBatchInstallScript(componentIds, reinstall = reinstall)
        },
        verify = { installed ->
            val missing = componentIds - installed
            if (missing.isNotEmpty()) error("开发套件安装未完成，缺少组件: ${missing.joinToString()}")
        },
        successMessage = "已成功就绪",
        failurePrefix = "安装失败",
    )

    fun uninstall(componentIds: Set<String>): Flow<InstallEvent> = runPipeline(
        componentIds = componentIds,
        title = "开发套件卸载",
        syncAssets = false,
        prepareMessage = { names -> "正在准备卸载 [$names]..." },
        steps = {
            val installed = probeInstalledComponents()
            val blockers = componentIds.flatMap { id ->
                BuiltinPluginBundles.blockingDependents(id, installed, alsoRemoving = componentIds)
            }.distinctBy { it.id }
            if (blockers.isNotEmpty()) {
                error("请先卸载依赖组件: ${blockers.joinToString("、") { it.name }}")
            }
            BuiltinPluginBundles.buildBatchUninstallScript(componentIds, installed - componentIds)
        },
        verify = { installed ->
            val stillThere = componentIds.intersect(installed)
            if (stillThere.isNotEmpty()) error("组件卸载未完成，仍检测到: ${stillThere.joinToString()}")
        },
        successMessage = "已卸载",
        failurePrefix = "卸载失败",
    )

    fun startInstall(componentIds: Set<String>, reinstall: Boolean, onCompleted: (() -> Unit)?): Job =
        start("install", onCompleted) { install(componentIds, reinstall) }

    fun startUninstall(componentIds: Set<String>, onCompleted: (() -> Unit)?): Job =
        start("uninstall", onCompleted) { uninstall(componentIds) }

    private fun start(action: String, onCompleted: (() -> Unit)?, pipeline: () -> Flow<InstallEvent>): Job =
        scope.launch {
            try {
                pipeline().collect {}
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ToolManager", "Background batch $action components failed: ${error.message}", error)
            } finally {
                onCompleted?.invoke()
            }
        }

    private fun runPipeline(
        componentIds: Set<String>,
        title: String,
        syncAssets: Boolean,
        prepareMessage: (String) -> String,
        steps: suspend () -> List<String>,
        verify: (Set<String>) -> Unit,
        successMessage: String,
        failurePrefix: String,
    ): Flow<InstallEvent> = flow {
        localAccess?.requireLocal()
        if (componentIds.isEmpty()) {
            emit(InstallEvent.Completed("components", "1.0.0"))
            return@flow
        }
        val known = BuiltinPluginBundles.bundles.flatMap { it.components }.map { it.id }.toSet()
        val unknown = componentIds - known
        if (unknown.isNotEmpty()) error("未知组件: ${unknown.joinToString()}")
        val distroId = currentDistroId()
        val compNames = BuiltinPluginBundles.bundles.flatMap { it.components }
            .filter { it.id in componentIds }
            .joinToString("、") { it.name }
        installMutex.withLock {
            isBatchInstalling.value = true
            backgroundTaskRegistry.start(TASK_ID)
            bundleInstallLog.value = emptyList()
            runCatching { installLogRepository.deleteForTool(distroId, LOG_TOOL_ID) }
            emit(InstallEvent.Started("components"))
            if (syncAssets) runCatching { assetSynchronizer.syncAssetsToDistro(distroId) }
            val planned = steps()
            val initialMsg = prepareMessage(compNames)
            bundleInstallState.value = initialMsg
            appendLog(initialMsg)
            notificationNotifier.showProgress(NOTIFICATION_ID, title, initialMsg, 0.05f)
            emit(InstallEvent.Progress("components", initialMsg, 0.05f))
            try {
                executeSteps(planned, compNames, title, distroId)
                bundleInstallState.value = "正在验证已安装组件状态..."
                appendLog("正在验证已安装组件状态...")
                notificationNotifier.showProgress(NOTIFICATION_ID, title, "正在验证状态...", 0.95f)
                emit(InstallEvent.Progress("components", "正在验证已安装组件状态...", 0.95f))
                verify(probeInstalledComponents())
                notificationNotifier.showSuccess(NOTIFICATION_ID, title, successMessage)
                emit(InstallEvent.Completed("components", "1.0.0"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "装配异常"
                appendLog("$failurePrefix: $message")
                notificationNotifier.showFailed(NOTIFICATION_ID, title, message)
                emit(InstallEvent.Failed("components", message))
                throw error
            } finally {
                isBatchInstalling.value = false
                backgroundTaskRegistry.finish(TASK_ID)
                bundleInstallState.value = null
            }
        }
    }

    private suspend fun FlowCollector<InstallEvent>.executeSteps(
        steps: List<String>,
        compNames: String,
        title: String,
        distroId: String,
    ) {
        steps.forEachIndexed { index, step ->
            val progress = 0.1f + 0.8f * (index.toFloat() / steps.size.toFloat())
            val shortDesc = describe(step, index, compNames)
            val stepTimeoutMs = when {
                "/opt/tianyan/scripts/" in step ||
                    "setup_android_core.sh" in step || "setup_flutter.sh" in step -> HEAVY_SETUP_STEP_TIMEOUT_MS
                else -> DEFAULT_STEP_TIMEOUT_MS
            }
            val stepLabel = "[步骤 ${index + 1}/${steps.size}] $shortDesc"
            bundleInstallState.value = stepLabel
            appendLog(stepLabel)
            notificationNotifier.showProgress(NOTIFICATION_ID, title, stepLabel, progress)
            emit(InstallEvent.Progress("components", stepLabel, progress))
            val flutterArchive = if ("setup_flutter.sh" in step) prepareFlutterArchive(distroId) else null
            val result = linuxRuntime.execute(
                ShellCommand(
                    commandLine = step,
                    workingDirectory = "/root",
                    environment = flutterArchive?.let { mapOf("TIANYAN_FLUTTER_ARCHIVE" to it.guestPath) }.orEmpty(),
                    timeoutMs = stepTimeoutMs,
                ),
                distroId = distroId,
            )
            result.stdout.trim().takeIf { it.isNotBlank() }?.let { appendLog(it.takeLast(4000)) }
            result.stderr.trim().takeIf { it.isNotBlank() }?.let { appendLog(it.takeLast(4000)) }
            if (!result.isSuccess) {
                error("步骤执行失败: ${result.stderr.ifBlank { result.stdout }.takeLast(800)}")
            }
        }
    }

    private suspend fun prepareFlutterArchive(distroId: String): FlutterSdkArchive {
        appendLog("==> [Tianyan] 使用应用内断点下载器获取 Flutter SDK（不在 PRoot 内调用 curl）...")
        var lastFlutterLogAt = 0L
        var lastFlutterLoggedBytes = -1L
        return flutterSdkDownloader.prepare(distroId) { downloaded, total ->
            val totalText = total?.takeIf { it > 0 }?.let { " / ${it / (1024 * 1024)} MB" }.orEmpty()
            val downloadedMb = downloaded / (1024 * 1024)
            val now = System.currentTimeMillis()
            val completed = total != null && total > 0L && downloaded >= total
            val shouldLog = downloaded == 0L || completed ||
                (downloaded > lastFlutterLoggedBytes && now - lastFlutterLogAt >= FLUTTER_DOWNLOAD_LOG_INTERVAL_MS)
            if (shouldLog) {
                appendLog("[Tianyan] Flutter SDK 应用内下载：$downloadedMb MB$totalText")
                lastFlutterLoggedBytes = downloaded
                lastFlutterLogAt = now
            }
        }
    }

    private fun describe(step: String, index: Int, compNames: String): String = when {
        index == 0 -> "正在创建 dpkg 配置目录..."
        index == 1 -> "正在写入 PRoot dpkg 安全策略..."
        index == 2 -> "正在清理 dpkg/apt 残留锁与临时文件..."
        "dpkg --remove" in step -> "正在清理无法完成的可选软件包事务..."
        "dpkg --configure" in step -> "正在恢复未完成的 dpkg 事务..."
        "npm uninstall" in step -> "正在移除 [$compNames] 的全局命令..."
        "setup_mcp_catalog" in step -> "正在从镜像安装 [$compNames] 的 MCP 能力并核对发布摘要..."
        step.startsWith("rm -rf --") || step.startsWith("rm -f --") -> "正在移除 [$compNames] 的程序文件..."
        "apt-get" in step && " update " in step -> "正在同步软件源并聚合下载全部依赖包..."
        "apt-get" in step && " remove " in step -> "正在卸载 [$compNames] 的独占软件包..."
        "apt-get" in step && "--reinstall" in step -> "正在重新安装 [$compNames] 的软件包..."
        "apt-get" in step -> "正在安装 [$compNames] 所需系统依赖..."
        "gradle" in step -> "正在部署并链接 Gradle 8.14.2 自动化构建环境..."
        "setup_android_core" in step -> "正在部署 Android SDK 平台包与 Gradle 构建环境 (国内镜像加速)..."
        "termux_ndk" in step -> "正在下载、校验并原子装配 Linux AArch64 NDK..."
        "jadx" in step -> "正在部署 JADX-CLI 源码反编译工具包..."
        "android" in step -> "正在配置 Android SDK 官方开发工具链..."
        "flutter" in step -> "正在拉取并配置 Flutter SDK 跨端开发环境..."
        else -> "正在执行环境准备步骤..."
    }

    private fun appendLog(message: String) {
        val incoming = message.trim().lineSequence().filter { it.isNotBlank() }.toList()
        if (incoming.isEmpty()) return
        val retained = (bundleInstallLog.value + incoming).toMutableList()
        var totalChars = retained.sumOf { it.length + 1 }
        while (totalChars > MAX_LOG_CHARS && retained.size > 1) {
            totalChars -= retained.removeAt(0).length + 1
        }
        bundleInstallLog.value = retained.map { line ->
            if (line.length <= MAX_LINE_CHARS) line else line.takeLast(MAX_LINE_CHARS)
        }
        scope.launch {
            runCatching {
                incoming.forEach { line ->
                    installLogRepository.insert(
                        InstallLogEntity(
                            distroId = currentDistroId(),
                            toolId = LOG_TOOL_ID,
                            event = "bundle",
                            message = if (line.length <= MAX_LINE_CHARS) line else line.takeLast(MAX_LINE_CHARS),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val LOG_TOOL_ID = "components"
        const val MAX_LOG_LINES = 2048
        private const val TASK_ID = "dev-bundle-install"
        private const val NOTIFICATION_ID = "dev_bundle_install"
        private const val DEFAULT_STEP_TIMEOUT_MS = 10 * 60_000L
        private const val HEAVY_SETUP_STEP_TIMEOUT_MS = 45 * 60_000L
        private const val FLUTTER_DOWNLOAD_LOG_INTERVAL_MS = 2_000L
        private const val MAX_LOG_CHARS = 120 * 1024
        private const val MAX_LINE_CHARS = 8 * 1024
    }
}

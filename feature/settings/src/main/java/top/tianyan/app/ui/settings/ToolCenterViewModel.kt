package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.datastore.FirstUseGuidePreferences
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.tools.ToolInstallProgress
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.core.tools.ToolVerification
import top.tianyan.app.runtime.LinuxRuntime
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.PluginBundle
import top.tianyan.app.core.model.PluginComponent
import top.tianyan.app.core.tools.LocalPluginImportState

class ToolCenterViewModel(
    private val toolManager: ToolManager,
    private val linuxRuntime: LinuxRuntime,
    private val firstUseGuidePreferences: FirstUseGuidePreferences,
    private val logger: AppLogger,
    private val remoteServers: RemoteServerRepository,
    private val mcpServerRepository: McpServerRepository,
) : ViewModel() {

    val tools: StateFlow<List<ToolEntity>> = toolManager.observeTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val installProgress: StateFlow<Map<String, ToolInstallProgress>> = toolManager.installProgress
    val verifications: StateFlow<Map<String, ToolVerification>> = toolManager.verifications

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _viewingLogsToolId = MutableStateFlow<String?>(null)
    val viewingLogsToolId: StateFlow<String?> = _viewingLogsToolId.asStateFlow()

    private val _toolLogs = MutableStateFlow<List<InstallLogEntity>>(emptyList())
    val toolLogs: StateFlow<List<InstallLogEntity>> = _toolLogs.asStateFlow()

    /** 刷新插件目录进行中标记（顶栏刷新按钮显示进度并防重复点击）。 */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** 操作失败的可观察错误（卸载失败 / 插件目录同步失败），UI 以横幅展示。 */
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    fun consumeOperationError() {
        _operationError.value = null
    }

    val localPluginImport: StateFlow<LocalPluginImportState> = toolManager.localPluginImportState

    /** 首次进入插件中心的离线包导入引导：false 表示尚未看过，需要展示遮罩引导。 */
    val importGuideShown: StateFlow<Boolean> = firstUseGuidePreferences.firstUseGuidesShown
        .map { it.contains(GUIDE_IMPORT_OFFLINE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markImportGuideShown() {
        viewModelScope.launch {
            firstUseGuidePreferences.markFirstUseGuideShown(GUIDE_IMPORT_OFFLINE)
        }
    }

    fun syncRegistry() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                toolManager.syncRegistry()
            } catch (e: Exception) {
                logger.w("Failed to sync tool registry: ${e.message}", e)
                _operationError.value = "刷新插件目录失败，请检查网络后重试"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun importLocalPlugin(uri: Uri) {
        if (blockedByRemote()) return
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: "本地插件包"
        toolManager.startLocalPluginImport(uri, fileName)
    }

    fun clearLocalPluginImportState() {
        toolManager.clearLocalPluginImportState()
    }

    fun confirmLocalPluginImport() {
        if (blockedByRemote()) return
        toolManager.confirmLocalPluginImport()
    }

    fun cancelLocalPluginImport() {
        toolManager.cancelLocalPluginImport()
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun toolSource(toolId: String): String = toolManager.manifest(toolId)?.source ?: "REMOTE"

    /**
     * 插件与底层工具只安装在本机 PRoot：远程主系统下拦截安装类操作并给出明确原因，
     * 不静默失败。这些组件依赖 artifacts / downloads / buildBindings 与本地运行时，远程环境没有对应适配。
     */
    private fun blockedByRemote(): Boolean {
        if (remoteServers.activeId.value == null) return false
        _operationError.value = "当前主系统为 SSH 远程服务器，插件与底层工具仅安装在本机 PRoot，不会同步到服务器；请先在「Linux 系统管理」切回本地系统。"
        return true
    }

    fun installTool(toolId: String) {
        if (blockedByRemote()) return
        toolManager.startInstall(toolId)
    }

    fun updateTool(toolId: String) {
        if (blockedByRemote()) return
        toolManager.startUpdate(toolId)
    }

    fun uninstallTool(toolId: String) {
        if (blockedByRemote()) return
        viewModelScope.launch {
            try {
                toolManager.uninstall(toolId)
            } catch (e: Exception) {
                logger.w("Tool uninstall failed: $toolId, ${e.message}", e)
                _operationError.value = "卸载「$toolId」失败，请稍后重试；可查看日志了解详情"
            }
        }
    }

    /** 读取指定工具的全部安装日志（供「AI 自愈」入口与日志弹窗使用同一份全量数据）。 */
    fun fullToolLogs(toolId: String, onReady: (List<String>) -> Unit) {
        viewModelScope.launch {
            val logs = try {
                toolManager.observeInstallLogs(toolId).first().map { "[${it.event}] ${it.message}" }
            } catch (e: Exception) {
                logger.w("Failed to read tool logs for $toolId: ${e.message}", e)
                emptyList()
            }
            onReady(logs)
        }
    }

    fun verifyTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolManager.verify(toolId)
            } catch (e: Exception) {
                logger.w("Tool verification failed: $toolId, ${e.message}", e)
            }
        }
    }

    fun clearLogs(toolId: String) {
        viewModelScope.launch {
            try {
                toolManager.clearLogs(toolId)
                _toolLogs.value = emptyList()
            } catch (e: Exception) {
                logger.w("Failed to clear logs for $toolId: ${e.message}", e)
            }
        }
    }

    fun viewLogs(toolId: String?) {
        _viewingLogsToolId.value = toolId
        if (toolId != null) {
            viewModelScope.launch {
                toolManager.observeInstallLogs(toolId).collect { logs ->
                    _toolLogs.value = logs
                }
            }
        }
    }

    init {
        syncRegistry()
        refreshInstalledStatus()
    }

    // ==================== 聚合大插件套件与子组件状态管理 ====================
    val pluginBundles: List<PluginBundle> = BuiltinPluginBundles.bundles

    private val _installedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val installedComponentIds: StateFlow<Set<String>> = _installedComponentIds.asStateFlow()

    private val _activeBundle = MutableStateFlow<PluginBundle?>(null)
    val activeBundle: StateFlow<PluginBundle?> = _activeBundle.asStateFlow()

    private val _selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponents: StateFlow<Set<String>> = _selectedComponents.asStateFlow()

    val isInstallingComponents: StateFlow<Boolean> = toolManager.isBatchInstalling
    val componentInstallProgress: StateFlow<String?> = toolManager.bundleInstallState
    val componentInstallLog: StateFlow<List<String>> = toolManager.bundleInstallLog

    fun refreshInstalledStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val installed = toolManager.probeInstalledComponents()
                _installedComponentIds.value = installed
                // 安装状态以组件探针为唯一真相：能力组件就绪后把对应 MCP 服务自动开启一次，
                // 卸载后回到关闭态（用户显式切换过的行不会被覆盖）。
                runCatching { mcpServerRepository.syncInstalledCapabilities(installed) }
                    .onFailure { logger.w("同步 MCP 能力安装状态失败: ${it.message}", it) }
            } catch (e: Exception) {
                logger.w("Failed to probe installed components: ${e.message}", e)
            }
        }
    }

    fun openBundleSetup(bundle: PluginBundle) {
        val installed = _installedComponentIds.value
        // 只默认勾选尚未安装的必选基座；已安装的组件不再勾选
        val uninstalledRequired = bundle.components.filter { it.id !in installed && it.isRequired }.map { it.id }.toSet()
        _selectedComponents.value = uninstalledRequired
        _activeBundle.value = bundle
    }

    fun closeBundleSetup() {
        _activeBundle.value = null
    }

    fun toggleComponent(component: PluginComponent) {
        val installed = _installedComponentIds.value
        if (component.id in installed) return // 已安装组件无需重复勾选
        val isUninstalledRequired = component.isRequired && component.id !in installed
        if (isUninstalledRequired) return // 尚未安装的必选基座锁定勾选
        val current = _selectedComponents.value
        _selectedComponents.value = if (component.id in current) current - component.id else current + component.id
    }

    fun installActiveBundleComponents() {
        if (blockedByRemote()) return
        val selected = _selectedComponents.value
        if (selected.isEmpty() || toolManager.isBatchInstalling.value) return

        // 立即关闭装配弹窗，后台静默装配并发送系统通知栏进度
        _activeBundle.value = null

        toolManager.startBackgroundBatchInstall(selected) {
            refreshInstalledStatus()
            syncRegistry()
        }
    }

    fun reinstallComponent(component: PluginComponent) {
        if (blockedByRemote()) return
        if (toolManager.isBatchInstalling.value) return
        _operationError.value = null
        _activeBundle.value = null
        toolManager.startBackgroundBatchInstall(setOf(component.id), reinstall = true) {
            refreshInstalledStatus()
        }
    }

    /** 仍有其他已装配组件依赖它时返回原因，并写到操作错误横幅。 */
    fun uninstallBlockedReason(component: PluginComponent): String? {
        val blockers = BuiltinPluginBundles.blockingDependents(
            component.id,
            _installedComponentIds.value,
        )
        if (blockers.isEmpty()) return null
        val message = "请先卸载依赖「${component.name}」的组件：${blockers.joinToString("、") { it.name }}"
        _operationError.value = message
        return message
    }

    fun uninstallComponent(component: PluginComponent) {
        if (blockedByRemote()) return
        if (toolManager.isBatchInstalling.value) return
        if (uninstallBlockedReason(component) != null) return
        _activeBundle.value = null
        toolManager.startBackgroundBatchUninstall(setOf(component.id)) {
            refreshInstalledStatus()
        }
    }

    // 兼容原 devSuites 接口
    val devSuites: List<PluginBundle> get() = pluginBundles
    val showSuiteDialog: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    val selectedSuites: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
    val isInstallingSuites: StateFlow<Boolean> get() = isInstallingComponents
    val suiteInstallProgress: StateFlow<String?> get() = componentInstallProgress
    fun openSuiteDialog() {
        pluginBundles.firstOrNull()?.let { openBundleSetup(it) }
    }
    fun closeSuiteDialog() = closeBundleSetup()
    fun toggleSuite(id: String) {}
    fun installSelectedSuites() {}

    companion object {
        /** 首次引导登记 ID：插件中心「导入离线插件包」入口。 */
        const val GUIDE_IMPORT_OFFLINE = "tool_center_import_offline"
    }
}

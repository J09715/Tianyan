package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.core.model.McpUpdateStatus
import top.tianyan.app.core.tools.McpCatalogUpdater
import top.tianyan.app.core.tools.ToolManager

/**
 * 「MCP 能力仓库」的就绪状态与版本视图模型。
 *
 * 外置后的 MCP 能力（WebSearch / CodeGraph 等）来自上游 npm 包，不再随 APK 内置，
 * 因此服务存在两种"关着"的原因，界面必须分开表达：
 * - **已装但用户没开**：切开关即可；
 * - **还没装**：切开关没有意义，服务进程根本不存在，硬开只会让每轮对话的工具发现白等一个超时。
 *
 * 判定依据是开发套件的**组件探针**（[ToolManager.probeInstalledComponents]），而不是另存一份
 * 安装标记——组件探针是唯一真相，避免出现「数据库说装了、文件其实不在」的第二真相
 * （这正是 MIGRATION_35_36 那次 120 秒启动阻塞的成因）。
 *
 * 另一半职责是**跟着上游走**：这正是不内置的理由。已装版本从落位标记读、最新版本从镜像读，
 * 两者都由 [McpCatalogUpdater] 经装配脚本取回；本层只维护"检查中/升级中/结论"这三个状态，
 * 不缓存也不落库——版本是可外部变化的事实，落库就又会变成第二真相。
 */
class McpCatalogViewModel(
    private val mcpServerRepository: McpServerRepository,
    private val toolManager: ToolManager,
    private val updater: McpCatalogUpdater,
    private val remoteServers: RemoteServerRepository,
    private val logger: AppLogger,
) : ViewModel() {

    private val installedComponentIds = MutableStateFlow<Set<String>>(emptySet())

    /** 需要先安装组件、而组件当前缺失的 MCP 服务：服务 id → 待安装的组件名。 */
    val installBlocked: StateFlow<Map<String, String>> = combine(
        mcpServerRepository.servers, installedComponentIds,
    ) { servers, installed ->
        servers.asSequence()
            .filter { server ->
                server.requiresInstall &&
                    server.installComponentId.isNotBlank() &&
                    server.installComponentId !in installed
            }
            .associate { server -> server.id to componentName(server.installComponentId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _installNotice = MutableStateFlow<String?>(null)

    /** 一次性提示（"该能力需要先安装组件"），展示后由 UI 调用 [consumeInstallNotice] 清除。 */
    val installNotice: StateFlow<String?> = _installNotice.asStateFlow()

    fun consumeInstallNotice() {
        _installNotice.value = null
    }

    /**
     * 切换开关前的门控：未安装的能力直接拒绝并给出直达提示。
     * 返回 false 表示本次切换被拦下，UI 不应再调用 [SettingsViewModel.toggleMcpServer]。
     */
    fun onToggleRequested(serverId: String, enabled: Boolean): Boolean {
        if (!enabled) return true
        val component = installBlocked.value[serverId] ?: return true
        _installNotice.value = "「$component」尚未安装。请到「设置 → 工具中心 → MCP 能力仓库」安装后再开启。"
        return false
    }

    // ==================== 版本：检查更新 / 一键升级 ====================

    private val _updateStatuses = MutableStateFlow<Map<String, McpUpdateStatus>>(emptyMap())

    /** 能力 id → 版本检查结论（只在本次界面存活期内有效）。 */
    val updateStatuses: StateFlow<Map<String, McpUpdateStatus>> = _updateStatuses.asStateFlow()

    private val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates.asStateFlow()

    private val _upgradingId = MutableStateFlow<String?>(null)
    val upgradingId: StateFlow<String?> = _upgradingId.asStateFlow()

    private val _updateNotice = MutableStateFlow<String?>(null)

    /** 版本相关的一次性提示，由 UI 调用 [consumeUpdateNotice] 清除。 */
    val updateNotice: StateFlow<String?> = _updateNotice.asStateFlow()

    fun consumeUpdateNotice() {
        _updateNotice.value = null
    }

    /** 已装的能力才值得联网问上游；逐个查而不是并发，避免在 PRoot 里一次打满多个 npm 进程。 */
    fun checkUpdates() {
        if (_checkingUpdates.value) return
        val targets = installedEntries()
        if (targets.isEmpty()) {
            _updateNotice.value = "还没有已安装的能力。先装配一个再检查更新。"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _checkingUpdates.value = true
            try {
                val results = targets.associate { it.id to updater.check(it) }
                _updateStatuses.value = _updateStatuses.value + results
            } catch (e: Exception) {
                logger.w("检查 MCP 能力更新失败: ${e.message}", e)
                _updateNotice.value = "检查更新失败，请确认本机 Linux 运行时可用后重试"
            } finally {
                _checkingUpdates.value = false
            }
        }
    }

    /** 一键升级到镜像上的最新版本。 */
    fun upgrade(entryId: String) {
        if (_upgradingId.value != null) return
        if (blockedByRemote()) return
        val entry = McpCatalog.find(entryId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _upgradingId.value = entryId
            try {
                val status = updater.upgrade(entry)
                _updateStatuses.value = _updateStatuses.value + (entryId to status)
                _updateNotice.value = if (status.error.isBlank()) {
                    "「${entry.name}」当前版本 v${status.installedVersion}；后续调用自动使用已安装版本，无需手动重启"
                } else {
                    "「${entry.name}」升级失败：${status.error}"
                }
            } catch (e: Exception) {
                logger.w("升级 MCP 能力失败: $entryId, ${e.message}", e)
                _updateNotice.value = "「${entry.name}」升级失败，请稍后重试"
            } finally {
                _upgradingId.value = null
            }
        }
    }

    private fun installedEntries() = McpCatalog.installable.filter { it.installComponentId in installedComponentIds.value }

    private fun blockedByRemote(): Boolean {
        if (remoteServers.activeId.value == null) return false
        _updateNotice.value = "当前主系统为 SSH 远程服务器，MCP 能力仅安装在本机 PRoot；请先在「Linux 系统管理」切回本地系统。"
        return true
    }

    /**
     * 重探组件状态，并把探针结果投影到 MCP 启停：
     * 首次装好对应组件时自动开启一次；组件缺失时回到关闭态。用户显式切换过的行不会被覆盖。
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { toolManager.probeInstalledComponents() }
                .onSuccess { installed ->
                    installedComponentIds.value = installed
                    // 组件已卸载的能力不该留着旧版本结论，否则界面会显示一个不存在的「可更新」。
                    _updateStatuses.value = _updateStatuses.value.filterKeys { id ->
                        val componentId = McpCatalog.find(id)?.installComponentId
                        componentId != null && componentId in installed
                    }
                    runCatching { mcpServerRepository.syncInstalledCapabilities(installed) }
                        .onFailure { logger.w("同步 MCP 能力安装状态失败: ${it.message}", it) }
                }
                .onFailure { logger.w("探测开发套件组件状态失败: ${it.message}", it) }
        }
    }

    private fun componentName(componentId: String): String =
        BuiltinPluginBundles.bundles
            .flatMap { bundle -> bundle.components }
            .firstOrNull { component -> component.id == componentId }
            ?.name
            ?: componentId
}

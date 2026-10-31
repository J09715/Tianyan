package top.tianyan.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.ToolchainReport
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.doctor.ToolchainInspector
import top.tianyan.app.runtime.doctor.ToolchainRepairer

/**
 * 沙箱工具链 ViewModel。
 *
 * 只负责发起检测 / 补齐并观察状态。补齐的两步（套件组件，然后 apt）在
 * [ToolchainRepairer] 自己的协程里跑，离开聊天不会把 apt/dpkg 杀掉。
 * [checkBusy] 与 [installBusy] 分开：重新检测不会把正在进行的安装清掉。
 */
class ToolchainViewModel(
    private val inspector: ToolchainInspector,
    private val repairer: ToolchainRepairer,
    private val toolManager: ToolManager,
    private val linuxRuntime: LinuxRuntime,
) : ViewModel() {

    private val _report = MutableStateFlow<ToolchainReport?>(null)
    val report: StateFlow<ToolchainReport?> = _report.asStateFlow()

    private val _checkBusy = MutableStateFlow(false)
    val checkBusy: StateFlow<Boolean> = _checkBusy.asStateFlow()

    /** 补齐是否在跑。来自进程级 [ToolchainRepairer]，刷新检测不会写它。 */
    val installBusy: StateFlow<Boolean> = repairer.running

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _hasGap = MutableStateFlow(false)
    val hasGap: StateFlow<Boolean> = _hasGap.asStateFlow()

    val logs: StateFlow<List<String>> = repairer.logs

    val outcome: StateFlow<ToolchainRepairer.RepairOutcome?> = repairer.outcome

    /** 开发套件安装进度（来自 ToolManager，与本面板的 apt 日志拼在一起展示）。 */
    val installState: StateFlow<String?> = toolManager.bundleInstallState

    /** 当前发行版是否 Debian 系（packageManager == "apt"），非 Debian 系隐藏补齐。 */
    val debBased: Boolean
        get() {
            val activeId = linuxRuntime.activeDistroId.value
            val active = linuxRuntime.installedDistros.value.firstOrNull { it.id == activeId }
            return active?.packageManager == "apt"
        }

    init {
        viewModelScope.launch {
            inspector.report.collect { report ->
                if (report != null) publish(report)
            }
        }
    }

    /**
     * 检测。默认走 [ToolchainInspector] 的 10 分钟缓存；
     * [force] 为 true 时忽略缓存（用户点「重新检测」，或补齐结束后由 repairer 自己强制刷新）。
     */
    fun refresh(force: Boolean = false) {
        if (_checkBusy.value) return
        if (!force) {
            val cached = inspector.cachedIfFresh()
            if (cached != null) {
                publish(cached)
                return
            }
        }
        _checkBusy.value = true
        viewModelScope.launch {
            try {
                inspector.inspect(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _failed.value = true
                _report.value = null
                _hasGap.value = false
            } finally {
                _checkBusy.value = false
            }
        }
    }

    /** 用户确认后才调用。已有补齐在跑时 [ToolchainRepairer.start] 会拒绝。 */
    fun repairMissing() {
        val current = _report.value ?: return
        if (current.repairable.isEmpty()) return
        repairer.start(current)
    }

    private fun publish(report: ToolchainReport) {
        if (report.probeFailed) {
            _failed.value = true
            _report.value = null
            _hasGap.value = false
            return
        }
        _failed.value = false
        _report.value = report
        _hasGap.value = report.repairable.isNotEmpty()
    }
}

package top.tianyan.app.ui.home

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.DoctorReport
import top.tianyan.app.core.model.ExecutionMode
import top.tianyan.app.core.model.RepairProgress
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.terminal.TerminalSessionManager
import top.tianyan.app.runtime.DistributionCatalog
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.BackgroundTaskRegistry
import top.tianyan.app.runtime.doctor.EnvironmentDoctor
import top.tianyan.app.runtime.doctor.EnvironmentRepairer
import top.tianyan.app.runtime.privilege.PrivilegeManager
import top.tianyan.app.runtime.privilege.PrivilegeAvailability
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.webchat.WebChatBridgeServer
import top.tianyan.app.runtime.webchat.WebChatServerStatus

/** 当前运行特权模式的展示状态（首页徽章与规格卡共用）。 */
data class ExecutionModeStatus(
    val mode: ExecutionMode = ExecutionMode.PROOT,
    val preferredMode: ExecutionMode = ExecutionMode.PROOT,
    /** 当前模式的特权是否实际生效（PRoot 恒为 true；Shizuku/Root 依赖实时授权探测）。 */
    val active: Boolean = true,
    /** 正在探测授权状态。 */
    val checking: Boolean = false,
    val degraded: Boolean = false,
    val reason: String = "",
)

data class SystemResourceMetrics(
    val memoryUsedMb: Long = 0,
    val memoryTotalMb: Long = 0,
    val memoryUsagePercent: Int = 0,
    val appHeapUsedMb: Long = 0,
    val storageUsedGb: Double = 0.0,
    val storageTotalGb: Double = 0.0,
    val storageUsagePercent: Int = 0,
    val activeProcessCount: Int = 0,
    val runningServicesCount: Int = 0,
    val cpuArch: String = "aarch64",
    val linuxDistro: String = "Ubuntu 24.04 LTS",
    val engineVersion: String = "proot-distro 5.10.0 · Link2Symlink",
    val hostAndroidVersion: String = "Android",
    val uptimeFormatted: String = "00:00",
)

class HomeViewModel(
    private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val environmentDoctor: EnvironmentDoctor,
    private val environmentRepairer: EnvironmentRepairer,
    private val terminalSessionManager: TerminalSessionManager,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
    private val privilegeManager: PrivilegeManager,
    private val logger: AppLogger,
    private val webChatBridgeServer: WebChatBridgeServer? = null,
) : ViewModel() {

    // WebChat 电脑大屏协作：局域网桥接服务状态（详见 WebChatDashboardCard）
    val webChatStatus: StateFlow<WebChatServerStatus> =
        webChatBridgeServer?.status ?: MutableStateFlow(WebChatServerStatus()).asStateFlow()

    fun toggleWebChat(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                webChatBridgeServer?.start()
            } else {
                webChatBridgeServer?.stop()
            }
        }
    }

    val runtimeState: StateFlow<RuntimeState> = linuxRuntime.state
    val installedDistros = linuxRuntime.installedDistros
    val activeDistroId = linuxRuntime.activeDistroId

    private val _executionModeStatus = MutableStateFlow(ExecutionModeStatus())
    val executionModeStatus: StateFlow<ExecutionModeStatus> = _executionModeStatus.asStateFlow()

    private val _initializing = MutableStateFlow(false)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    // 发行版切换中（关闭旧会话 + 切换 + 刷新指标期间置位，防止重复点击与无反馈停顿）
    private val _switchingDistro = MutableStateFlow(false)
    val switchingDistro: StateFlow<Boolean> = _switchingDistro.asStateFlow()

    private val _metrics = MutableStateFlow(SystemResourceMetrics())
    val metrics: StateFlow<SystemResourceMetrics> = _metrics.asStateFlow()

    // 运行环境健康体检状态
    private val _doctorReport = MutableStateFlow<DoctorReport?>(null)
    val doctorReport: StateFlow<DoctorReport?> = _doctorReport.asStateFlow()

    private val _isCheckingDoctor = MutableStateFlow(false)
    val isCheckingDoctor: StateFlow<Boolean> = _isCheckingDoctor.asStateFlow()

    // 一键自愈修复状态与进度 (通过 EnvironmentRepairer 单例持久化，切页面不丢失)
    val repairProgress: StateFlow<RepairProgress?> = environmentRepairer.progress
    val isRepairing: StateFlow<Boolean> = environmentRepairer.isRepairing

    private var initializationJob: Job? = null
    private var doctorJob: Job? = null
    private var metricsRefreshJob: Job? = null

    init {
        startMetricsMonitoring()
        observeRuntimeStateForDoctor()
        observeRepairCompletion()
        observeExecutionMode()
    }

    /** 直接消费全应用共享的权限状态机，避免首页自行维护第二套授权真相。 */
    private fun observeExecutionMode() {
        viewModelScope.launch {
            privilegeManager.state.collect { state ->
                _executionModeStatus.value = ExecutionModeStatus(
                    mode = state.effectiveMode,
                    preferredMode = state.preferredMode,
                    active = state.availability == PrivilegeAvailability.ACTIVE,
                    checking = state.availability == PrivilegeAvailability.CHECKING,
                    degraded = state.availability == PrivilegeAvailability.DEGRADED,
                    reason = state.reason,
                )
            }
        }
    }

    /** 手动实时复核；若高权限已失效，PrivilegeManager 会统一降级并广播状态。 */
    fun refreshExecutionModeStatus() {
        viewModelScope.launch {
            try {
                privilegeManager.getPrivilegeInfo()
            } catch (e: Exception) {
                logger.w("HomeViewModel: Failed to refresh privilege status: ${e.message}", e)
            }
        }
    }

    private fun observeRepairCompletion() {
        viewModelScope.launch {
            environmentRepairer.progress.collect { progress ->
                if (progress?.isCompleted == true) {
                    runDoctorCheck()
                }
            }
        }
    }

    private fun startMetricsMonitoring() {
        viewModelScope.launch {
            // 性能优化：指标刷新频率从固定的 3 秒改为差异化策略
            // - 快速变化指标（进程数、运行时间）：每 1 秒刷新
            // - 慢速变化指标（内存、存储）：每 5 秒刷新
            // 减少系统调用频率，降低功耗和 CPU 占用
            var counter = 0
            while (isActive) {
                val shouldRefreshSlowMetrics = (counter % 5 == 0)
                refreshMetrics(refreshSlowMetrics = shouldRefreshSlowMetrics)
                delay(1000L)
                counter++
            }
        }
    }

    private fun observeRuntimeStateForDoctor() {
        viewModelScope.launch {
            runtimeState.collect { state ->
                if (state is RuntimeState.Ready && _doctorReport.value == null && !_isCheckingDoctor.value) {
                    runDoctorCheck()
                }
            }
        }
    }

    fun runDoctorCheck() {
        if (_isCheckingDoctor.value || isRepairing.value) return
        doctorJob = viewModelScope.launch {
            _isCheckingDoctor.value = true
            try {
                val report = environmentDoctor.check()
                _doctorReport.value = report
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w("Doctor check failed: ${e.message}", e)
            } finally {
                _isCheckingDoctor.value = false
            }
        }
    }

    fun startAutoRepair() {
        environmentRepairer.startRepair()
    }

    fun cancelAutoRepair() {
        environmentRepairer.cancelRepair()
    }

    fun refreshMetrics(refreshSlowMetrics: Boolean = true) {
        if (metricsRefreshJob?.isActive == true) return
        metricsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 快速指标：每次刷新（进程数、运行时间）
                val bgProcesses = try { linuxRuntime.listBackground() } catch (e: Exception) { emptyList() }
                val activeProcs = bgProcesses.size + backgroundTaskRegistry.activeTasks.value.size

                // 4. 运行时间 (基于全局应用启动时间戳，切换 Tab 不会重置)
                val elapsedSec = (SystemClock.elapsedRealtime() - APP_START_TIME) / 1000
                val hours = elapsedSec / 3600
                val minutes = (elapsedSec % 3600) / 60
                val seconds = elapsedSec % 60
                val uptime = if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
                             else String.format("%02d:%02d", minutes, seconds)

                // 慢速指标：每 5 秒刷新一次（内存、存储），减少系统调用
                var usedMemMb = _metrics.value.memoryUsedMb
                var totalMemMb = _metrics.value.memoryTotalMb
                var memPercent = _metrics.value.memoryUsagePercent
                var heapUsedMb = _metrics.value.appHeapUsedMb
                var usedGb = _metrics.value.storageUsedGb
                var totalGb = _metrics.value.storageTotalGb
                var storagePercent = _metrics.value.storageUsagePercent
                
                if (refreshSlowMetrics) {
                    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager?.getMemoryInfo(memInfo)
                    totalMemMb = memInfo.totalMem / (1024 * 1024)
                    val availMemMb = memInfo.availMem / (1024 * 1024)
                    usedMemMb = (totalMemMb - availMemMb).coerceAtLeast(0)
                    memPercent = if (totalMemMb > 0) ((usedMemMb * 100) / totalMemMb).toInt() else 0

                    val rt = Runtime.getRuntime()
                    heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

                    // 2. 存储空间 (Disk / Rootfs)
                    val rootfsDir = try { linuxRuntime.rootfsPath() } catch (e: Exception) { context.filesDir }
                    val stat = StatFs(if (rootfsDir.exists()) rootfsDir.absolutePath else context.filesDir.absolutePath)
                    val totalBytes = stat.totalBytes
                    val availBytes = stat.availableBytes
                    val usedBytes = (totalBytes - availBytes).coerceAtLeast(0)
                    totalGb = kotlin.math.round(totalBytes.toDouble() / (1024 * 1024 * 1024) * 10) / 10
                    usedGb = kotlin.math.round(usedBytes.toDouble() / (1024 * 1024 * 1024) * 10) / 10
                    storagePercent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
                }

            val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"
            val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            withContext(Dispatchers.Main.immediate) {
                val currentDistro = linuxRuntime.activeDistroId.value
                val distroDisplayName = DistributionCatalog.require(currentDistro).displayName

                _metrics.value = SystemResourceMetrics(
                    memoryUsedMb = usedMemMb,
                    memoryTotalMb = totalMemMb,
                    memoryUsagePercent = memPercent,
                    appHeapUsedMb = heapUsedMb,
                    storageUsedGb = usedGb,
                    storageTotalGb = totalGb,
                    storageUsagePercent = storagePercent,
                    activeProcessCount = activeProcs,
                    runningServicesCount = bgProcesses.count { it.type == ProcessType.SERVICE },
                    cpuArch = arch,
                    linuxDistro = distroDisplayName,
                    engineVersion = "proot-distro 5.10.0 · Link2Symlink",
                    hostAndroidVersion = androidVer,
                    uptimeFormatted = uptime,
                )
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w("HomeViewModel: Failed to refresh metrics: ${e.message}", e)
            }
        }
    }

    fun switchDistro(distroId: String) {
        if (_switchingDistro.value) return
        viewModelScope.launch {
            _switchingDistro.value = true
            try {
                // 1. 先关闭所有旧系统的终端会话（PTY 进程），防止并发冲突崩溃
                terminalSessionManager.closeAllSessions()
                // 2. 切换活动发行版（更新 DataStore + 刷新列表）
                linuxRuntime.switchActiveDistro(distroId)
                // 3. 刷新仪表盘数据
                refreshMetrics()
            } catch (e: Exception) {
                logger.w("HomeViewModel: Failed to switch distro: ${e.message}", e)
            } finally {
                _switchingDistro.value = false
            }
        }
    }

    fun initializeRuntime() {
        if (_initializing.value || initializationJob?.isActive == true) return
        initializationJob = viewModelScope.launch {
            _initializing.value = true
            try {
                linuxRuntime.initialize()
            } finally {
                _initializing.value = false
                initializationJob = null
                refreshMetrics()
                runDoctorCheck()
            }
        }
    }

    fun cancelInitialization() {
        initializationJob?.cancel()
    }

    companion object {
        private val APP_START_TIME = SystemClock.elapsedRealtime()
    }
}

package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.harness.workflow.SentinelState
import top.tianyan.app.harness.workflow.WorkflowScheduleRepository
import top.tianyan.app.harness.workflow.disableSentinelSchedule
import top.tianyan.app.harness.workflow.enableSentinelSchedule
import top.tianyan.app.harness.workflow.observeSentinelState
import top.tianyan.app.runtime.webchat.WebChatBridgeServer
import top.tianyan.app.runtime.webchat.WebChatServerStatus

/**
 * 乾坤「特色功能」栏的状态载体：晨报哨兵（定时计划驱动的每日巡检）
 * 与 WebChat 电脑大屏协作（复用 Activity 级单例 WebChatBridgeServer）。
 * 独立于 SettingsViewModel，避免其触达棘轮基线。
 */
class FeatureHubViewModel(
    private val webChatBridgeServer: WebChatBridgeServer?,
    private val workflowRepository: WorkflowRepository,
    private val scheduleRepository: WorkflowScheduleRepository,
    private val logger: AppLogger,
) : ViewModel() {

    private val _sentinelState = MutableStateFlow(SentinelState())
    val sentinelState: StateFlow<SentinelState> = _sentinelState.asStateFlow()

    val webChatStatus: StateFlow<WebChatServerStatus> =
        webChatBridgeServer?.status ?: MutableStateFlow(WebChatServerStatus()).asStateFlow()

    init {
        viewModelScope.observeSentinelState(scheduleRepository) { _sentinelState.value = it }
    }

    fun enableSentinel(hour: Int, minute: Int) {
        viewModelScope.launch {
            runCatching { enableSentinelSchedule(scheduleRepository, workflowRepository, hour, minute) }
                .onFailure { logger.w("FeatureHubViewModel: enableSentinel failed: ${it.message}", it) }
        }
    }

    fun disableSentinel() {
        viewModelScope.launch {
            runCatching { disableSentinelSchedule(scheduleRepository) }
                .onFailure { logger.w("FeatureHubViewModel: disableSentinel failed: ${it.message}", it) }
        }
    }

    fun toggleWebChat(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                webChatBridgeServer?.start()
            } else {
                webChatBridgeServer?.stop()
            }
        }
    }
}

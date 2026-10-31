package top.tianyan.app.core.common.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 应用跨模块/跨系统组件（Activity、Service、Receiver 等）的全局导航目标。 */
sealed interface AppNavigationTarget {
    /** 无线 ADB 与日志抓取工作台 */
    data object AdbLogcat : AppNavigationTarget

    /** 打开工作流运行页（通知栏点入；executionId 为空则只进入工作流目录） */
    data class WorkflowRun(val executionId: String? = null) : AppNavigationTarget

    /** 打开 Agent 设置页（模型/思考呈现/工具中心配置） */
    data object AgentSettings : AppNavigationTarget

    /** 系统分享入口：外部 ACTION_SEND 分享的文本，进入聊天页预填（不自动发送） */
    data class SharedText(val text: String) : AppNavigationTarget

    /** 打开 Agent 会话（通知点入；按 sessionId 定位群聊或独立会话） */
    data class AgentSession(val sessionId: String? = null, val groupId: String? = null) : AppNavigationTarget
}

/**
 * 全局导航总线。
 *
 * 用于在非 UI 逻辑（系统通知、后台服务、快捷方式、Intent 处理）与 Compose Navigation 之间
 * 建立解耦、可靠的导航事件传递通道。
 */
class GlobalNavigationBus() {
    private val _events = MutableSharedFlow<AppNavigationTarget>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppNavigationTarget> = _events.asSharedFlow()

    fun navigateTo(target: AppNavigationTarget) {
        _events.tryEmit(target)
    }

    /** 消费并重置当前重放事件，防止同一目标在旋转屏幕或重组时被重复处理。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearLatest(target: AppNavigationTarget) {
        _events.resetReplayCache()
    }
}

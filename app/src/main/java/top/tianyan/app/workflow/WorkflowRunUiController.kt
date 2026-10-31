package top.tianyan.app.workflow

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.runtime.gui.WorkflowGuiHudBridge
import top.tianyan.app.ui.workflow.hud.WorkflowHudService

/**
 * 工作流 HUD 悬浮窗的生产者：监听 [WorkflowRunManager.activeRuns]，
 * 把最新一次运行的状态翻译到 WorkflowGuiHudBridge（原 WorkflowViewModel.publishHud 职责）。
 * 进程级单例，与页面生命周期无关；页面退出后悬浮窗继续显示后台运行进度。
 */
class WorkflowRunUiController(
    private val runManager: WorkflowRunManager,
    private val hud: WorkflowGuiHudBridge,
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(runManager.latestRunId, runManager.activeRuns) { id, runs -> id?.let { runs[it] } }
                .collectLatest { state ->
                    if (state == null) return@collectLatest
                    val isNewRun = hud.session.value?.executionId != state.executionId
                    if (isNewRun && state.status !in TERMINAL) {
                        hud.start(state.executionId, state.definition.name)
                        hud.bindCancel(state.executionId) { runManager.cancel(state.executionId) }
                        if (Settings.canDrawOverlays(appContext)) {
                            WorkflowHudService.start(appContext)
                        }
                    }
                    publish(state)
                }
        }
    }

    private fun publish(state: WorkflowRuntimeState) {
        when (state.status) {
            WorkflowRunStatus.SUCCESS -> hud.finish(true, "全部节点完成")
            WorkflowRunStatus.FAILED -> hud.finish(false, state.error ?: "工作流失败")
            WorkflowRunStatus.CANCELLED -> {
                if (!hud.isStopRequested(state.executionId)) {
                    hud.cancelled(state.error ?: "已取消")
                }
            }
            else -> {
                val active = state.nodeStates.entries.firstOrNull { (_, node) ->
                    node.status in HUD_ACTIVE_NODE
                }
                val title = active?.let { (id, _) ->
                    state.definition.nodes.firstOrNull { it.id == id }?.title ?: id
                }.orEmpty()
                val message = active?.value?.progressMessage?.takeIf { it.isNotBlank() } ?: "运行中"
                hud.updateNode(title, message)
            }
        }
    }

    private companion object {
        val TERMINAL = setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)
        val HUD_ACTIVE_NODE = setOf(
            NodeRunStatus.RUNNING,
            NodeRunStatus.STREAMING,
            NodeRunStatus.WAITING_APPROVAL,
        )
    }
}

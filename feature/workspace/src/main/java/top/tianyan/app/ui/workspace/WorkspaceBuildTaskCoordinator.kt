package top.tianyan.app.ui.workspace

import android.content.Context
import top.tianyan.app.feature.workspace.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.tools.ToolNotificationNotifier
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.runtime.BackgroundTaskRegistry
import top.tianyan.app.runtime.build.BuildRunProgress
import top.tianyan.app.runtime.build.WorkspaceBuildRunner
import top.tianyan.app.harness.workflow.WorkflowSignal
import top.tianyan.app.harness.workflow.WorkflowSignalBus
import java.io.File
import top.tianyan.app.core.datastore.WorkshopKeystore
import top.tianyan.app.runtime.build.WorkshopBuildType

data class WorkspaceBuildTaskState(
    val project: WorkspaceProject,
    val progress: BuildRunProgress,
)

/** Keeps a workspace build alive while the workspace destination is recreated. */
class WorkspaceBuildTaskCoordinator(
    private val context: Context,
    private val runner: WorkspaceBuildRunner,
    private val notifier: ToolNotificationNotifier,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
    private val workflowSignals: WorkflowSignalBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<WorkspaceBuildTaskState?>(null)
    val state: StateFlow<WorkspaceBuildTaskState?> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(
        project: WorkspaceProject,
        buildType: WorkshopBuildType = WorkshopBuildType.DEBUG,
        keystore: WorkshopKeystore? = null,
    ): Boolean {
        if (job?.isActive == true || _state.value?.progress?.isRunning == true) return false
        val initial = BuildRunProgress(step = context.getString(R.string.workspace_prepare_build))
        _state.value = WorkspaceBuildTaskState(project, initial)
        backgroundTaskRegistry.start(BUILD_TASK_ID)
        notifier.showBuildProgress(project.name, initial.step)
        job = scope.launch {
            try {
                // 终态保护：终态（isRunning=false）一旦到达，后续任何 isRunning=true
                // 的消息都是心跳协程在 cancel 前挤进通道的孤儿，直接丢弃——绝不允许
                // 把已呈现的“运行就绪/编译失败”覆盖回“正在构建”转圈态。
                var finished = false
                runner.runProject(project, buildType, keystore).collect { progress ->
                    if (finished) return@collect
                    if (!progress.isRunning) finished = true
                    _state.value = WorkspaceBuildTaskState(project, progress)
                    if (progress.isRunning) {
                        notifier.showBuildProgress(project.name, progress.step)
                    } else {
                        if (progress.isSuccess == true) {
                            notifier.showBuildSuccess(project.name, progress.apkPath)
                            progress.apkPath?.takeIf(String::isNotBlank)?.let { apkPath ->
                                workflowSignals.emit(WorkflowSignal.ApkGenerated(project.name, project.linuxPath, apkPath))
                            }
                        } else {
                            val error = progress.message ?: context.getString(R.string.workspace_unknown_error)
                            notifier.showBuildFailed(project.name, error)
                            workflowSignals.emit(WorkflowSignal.BuildFailed(project.name, project.linuxPath, error))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failed = BuildRunProgress(
                    step = context.getString(R.string.workspace_build_interrupted),
                    isRunning = false,
                    isSuccess = false,
                    message = error.message ?: context.getString(R.string.workspace_build_exception),
                )
                _state.value = WorkspaceBuildTaskState(project, failed)
                val message = failed.message ?: context.getString(R.string.workspace_unknown_exception)
                notifier.showBuildFailed(project.name, message)
                workflowSignals.emit(WorkflowSignal.BuildFailed(project.name, project.linuxPath, message))
            } finally {
                backgroundTaskRegistry.finish(BUILD_TASK_ID)
                job = null
            }
        }
        return true
    }

    @Synchronized
    fun cancel() {
        val current = _state.value ?: return
        job?.cancel()
        job = null
        val stopped = BuildRunProgress(
            step = context.getString(R.string.workspace_build_stopped),
            isRunning = false,
            isSuccess = false,
            message = context.getString(R.string.workspace_build_stopped_message),
            logOutput = current.progress.logOutput,
        )
        _state.value = current.copy(progress = stopped)
        notifier.showBuildFailed(current.project.name, stopped.message ?: context.getString(R.string.workspace_build_stopped_short))
    }

    fun dismiss() {
        if (_state.value?.progress?.isRunning != true) _state.value = null
    }

    fun launchPackageInstaller(apkPath: String) {
        runner.launchPackageInstaller(File(apkPath))
    }

    private companion object {
        const val BUILD_TASK_ID = "workspace-build"
    }
}

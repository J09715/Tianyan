package top.tianyan.app.ui.iteration

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.feature.custom_iteration.R
import top.tianyan.app.iteration.engine.AssetReader
import top.tianyan.app.iteration.engine.AndroidAssetReader
import top.tianyan.app.iteration.engine.CustomIterationBootstrap
import top.tianyan.app.iteration.engine.CustomIterationPaths
import top.tianyan.app.iteration.engine.TianyanDevBuildCli
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator

class CustomIterationViewModel(
    application: Application,
    private val buildCoordinator: TianyanDevBuildCoordinator,
    /**
     * 运行时路径解析器，在**点击时刻**求值。
     *
     * 迭代工作区宿主路径必须是 PRoot 绑定到 guest `/root` 的 `homeDir`：
     * 写死 `filesDir/runtime/rootfs/root` 会得到一个从未被挂载的目录，
     * UI 却报告「工作区就绪」——这是必须杜绝的假象。
     * RootFS 安装/发行版切换会改变这些路径，故不能在构造期快照。
     */
    private val resolvePaths: () -> CustomIterationPaths,
    /** assets 读取源；默认读 APK assets，测试可注入内存实现。 */
    private val assets: AssetReader = AndroidAssetReader(application),
    /**
     * 点击时刻读取迭代工作区的 `origin` URL。
     *
     * 与 [TianyanDevBuildCoordinator] 相同：在 guest `~/custom_tianyan` 执行
     * `git remote get-url origin`。尚未克隆或命令失败时返回 null，Prompt 改用占位符，
     * 不回退到官方仓库。
     */
    private val readOriginRemoteUrl: suspend () -> String? = { null },
) : AndroidViewModel(application) {

    data class UiState(
        val isBusy: Boolean = false,
        val workspaceReady: Boolean = false,
        val workspacePath: String = "",
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 云端构建状态机：Idle → Dispatching → Running → Downloading → Success/Failed。 */
    private val _buildState = MutableStateFlow<TianyanDevBuildCoordinator.BuildStatus>(
        TianyanDevBuildCoordinator.BuildStatus.Idle
    )
    val buildState: StateFlow<TianyanDevBuildCoordinator.BuildStatus> = _buildState.asStateFlow()

    fun startCustomIteration(onSessionReady: (prompt: String) -> Unit) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null)

        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                // 宿主 homeDir ←PRoot bind→ guest /root。Skill 只写 attachments/workspace 扫描根。
                // Fork 与 Coordinator 一样从 origin 解析；解析不到时 Prompt 要求 Agent 先读 git remote。
                val originUrl = runCatching { readOriginRemoteUrl() }.getOrNull()
                val forkRepo = originUrl?.let(TianyanDevBuildCli::parseGitHubSlug)
                val result = CustomIterationBootstrap.bootstrap(
                    assets = assets,
                    paths = resolvePaths(),
                    forkRepo = forkRepo,
                )

                if (result.success) {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        workspaceReady = true,
                        workspacePath = result.workspacePath
                    )
                    onSessionReady(result.prompt)
                } else {
                    // 引导失败的原始技术细节只进日志，UI 展示用户可理解的友好文案
                    Log.w(TAG, "bootstrap failed: ${result.errorMessage}")
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        errorMessage = app.getString(R.string.iteration_error_bootstrap)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap custom iteration crashed", e)
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    errorMessage = app.getString(R.string.iteration_error_bootstrap)
                )
            }
        }
    }

    /**
     * 触发 GitHub Actions 云端构建 TianyanDev APK，并全程跟踪状态直至终态。
     * 流水线进行中（Dispatching/Running/Downloading）时忽略重复触发。
     */
    fun startCloudBuild(branch: String) {
        if (isBuildPipelineActive()) return
        val normalizedBranch = branch.trim().ifBlank { DEFAULT_BRANCH }
        viewModelScope.launch {
            val status = buildCoordinator.runPipeline(
                branch = normalizedBranch,
            ) { updated ->
                _buildState.value = updated
            }
            _buildState.value = status
        }
    }

    /** 用户关闭构建失败提示，回到 Idle 可再次触发。 */
    fun dismissBuildStatus() {
        val current = _buildState.value
        if (current is TianyanDevBuildCoordinator.BuildStatus.Failed ||
            current is TianyanDevBuildCoordinator.BuildStatus.Success
        ) {
            _buildState.value = TianyanDevBuildCoordinator.BuildStatus.Idle
        }
    }

    private fun isBuildPipelineActive(): Boolean = when (_buildState.value) {
        is TianyanDevBuildCoordinator.BuildStatus.Dispatching,
        is TianyanDevBuildCoordinator.BuildStatus.Running,
        is TianyanDevBuildCoordinator.BuildStatus.Downloading,
        -> true
        else -> false
    }

    /** 用户关闭错误提示。 */
    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private companion object {
        const val TAG = "CustomIteration"
        const val DEFAULT_BRANCH = "main"
    }
}

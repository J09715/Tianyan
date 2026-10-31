package top.tianyan.app.harness

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.harness.mcp.McpWorkspaceRecommender
import top.tianyan.app.runtime.RuntimePathManager

/** 前台工作区的 MCP 推荐投影；切换工作区时撤销旧扫描。 */
class HarnessWorkspaceRecommendations(
    private val recommender: McpWorkspaceRecommender,
    private val servers: McpServerRepository,
    private val paths: RuntimePathManager,
    private val logger: AppLogger,
) {
    private val mutableRecommendations = MutableStateFlow<List<McpWorkspaceRecommender.Recommendation>>(emptyList())
    val recommendations = mutableRecommendations.asStateFlow()
    private var scanJob: Job? = null

    suspend fun enable(presetId: String) {
        if (top.tianyan.app.core.model.BuiltinMcpPresets.find(presetId)?.requiresInstall == true) return
        try {
            servers.setEnabled(presetId, true)
            dismiss(presetId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.e("启用推荐 MCP 失败：$presetId", error)
        }
    }

    fun dismiss(presetId: String) {
        mutableRecommendations.update { current -> current.filterNot { it.presetId == presetId } }
    }

    @Synchronized
    fun refresh(scope: CoroutineScope, workspacePath: String) {
        scanJob?.cancel()
        mutableRecommendations.value = emptyList()
        scanJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val directory = resolveDirectory(paths.workspaceDir, workspacePath)
                    val candidates = directory?.let { recommender.recommend(it) }.orEmpty()
                    val enabled = servers.servers.first().filter { it.isEnabled }.map { it.id }.toSet()
                    candidates.filter { it.presetId !in enabled }
                }
                // Publish under the same lock as refresh so a superseded scan cannot win.
                synchronized(this@HarnessWorkspaceRecommendations) {
                    if (coroutineContext[Job]?.isActive == true) mutableRecommendations.value = result
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.e("扫描工作区 MCP 推荐失败", error)
            }
        }
    }

    companion object {
        internal fun resolveDirectory(root: File, workspacePath: String): File? {
            val path = workspacePath.trim()
            if (path != "/workspace" && !path.startsWith("/workspace/")) return null
            val canonicalRoot = root.canonicalFile
            val directory = File(canonicalRoot, path.removePrefix("/workspace").trimStart('/')).canonicalFile
            if (!directory.toPath().startsWith(canonicalRoot.toPath())) return null
            return directory.takeIf { it.isDirectory }
        }
    }
}

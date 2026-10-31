package top.tianyan.app.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.runtime.remote.RemoteSystemService

data class SessionWorkspaceCatalogState(val projects: List<WorkspaceProject> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val server: RemoteServer? = null, val selectionId: String? = null)

/** The new-session picker uses the same selected profile and SFTP root as the remote workshop. */
class SessionWorkspaceCatalog(private val servers: RemoteServerRepository,
    private val localProjects: () -> Flow<List<WorkspaceProject>>,
    private val remoteProjects: suspend (RemoteServer) -> List<top.tianyan.app.runtime.environment.ExecutionFileEntry>) {
    constructor(servers: RemoteServerRepository, remote: RemoteSystemService, local: WorkspaceManager) :
        this(servers, local::observeProjects, { target -> remote.projects(target.id, target) })
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<SessionWorkspaceCatalogState> = combine(servers.activeId, servers.servers) { id, profiles ->
        id to profiles.firstOrNull { it.id == id }
    }.distinctUntilChanged().flatMapLatest { (id, profile) ->
        if (id == null) localProjects().map { SessionWorkspaceCatalogState(it, loading = false) }
        else flow {
            emit(SessionWorkspaceCatalogState(server = profile, selectionId = id))
            while (true) {
                try {
                    checkNotNull(profile) { "连接档案不可用" }
                    val projects = remoteProjects(profile).map { entry ->
                        WorkspaceProject(entry.name, "${profile.workspace.trimEnd('/')}/${entry.name}", "/workspace/${entry.name}", 0, ownsDirectory = false)
                    }
                    emit(SessionWorkspaceCatalogState(projects, false, server = profile, selectionId = id))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    emit(SessionWorkspaceCatalogState(loading = false, error = "无法读取服务器项目，请检查连接与工作目录；不会使用手机项目", server = profile, selectionId = id))
                }
                delay(15_000)
            }
        }
    }
    fun validate(state: SessionWorkspaceCatalogState, workspace: String) {
        check(state.selectionId == servers.activeId.value && state.server == servers.servers.value.firstOrNull { it.id == state.selectionId }) {
            "执行环境已改变，请刷新项目后重新创建会话"
        }
        if (workspace.isNotBlank()) check(!state.loading && state.error == null) { state.error ?: "项目列表加载中，请稍后重试" }
        require(workspace.isBlank() || state.projects.any { it.linuxPath == workspace }) { "所选项目不属于当前执行环境" }
    }
}

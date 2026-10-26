package top.tianyan.app.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.runtime.WorkspaceManager

/** 正在执行的远程/分支操作 */
enum class GitOperation {
    PUSH, PULL, CHECKOUT, CREATE_BRANCH, DELETE_BRANCH, REFRESH, UNSHALLOW,
    STAGE, COMMIT, AI_MESSAGE, DISCARD, TAG, DETAIL,
}

data class GitUiState(
    val projectName: String = "",
    val projectPath: String = "",
    val projectLinuxPath: String = "",
    val initialized: Boolean = false,
    val isRepository: Boolean = false,
    val isShallow: Boolean = false,
    val loading: Boolean = false,
    val commitsLoading: Boolean = false,
    val overview: GitOverview? = null,
    val commits: List<GitCommitRow> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val operation: GitOperation? = null,
    val progress: GitProgress? = null,
    val credentialHosts: List<String> = emptyList(),
    val changes: List<GitManager.GitFileChange> = emptyList(),
    val tags: List<GitManager.GitTagInfo> = emptyList(),
    /** 已加载的提交详情（hash → 完整信息+diff），点击提交行时懒加载 */
    val commitDetails: Map<String, String> = emptyMap(),
    /** 提交信息草稿：放 VM 而非组合状态，页面退出组合（返回聊天）后 AI 生成结果不丢 */
    val commitMessageDraft: String = "",
) {
    val busy: Boolean get() = operation != null
}

class GitViewModel(
    private val workspaceManager: WorkspaceManager,
    private val gitManager: GitManager,
    private val credentialsStore: GitCredentialsStore,
    private val providerClient: ProviderClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GitUiState())
    val uiState: StateFlow<GitUiState> = _uiState.asStateFlow()

    private var lastProjectName: String? = null

    /** 由 GitScreen 在进入时绑定项目；重复绑定同一项目不重复初始化 */
    fun bind(projectName: String) {
        if (lastProjectName == projectName && _uiState.value.initialized) return
        lastProjectName = projectName
        viewModelScope.launch {
            _uiState.update { it.copy(projectName = projectName, initialized = true, loading = true) }
            val project = runCatching {
                workspaceManager.listProjects().firstOrNull { it.name == projectName }
            }.getOrNull()
            if (project == null) {
                _uiState.update { it.copy(loading = false, error = "未找到工作区项目：$projectName") }
                return@launch
            }
            val isRepo = gitManager.isRepository(project.path)
            _uiState.update {
                it.copy(
                    projectPath = project.path,
                    projectLinuxPath = project.linuxPath,
                    isRepository = isRepo,
                    loading = false,
                )
            }
            if (isRepo) refreshInternal(showLoading = false)
        }
    }

    fun refresh() {
        if (_uiState.value.isRepository) refreshInternal(showLoading = true)
    }

    /**
     * 轻量刷新（页面 onResume 用）：只刷概览/改动/标签，跳过最重的提交树 RevWalk 遍历。
     * agent 改文件影响的是改动列表，提交树只有 commit 后才变。
     */
    fun refreshLight() {
        val state = _uiState.value
        if (!state.initialized || !state.isRepository || state.busy) return
        val path = state.projectPath
        viewModelScope.launch {
            val overview = runCatching { gitManager.loadOverview(path) }
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            val tags = runCatching { gitManager.listTags(path) }.getOrDefault(emptyList())
            _uiState.update { s ->
                s.copy(
                    overview = overview.getOrNull() ?: s.overview,
                    changes = changes,
                    tags = tags,
                    isShallow = gitManager.isShallow(path),
                )
            }
        }
    }

    private fun refreshInternal(showLoading: Boolean) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.REFRESH, loading = showLoading, commitsLoading = true, error = null) }
            val overview = runCatching { gitManager.loadOverview(path) }
            val commits = runCatching { gitManager.loadCommitGraph(path) }
            val hosts = runCatching { credentialsStore.listHosts() }.getOrDefault(emptyList())
            val shallow = gitManager.isShallow(path)
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            val tags = runCatching { gitManager.listTags(path) }.getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    operation = null,
                    loading = false,
                    commitsLoading = false,
                    overview = overview.getOrNull(),
                    commits = commits.getOrNull().orEmpty(),
                    error = (overview.exceptionOrNull() ?: commits.exceptionOrNull())?.let { GitManager.friendlyError(it) },
                    credentialHosts = hosts,
                    isShallow = shallow,
                    changes = changes,
                    tags = tags,
                )
            }
        }
    }

    fun checkout(branch: GitBranchInfo) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.CHECKOUT, error = null) }
            val result = gitManager.checkout(path, branch)
            handleOpResult(result)
        }
    }

    fun createBranch(name: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy || name.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.CREATE_BRANCH, error = null) }
            handleOpResult(gitManager.createBranch(path, name.trim(), fromCurrent = true))
        }
    }

    fun deleteBranch(branch: GitBranchInfo) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.DELETE_BRANCH, error = null) }
            handleOpResult(gitManager.deleteBranch(path, branch))
        }
    }

    fun push() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.PUSH, progress = null, error = null) }
            val result = gitManager.push(path) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            handleOpResult(result)
        }
    }

    fun pull() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.PULL, progress = null, error = null) }
            val result = gitManager.pull(path) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            handleOpResult(result)
        }
    }

    /** 浅克隆补全历史：git fetch --unshallow（沙箱 CLI git） */
    fun unshallow() {
        val state = _uiState.value
        if (state.projectLinuxPath.isBlank() || state.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.UNSHALLOW, progress = null, error = null) }
            val result = gitManager.unshallow(state.projectLinuxPath) { line ->
                // git --progress 输出行如 "Receiving objects: 45% (123/274)"，整行透传为进度标题
                _uiState.update { it.copy(progress = GitProgress(line.take(60), 0, 0)) }
            }
            handleOpResult(result)
        }
    }

    // ------------------------------------------------------------------
    // 提交流程：暂存 → （可选 AI 拟写 message）→ commit
    // ------------------------------------------------------------------

    fun stageFile(change: GitManager.GitFileChange) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.STAGE) }
            val result = gitManager.stageFile(path, change.path, stage = !change.staged)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            // 只刷新改动列表，不动提交树
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, changes = changes) }
        }
    }

    fun stageAll() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.STAGE) }
            val result = gitManager.stageAll(path)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, changes = changes) }
        }
    }

    fun commit(message: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy || message.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.COMMIT) }
            handleOpResult(gitManager.commit(path, message.trim()))
        }
    }

    /** 提交后自动推送当前分支 */
    fun commitAndPush(message: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy || message.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.COMMIT) }
            val commitResult = gitManager.commit(path, message.trim())
            if (commitResult is GitOpResult.Failed) {
                handleOpResult(commitResult)
                return@launch
            }
            _uiState.update { it.copy(operation = GitOperation.PUSH, progress = null) }
            val pushResult = gitManager.push(path) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            val commitSummary = (commitResult as GitOpResult.Ok).message
            handleOpResult(
                when (pushResult) {
                    is GitOpResult.Ok -> GitOpResult.Ok("$commitSummary，已推送")
                    is GitOpResult.Failed -> GitOpResult.Failed("$commitSummary，但推送失败：${pushResult.message}")
                },
            )
        }
    }

    /** 推送预览：返回待推送提交列表给确认对话框 */
    fun loadPushPreview(onReady: (List<Pair<String, String>>) -> Unit) {
        val path = _uiState.value.projectPath
        if (path.isBlank()) return
        viewModelScope.launch {
            onReady(gitManager.pushPreview(path))
        }
    }

    /** 懒加载提交详情（hash → 完整信息 + diff） */
    fun loadCommitDetail(hash: String) {
        val state = _uiState.value
        if (state.projectPath.isBlank() || state.commitDetails.containsKey(hash)) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.DETAIL) }
            val detail = gitManager.getCommitDetail(state.projectPath, hash)
            _uiState.update { s ->
                s.copy(operation = null, commitDetails = s.commitDetails + (hash to detail))
            }
        }
    }

    fun unstageAll() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.STAGE) }
            val result = gitManager.unstageAll(path)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, changes = changes) }
        }
    }

    fun discardFile(change: GitManager.GitFileChange) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.DISCARD) }
            val result = gitManager.discardFile(path, change.path)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            val notice = (result as GitOpResult.Ok).message
            val changes = runCatching { gitManager.listChanges(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, changes = changes, notice = notice) }
        }
    }

    fun createTag(name: String, message: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy || name.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.TAG) }
            val result = gitManager.createTag(path, name.trim(), message)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            val notice = (result as GitOpResult.Ok).message
            val tags = runCatching { gitManager.listTags(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, tags = tags, notice = notice) }
        }
    }

    fun deleteTag(name: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.TAG) }
            val result = gitManager.deleteTag(path, name)
            if (result is GitOpResult.Failed) {
                _uiState.update { it.copy(operation = null, error = result.message) }
                return@launch
            }
            val notice = (result as GitOpResult.Ok).message
            val tags = runCatching { gitManager.listTags(path) }.getOrDefault(emptyList())
            _uiState.update { it.copy(operation = null, tags = tags, notice = notice) }
        }
    }

    /** 借助当前激活模型，基于改动 diff 生成 commit message（单次非会话调用）。
     *  结果写入 uiState.commitMessageDraft：即使页面已退出组合（用户返回聊天页），
     *  生成结果也不丢失，回到改动页签时输入框仍在。 */
    fun generateCommitMessage() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.AI_MESSAGE, error = null) }
            runCatching {
                val summary = gitManager.buildDiffSummary(path)
                val model = providerClient.resolveModel()
                val result = providerClient.chat(
                    model,
                    listOf(
                        ApiMessage(
                            role = "user",
                            content = """
                                你是 Git 提交信息撰写助手。根据以下改动生成一条 commit message。
                                要求：第一行是 50 字符内的中文祈使句摘要（不加前缀）；若改动较复杂，空一行后附 2-4 条要点列表。只输出提交信息本身，不要任何解释、代码块标记或引号。

                                $summary
                            """.trimIndent(),
                        ),
                    ),
                )
                val text = result.content?.trim().orEmpty()
                check(text.isNotBlank()) { "模型返回了空内容" }
                text
            }.onSuccess { message ->
                _uiState.update { it.copy(operation = null, commitMessageDraft = message) }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(operation = null, error = "AI 生成失败：${failure.message ?: "请检查模型配置"}")
                }
            }
        }
    }

    fun updateCommitMessageDraft(text: String) {
        _uiState.update { it.copy(commitMessageDraft = text.take(2000)) }
    }

    fun clearCommitMessageDraft() {
        _uiState.update { it.copy(commitMessageDraft = "") }
    }

    fun saveCredentials(host: String, username: String, token: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { credentialsStore.save(host.trim().lowercase(), username, token) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            notice = "已保存 $host 的凭据",
                            credentialHosts = (state.credentialHosts + host.trim().lowercase()).distinct().sorted(),
                        )
                    }
                    onDone()
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = "凭据保存失败：${failure.message}") }
                }
        }
    }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun handleOpResult(result: GitOpResult) {
        when (result) {
            is GitOpResult.Ok -> _uiState.update { it.copy(operation = null, progress = null, notice = result.message, commitMessageDraft = "") }
            is GitOpResult.Failed -> _uiState.update { it.copy(operation = null, progress = null, error = result.message) }
        }
        refreshInternal(showLoading = false)
    }
}

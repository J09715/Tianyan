package top.tianyan.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.harness.redteam.RedTeamCoordinator
import top.tianyan.app.core.model.RedTeamPreflightReport
import top.tianyan.app.harness.redteam.RedTeamSkillStore

/**
 * 红队控制台（11 个页签）的 ViewModel 侧状态与操作。
 *
 * 为什么单独成文件：ChatViewModel 已到行数棘轮上限，而控制台状态与主对话装配无耦合 ——
 * 它只依赖「当前会话 id + 红队协调器」。
 *
 * 设计要点：
 * - **每次切页签只加载该页签需要的数据**，不做全量刷新。11 个页签全量拉一遍会打爆事实库。
 * - 搜索框是「回车才搜」：边打边搜会把每次按键变成一次全量过滤。
 * - 所有失败都写进 `error` 而不是抛异常：控制台是只读观察界面，崩掉没有意义。
 */

/** 红队控制台的运行时宿主能力（由 ChatViewModel 提供）。 */
internal interface RedTeamConsoleHost {
    val redTeamConsoleSessionId: String
    val redTeamCoordinatorRef: RedTeamCoordinator?
    val redTeamConsoleScope: kotlinx.coroutines.CoroutineScope
}

internal class RedTeamConsoleController(
    private val host: RedTeamConsoleHost,
) {
    private val _state = MutableStateFlow(RedTeamConsoleState())
    val state: StateFlow<RedTeamConsoleState> = _state.asStateFlow()

    private val _skillHealth = MutableStateFlow<RedTeamPreflightReport?>(null)
    val skillHealth: StateFlow<RedTeamPreflightReport?> = _skillHealth.asStateFlow()

    private fun launch(block: suspend (RedTeamCoordinator, String) -> Unit) {
        val coordinator = host.redTeamCoordinatorRef ?: run {
            _state.update { it.copy(error = "红队协调器未装配，控制台不可用。") }
            return
        }
        val sessionId = host.redTeamConsoleSessionId.takeIf { it.isNotBlank() } ?: return
        host.redTeamConsoleScope.launch {
            try {
                block(coordinator, sessionId)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.update { it.copy(loading = false, error = failure.message ?: "红队控制台操作失败") }
            }
        }
    }

    /** 首次进入或手动刷新：概览页签所需的全量快照。 */
    fun refresh() = launch { coordinator, sessionId ->
        // 清分区行缓存：刷新意味着「数据可能变了」，带着旧缓存会把新写入的事实藏起来。
        _state.update { it.copy(loading = true, error = null, sectionRows = emptyMap()) }
        val snapshot = withContext(Dispatchers.IO) { coordinator.consoleSnapshot(sessionId) }
        _state.update {
            it.copy(
                loading = false,
                engagements = snapshot.engagements,
                currentEngagement = snapshot.current,
                stats = snapshot.stats,
                segments = snapshot.segments,
                assets = snapshot.assets,
                total = snapshot.assets.size,
            )
        }
        loadTab(_state.value.tab, coordinator, sessionId)
    }

    fun selectTab(tab: RedTeamConsoleTab) {
        _state.update { it.copy(tab = tab) }
        launch { coordinator, sessionId -> loadTab(tab, coordinator, sessionId) }
    }

    /** 按页签懒加载：只有当前页签需要的接口会被调用。 */
    private suspend fun loadTab(tab: RedTeamConsoleTab, coordinator: RedTeamCoordinator, sessionId: String) {
        when (tab) {
            RedTeamConsoleTab.OVERVIEW, RedTeamConsoleTab.ASSETS -> Unit // 快照已覆盖
            RedTeamConsoleTab.SESSIONS, RedTeamConsoleTab.VULNS, RedTeamConsoleTab.CHAIN,
            RedTeamConsoleTab.SCORES, RedTeamConsoleTab.TARGETS, RedTeamConsoleTab.REPORT,
            RedTeamConsoleTab.KNOWLEDGE, RedTeamConsoleTab.ARTIFACTS -> {
                val sections = withContext(Dispatchers.IO) { coordinator.consoleSections(sessionId) }
                _state.update { it.copy(sections = sections) }
                loadSectionRows(tab, coordinator, sessionId)
            }
            RedTeamConsoleTab.PROMPTS -> {
                val roles = withContext(Dispatchers.IO) { coordinator.consoleRoles(sessionId) }
                _state.update { current ->
                    val keep = current.roleId?.takeIf { id -> roles.any { it.id == id } }
                    val selected = keep ?: roles.firstOrNull()?.id
                    current.copy(
                        roles = roles,
                        roleId = selected,
                        roleDraft = roles.firstOrNull { it.id == selected }?.content.orEmpty(),
                    )
                }
            }
            RedTeamConsoleTab.SKILLS -> {
                val skills = withContext(Dispatchers.IO) { coordinator.consoleSkills() }
                _state.update { it.copy(skills = skills) }
            }
        }
        val agents = withContext(Dispatchers.IO) { coordinator.consoleAgentsStatus(sessionId) }
        _state.update { it.copy(agents = agents) }
    }

    /**
     * 加载分区行数据。
     *
     * 分区页签只显示当前分区的行，不预载全部 9 个分区 —— 一次拉全量在事实多的时候
     * 会把首屏拖到几秒。`sectionRows` 按分区 id 缓存，来回切页签不重复拉。
     */
    private suspend fun loadSectionRows(
        tab: RedTeamConsoleTab,
        coordinator: RedTeamCoordinator,
        sessionId: String,
    ) {
        val section = sectionOf(tab) ?: return
        if (_state.value.sectionRows.containsKey(section.id)) return
        val rows = withContext(Dispatchers.IO) { coordinator.consoleSectionRows(sessionId, section) }
        _state.update { it.copy(sectionRows = it.sectionRows + (section.id to rows)) }
    }

    /** 页签 → 分区枚举；概览/资产/提示词/技能不是分区页签，返回 null。 */
    private fun sectionOf(tab: RedTeamConsoleTab): RedTeamConsoleModel.Section? = when (tab) {
        RedTeamConsoleTab.SESSIONS -> RedTeamConsoleModel.Section.SESSIONS
        RedTeamConsoleTab.VULNS -> RedTeamConsoleModel.Section.VULNS
        RedTeamConsoleTab.CHAIN -> RedTeamConsoleModel.Section.CHAIN
        RedTeamConsoleTab.SCORES -> RedTeamConsoleModel.Section.SCORES
        RedTeamConsoleTab.TARGETS -> RedTeamConsoleModel.Section.TARGETS
        RedTeamConsoleTab.REPORT -> RedTeamConsoleModel.Section.REPORT
        RedTeamConsoleTab.KNOWLEDGE -> RedTeamConsoleModel.Section.KNOWLEDGE
        RedTeamConsoleTab.ARTIFACTS -> RedTeamConsoleModel.Section.ATTACK_FILES
        else -> null
    }

    /**
     * 绑定目标后重新加载。
     *
     * 绑定会写入一条 engagement 事实，「目标」页签正是从它渲染的。
     * 不清缓存的话，用户绑完切到目标页仍看到「0 条」—— 数据其实已经在库里了。
     */
    fun onTargetBound() {
        _state.update { it.copy(sectionRows = emptyMap()) }
        refresh()
    }

    fun refreshSkillHealth() = launch { coordinator, sessionId ->
        val report = withContext(Dispatchers.IO) { coordinator.skillHealth(sessionId) }
        _skillHealth.value = report
    }

    // ---------- 资产测绘 ----------

    fun selectSegment(segment: String?) {
        _state.update { it.copy(selectedSegment = segment) }
        reloadAssets()
    }

    fun updateQuery(query: String) = _state.update { it.copy(queryDraft = query) }
    fun updateService(service: String) = _state.update { it.copy(serviceDraft = service) }
    fun updatePort(port: String) = _state.update { it.copy(portDraft = port) }

    /** 回车提交搜索：把三个草稿合并进 filter 后重查。 */
    fun submitQuery() {
        _state.update { current ->
            current.copy(
                filter = current.filter.copy(
                    q = current.queryDraft.trim(),
                    service = current.serviceDraft.trim(),
                    port = current.portDraft.trim().toIntOrNull(),
                ),
            )
        }
        reloadAssets()
    }

    fun setProvenance(provenance: String?) {
        _state.update { it.copy(filter = it.filter.copy(provenance = provenance)) }
        reloadAssets()
    }

    fun setSort(sort: RedTeamConsoleModel.Sort) {
        _state.update { it.copy(filter = it.filter.copy(sort = sort)) }
        reloadAssets()
    }

    fun setView(view: RedTeamConsoleView) {
        _state.update { it.copy(view = view) }
        // 切到图谱视图才拉图谱数据：力导向布局要算全量节点，
        // 在列表视图里预先算一遍是白烧 CPU。
        if (view == RedTeamConsoleView.GRAPH && _state.value.graph == null) loadGraph()
    }

    fun toggleAsset(assetId: String) {
        if (_state.value.expandedAsset == assetId) {
            _state.update { it.copy(expandedAsset = null, detail = null) }
            return
        }
        _state.update { it.copy(expandedAsset = assetId) }
        launch { coordinator, sessionId ->
            val detail = withContext(Dispatchers.IO) { coordinator.consoleAssetDetail(sessionId, assetId) }
            _state.update { it.copy(detail = detail) }
        }
    }

    private fun reloadAssets() = launch { coordinator, sessionId ->
        _state.update { it.copy(loading = true) }
        val filter = _state.value.filter.copy(cidr = _state.value.selectedSegment)
        val (total, assets) = withContext(Dispatchers.IO) { coordinator.consoleAssets(sessionId, filter) }
        _state.update { it.copy(loading = false, total = total, assets = assets) }
    }

    fun loadGraph() = launch { coordinator, sessionId ->
        _state.update { it.copy(graphLoading = true) }
        val graph = withContext(Dispatchers.IO) {
            coordinator.consoleGraph(sessionId, _state.value.selectedSegment)
        }
        _state.update { it.copy(graphLoading = false, graph = graph) }
    }

    // ---------- 角色提示词 ----------

    fun selectRole(roleId: String) {
        _state.update { current ->
            val role = current.roles.firstOrNull { it.id == roleId }
            current.copy(roleId = roleId, roleDraft = role?.content.orEmpty(), roleMessage = null)
        }
    }

    fun updateRoleDraft(content: String) = _state.update { it.copy(roleDraft = content) }

    fun saveRole() = launch { coordinator, sessionId ->
        val roleId = _state.value.roleId ?: return@launch
        _state.update { it.copy(roleSaving = true, roleMessage = null) }
        val ok = withContext(Dispatchers.IO) {
            coordinator.consoleSaveRolePrompt(sessionId, roleId, _state.value.roleDraft)
        }
        _state.update {
            it.copy(roleSaving = false, roleMessage = if (ok) "已保存" else "保存失败")
        }
        val roles = withContext(Dispatchers.IO) { coordinator.consoleRoles(sessionId) }
        _state.update { it.copy(roles = roles) }
    }

    fun resetRole() {
        _state.update { current ->
            val role = current.roles.firstOrNull { it.id == current.roleId }
            current.copy(roleDraft = role?.content.orEmpty(), roleMessage = null)
        }
    }

    // ---------- 技能库 ----------

    fun selectSkill(skillId: String) = _state.update { current ->
        current.copy(skillDraft = current.skills.firstOrNull { it.id == skillId }, skillMessage = null)
    }

    fun newSkill() = _state.update {
        it.copy(
            skillDraft = RedTeamSkillStore.Skill(
                id = "", name = "", role = null, enabled = true, description = "",
                whenToUse = null, body = "", path = null, builtin = false,
            ),
            skillMessage = null,
        )
    }

    fun updateSkillDraft(transform: (RedTeamSkillStore.Skill) -> RedTeamSkillStore.Skill) =
        _state.update { current -> current.skillDraft?.let { current.copy(skillDraft = transform(it)) } ?: current }

    fun saveSkill() = launch { coordinator, _ ->
        val draft = _state.value.skillDraft ?: return@launch
        _state.update { it.copy(skillSaving = true, skillMessage = null) }
        val saved = runCatching { withContext(Dispatchers.IO) { coordinator.consoleSaveSkill(draft) } }
        _state.update {
            it.copy(
                skillSaving = false,
                skillMessage = saved.fold({ "已保存" }, { e -> "保存失败：${e.message}" }),
                skillDraft = saved.getOrNull() ?: draft,
            )
        }
        val skills = withContext(Dispatchers.IO) { coordinator.consoleSkills() }
        _state.update { it.copy(skills = skills) }
    }

    fun deleteSkill() = launch { coordinator, _ ->
        val draft = _state.value.skillDraft ?: return@launch
        if (draft.builtin) return@launch
        _state.update { it.copy(skillSaving = true, skillMessage = null) }
        val ok = withContext(Dispatchers.IO) { coordinator.consoleDeleteSkill(draft.id) }
        _state.update {
            it.copy(skillSaving = false, skillDraft = if (ok) null else it.skillDraft, skillMessage = if (ok) "已删除" else "删除失败")
        }
        val skills = withContext(Dispatchers.IO) { coordinator.consoleSkills() }
        _state.update { it.copy(skills = skills) }
    }

    // ---------- 智能体并发 ----------

    fun setAgentsMax(value: Int) = launch { coordinator, _ ->
        val applied = withContext(Dispatchers.IO) { coordinator.consoleSetAgentsMax(value) }
        _state.update { it.copy(agentsMessage = "并发上限已设为 $applied") }
        val sessionId = host.redTeamConsoleSessionId
        val agents = withContext(Dispatchers.IO) { coordinator.consoleAgentsStatus(sessionId) }
        _state.update { it.copy(agents = agents) }
    }
}

/**
 * 为 ChatViewModel 构造控制台控制器。
 *
 * 单独成函数而不是内联在 ViewModel 里：ChatViewModel 已到行数棘轮上限，
 * 而这段是纯装配，没有任何业务逻辑。
 *
 * 用 lazy 在 ViewModel 侧延迟构造：控制台只有用户点开红队入口才会用到，
 * 没必要在每个会话的 ViewModel 初始化时就建一份。
 */
internal fun ChatViewModel.buildRedTeamConsoleController(): RedTeamConsoleController =
    RedTeamConsoleController(
        object : RedTeamConsoleHost {
            override val redTeamConsoleSessionId: String get() = currentSessionId.value
            override val redTeamCoordinatorRef: RedTeamCoordinator? get() = this@buildRedTeamConsoleController.redTeamCoordinatorRef
            override val redTeamConsoleScope: kotlinx.coroutines.CoroutineScope get() = viewModelScope
        },
    )

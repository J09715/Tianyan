package top.tianyan.app.ui.workflow

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.WorkflowExecutionLogEntity
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.model.workflow.BuiltinWorkflows
import top.tianyan.app.core.model.workflow.FailurePolicy
import top.tianyan.app.core.model.workflow.WorkflowApprovalRequest
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport
import top.tianyan.app.core.model.workflow.WorkflowEdge
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.core.model.workflow.WorkflowTrigger
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.harness.workflow.WorkflowScheduleRepository
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.gui.WorkflowGuiHudBridge
import top.tianyan.app.ui.workflow.hud.WorkflowHudService
import java.io.File
import top.tianyan.app.core.model.workflow.WorkflowLayout

class WorkflowViewModel(
    private val repository: WorkflowRepository,
    private val runManager: WorkflowRunManager,
    private val scheduleRepository: WorkflowScheduleRepository,
    private val hud: WorkflowGuiHudBridge,
    private val appContext: Context,
    aiModelRepository: AiModelRepository,
    private val pathManager: RuntimePathManager,
    private val json: Json,
) : ViewModel() {
    val definitions: StateFlow<List<WorkflowDefinition>> = repository.observeDefinitions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuiltinWorkflows.all)
    val history: StateFlow<List<WorkflowHistoryEntry>> = repository.observeRecentExecutions()
        .map { logs -> logs.mapNotNull(::decodeHistory) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models = aiModelRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 定时计划列表（含禁用项），按启用与下次触发时间排序。 */
    val schedules: StateFlow<List<WorkflowScheduleEntity>> = scheduleRepository.observeSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveSchedule(entity: WorkflowScheduleEntity) {
        viewModelScope.launch { runCatching { scheduleRepository.upsert(entity) } }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch { runCatching { scheduleRepository.delete(id) } }
    }

    fun toggleSchedule(id: String, enabled: Boolean) {
        viewModelScope.launch { runCatching { scheduleRepository.setEnabled(id, enabled) } }
    }
    private val _error = MutableStateFlow<String?>(null)
    val error = combine(_error, runManager.historyErrors, ::workflowHistoryError)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun clearError() { _error.value = null }
    /** 进程级注册表中的全部活跃/近期运行（含后台运行），供「后台运行中」指示与切换。 */
    val allRuns: StateFlow<Map<String, WorkflowRuntimeState>> = runManager.activeRuns
    val running: StateFlow<Boolean> = runManager.running

    /** 仍在推进（非终态）的运行，最新在前：目录页横幅与一键切换。 */
    val backgroundActiveRuns: StateFlow<List<WorkflowRuntimeState>> = runManager.activeRuns
        .map { runs ->
            runs.values
                .filter { it.status !in WorkflowRunManager.TERMINAL }
                .sortedByDescending { it.startedAt ?: 0L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _viewingRunId = MutableStateFlow<String?>(null)
    val viewingRunId = _viewingRunId.asStateFlow()

    /** 历史回看的快照：该执行已不在进程注册表时，activeState 退回此值。 */
    private val _historySnapshot = MutableStateFlow<WorkflowRuntimeState?>(null)

    /** 当前页面展示的运行状态（活跃运行或历史回看）。 */
    val activeState: StateFlow<WorkflowRuntimeState?> = combine(
        runManager.activeRuns,
        _viewingRunId,
        _historySnapshot,
    ) { runs, id, snapshot ->
        when {
            id == null -> null
            runs[id] != null -> runs[id]
            snapshot?.executionId == id -> snapshot
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val approvalRequest: StateFlow<WorkflowApprovalRequest?> = combine(_viewingRunId, runManager.activeRuns) { id, runs ->
        id to runs[id]?.resumeCount
    }.distinctUntilChanged().flatMapLatest { (id, _) ->
        id?.let { runManager.approvalRequestFor(it) } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editorState = MutableStateFlow<WorkflowEditorUiState?>(null)
    val editorState = _editorState.asStateFlow()

    private var editHistory: WorkflowEditHistory? = null

    init {
        viewModelScope.launch { repository.ensureBuiltins() }
    }

    /**
     * 自动扫描工程目录或工作区下的 APK 构建产物，按最后修改时间倒序排列（最新在前）。
     * 全盘 walkTopDown 开销不可控，必须挂起并切到 IO 执行，禁止在组合/主线程同步调用。
     */
    suspend fun scanWorkspaceApks(projectName: String): List<DiscoveredApk> =
        withContext(Dispatchers.IO) {
            scanApksInWorkspace(pathManager.workspaceDir, projectName)
        }

    fun start(definition: WorkflowDefinition, projectName: String, variables: Map<String, String> = emptyMap()) {
        val safeProject = projectName.trim().trim('/').takeIf { it.isNotEmpty() }
        val workspace = variables["TARGET_WORKSPACE"]?.takeIf { it.startsWith('/') }
            ?: safeProject?.let { "/workspace/$it" } ?: "/workspace"
        launchAndShow(definition, variables, workspace)
    }

    /** 历史重跑使用扩图前定义及保存的变量/工作区，动态提案重新规划并审批。 */
    fun rerun(entry: WorkflowHistoryEntry) {
        val snapshot = entry.state
        val workspace = snapshot.context?.workspacePath?.takeIf { it.startsWith('/') } ?: "/workspace"
        launchAndShow(snapshot.baseDefinition ?: snapshot.definition, snapshot.context?.globalVariables ?: emptyMap(), workspace)
    }

    private fun launchAndShow(definition: WorkflowDefinition, variables: Map<String, String>, workspace: String) {
        launchAndShow { runManager.start(definition, variables, workspace) }
    }

    fun resume(state: WorkflowRuntimeState, retryNodeIds: Set<String>) {
        launchAndShow { runManager.resume(state.executionId, retryNodeIds, state.resumeCount, state.journal.size) }
    }

    private fun launchAndShow(action: suspend () -> WorkflowRunManager.WorkflowStartResult) {
        // 运行所有权在 WorkflowRunManager（进程级），离开页面/销毁 ViewModel 不再取消运行
        viewModelScope.launch {
            val result = action()
            if (!result.accepted) {
                _error.value = result.failureReason
                return@launch
            }
            _viewingRunId.value = result.executionId
            _historySnapshot.value = null
            if (!Settings.canDrawOverlays(appContext)) {
                _error.value = "未授予悬浮窗权限，工作流进度仅在应用内显示。可在系统设置中开启「显示在其他应用上层」。"
            }
        }
    }

    /** 切换当前页面展示的运行（活跃或终态回看）。 */
    fun viewRun(executionId: String) {
        _viewingRunId.value = executionId
    }

    fun decide(nodeId: String, approved: Boolean, variables: Map<String, String> = emptyMap(), reviewToken: String? = null) {
        val executionId = _viewingRunId.value ?: return
        runManager.decide(executionId, nodeId, approved, variables, reviewToken)
    }

    fun cancel() {
        val executionId = _viewingRunId.value ?: return
        runManager.cancel(executionId)
    }

    fun showHistory(state: WorkflowRuntimeState) {
        // 该执行可能已被注册表回收，快照兜底展示
        _historySnapshot.value = state
        _viewingRunId.value = state.executionId
    }

    fun closeRun() {
        // 仅解除页面与运行的绑定；运行继续在后台推进，由 WorkflowRunManager 负责
        _viewingRunId.value = null
        _historySnapshot.value = null
        if (hud.session.value == null) {
            WorkflowHudService.stop(appContext)
        }
    }

    fun createWorkflow() = openEditor(blankWorkflowDefinition(), initiallySaved = false)

    fun importWorkflow(definition: WorkflowDefinition) = openEditor(WorkflowDefinitionImport.fresh(definition), initiallySaved = false)

    fun edit(definition: WorkflowDefinition) {
        if (definition.isBuiltin) {
            val suffix = UUID.randomUUID().toString().take(8)
            openEditor(
                definition.copy(
                    id = "workflow_$suffix",
                    name = "${definition.name} 副本",
                    isBuiltin = false,
                    trigger = WorkflowTrigger.Manual(),
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
                initiallySaved = false,
            )
        } else {
            openEditor(definition, initiallySaved = true)
        }
    }

    private fun openEditor(definition: WorkflowDefinition, initiallySaved: Boolean) {
        val history = WorkflowEditHistory(definition, initiallySaved)
        editHistory = history
        publishEditor(history, selectedNodeId = definition.nodes.firstOrNull()?.id)
    }

    fun closeEditor() {
        editHistory = null
        _editorState.value = null
    }

    fun selectNode(nodeId: String?) {
        _editorState.value = _editorState.value?.copy(selectedNodeId = nodeId, message = null)
    }

    fun beginConnection(nodeId: String) {
        _editorState.value = _editorState.value?.copy(connectionSourceId = nodeId, selectedNodeId = nodeId, message = "请选择目标节点")
    }

    fun cancelConnection() {
        _editorState.value = _editorState.value?.copy(connectionSourceId = null, message = null)
    }

    fun connectTo(targetNodeId: String) {
        val source = _editorState.value?.connectionSourceId ?: return
        mutate {
            WorkflowGraphEditor.connect(
                it,
                WorkflowEdge("edge_${UUID.randomUUID().toString().take(8)}", source, "output", targetNodeId),
            )
        }
        _editorState.value = _editorState.value?.copy(connectionSourceId = null)
    }

    fun addNode(type: WorkflowNodeType) {
        val state = _editorState.value ?: return
        val suffix = UUID.randomUUID().toString().take(8)
        val (nextX, nextY) = WorkflowGraphEditor.calculateNextNodePosition(
            existingNodes = state.definition.nodes,
            selectedNodeId = state.selectedNodeId,
        )
        val node = WorkflowNode(
            id = "node_$suffix",
            type = type,
            title = type.defaultTitle(),
            canvasX = nextX,
            canvasY = nextY,
            config = type.defaultConfig(),
            failurePolicy = if (type == WorkflowNodeType.CONDITION_BRANCH) FailurePolicy.CONTINUE else FailurePolicy.ABORT,
        )
        mutate(selectedNodeId = node.id) { WorkflowGraphEditor.addNode(it, node) }
    }

    fun updateMetadata(name: String, description: String, category: String, maxParallelNodes: Int) = mutate {
        updatedWorkflowMetadata(it, name, description, category, maxParallelNodes)
    }

    fun updateNode(node: WorkflowNode) = mutate(selectedNodeId = node.id) { WorkflowGraphEditor.updateNode(it, node) }

    fun autoLayout() = mutate { WorkflowLayout.arrange(it) }

    fun moveNode(nodeId: String, x: Float, y: Float) = mutate(selectedNodeId = nodeId) { definition ->
        val node = definition.nodes.firstOrNull { it.id == nodeId } ?: return@mutate definition
        WorkflowGraphEditor.updateNode(definition, node.copy(canvasX = x.coerceAtLeast(0f), canvasY = y.coerceAtLeast(0f)))
    }

    fun removeSelectedNode() {
        val nodeId = _editorState.value?.selectedNodeId ?: return
        mutate(selectedNodeId = null) { WorkflowGraphEditor.removeNode(it, nodeId) }
    }

    fun removeNode(nodeId: String) {
        mutate(selectedNodeId = if (_editorState.value?.selectedNodeId == nodeId) null else _editorState.value?.selectedNodeId) {
            WorkflowGraphEditor.removeNode(it, nodeId)
        }
    }

    fun connectNodes(fromNodeId: String, toNodeId: String, port: String = "output", condition: String? = null) {
        val edge = WorkflowEdge(
            id = "edge_${UUID.randomUUID().toString().take(8)}",
            fromNodeId = fromNodeId,
            fromPort = port,
            toNodeId = toNodeId,
            conditionExpression = condition?.trim()?.ifBlank { null },
        )
        mutate { WorkflowGraphEditor.connect(it, edge) }
    }

    fun disconnectNodes(fromNodeId: String, toNodeId: String) {
        mutate { definition ->
            val toRemove = definition.edges.filter { it.fromNodeId == fromNodeId && it.toNodeId == toNodeId }
            toRemove.fold(definition) { acc, edge -> WorkflowGraphEditor.removeEdge(acc, edge.id) }
        }
    }

    fun disconnectAllForNode(nodeId: String) {
        mutate { definition ->
            val toRemove = definition.edges.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
            toRemove.fold(definition) { acc, edge -> WorkflowGraphEditor.removeEdge(acc, edge.id) }
        }
    }

    fun updateEdge(edge: WorkflowEdge) = mutate { WorkflowGraphEditor.updateEdge(it, edge) }

    fun removeEdge(edgeId: String) = mutate { WorkflowGraphEditor.removeEdge(it, edgeId) }

    fun undoEdit() {
        val history = editHistory ?: return
        if (history.undo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun redoEdit() {
        val history = editHistory ?: return
        if (history.redo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun saveEditor() {
        val history = editHistory ?: return
        _editorState.value = _editorState.value?.copy(isSaving = true, message = null)
        viewModelScope.launch {
            runCatching { repository.upsert(history.current.copy(updatedAt = System.currentTimeMillis())) }
                .onSuccess {
                    history.markSaved()
                    publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId, message = "已保存")
                }
                .onFailure { error ->
                    _editorState.value = _editorState.value?.copy(isSaving = false, message = error.message ?: "保存失败")
                }
        }
    }

    fun deleteWorkflow(definition: WorkflowDefinition) {
        if (definition.isBuiltin) return
        viewModelScope.launch { repository.deleteCustom(definition.id) }
    }

    private fun mutate(selectedNodeId: String? = _editorState.value?.selectedNodeId, block: (WorkflowDefinition) -> WorkflowDefinition) {
        val history = editHistory ?: return
        runCatching { block(history.current) }
            .onSuccess { next ->
                history.commit(next)
                publishEditor(history, selectedNodeId)
            }
            .onFailure { error ->
                _editorState.value = _editorState.value?.copy(message = error.message ?: "编辑失败")
            }
    }

    private fun publishEditor(history: WorkflowEditHistory, selectedNodeId: String?, message: String? = null) {
        val connectionSourceId = _editorState.value?.connectionSourceId
            ?.takeIf { id -> history.current.nodes.any { it.id == id } }
        _editorState.value = WorkflowEditorUiState(
            definition = history.current,
            selectedNodeId = selectedNodeId?.takeIf { id -> history.current.nodes.any { it.id == id } },
            connectionSourceId = connectionSourceId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            isDirty = history.isDirty,
            message = message,
        )
    }

    private fun decodeHistory(log: WorkflowExecutionLogEntity): WorkflowHistoryEntry? {
        val state = runCatching { json.decodeFromString<WorkflowRuntimeState>(log.finalContextJson) }.getOrNull()
            ?: return null
        return WorkflowHistoryEntry(state = state, triggerSource = log.triggerSource, scheduleId = log.scheduleId)
    }
}

internal fun scanApksInWorkspace(workspaceDir: File, projectName: String): List<DiscoveredApk> {
    val safeProject = projectName.trim().trim('/').takeIf { it.isNotEmpty() }
    val projectDir = if (safeProject != null) File(workspaceDir, safeProject) else null

    // 优先在当前工程目录搜索，如果当前工程下未找到，再扩大至整个工作区根目录
    val targetDirs = listOfNotNull(
        projectDir?.takeIf { it.isDirectory },
        workspaceDir.takeIf { it.isDirectory },
    ).distinct()

    val results = mutableListOf<DiscoveredApk>()
    val seenPaths = mutableSetOf<String>()

    for (dir in targetDirs) {
        val isProjectScope = dir == projectDir
        runCatching {
            dir.walkTopDown()
                .maxDepth(8)
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .forEach { file ->
                    val relPath = file.relativeTo(dir).path.replace('\\', '/')
                    val sandboxPath = if (isProjectScope && safeProject != null) {
                        "/workspace/$safeProject/$relPath"
                    } else {
                        val relToWorkspace = file.relativeTo(workspaceDir).path.replace('\\', '/')
                        "/workspace/$relToWorkspace"
                    }
                    if (seenPaths.add(file.absolutePath)) {
                        results.add(
                            DiscoveredApk(
                                file = file,
                                name = file.name,
                                relativePath = if (isProjectScope) relPath else file.relativeTo(workspaceDir).path.replace('\\', '/'),
                                sandboxPath = sandboxPath,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                            ),
                        )
                    }
                }
        }
        if (results.isNotEmpty()) break
    }

    return results.sortedByDescending { it.lastModified }
}

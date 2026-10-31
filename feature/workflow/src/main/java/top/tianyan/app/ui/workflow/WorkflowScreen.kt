package top.tianyan.app.ui.workflow
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    projectName: String = "",
    initialWorkflowId: String? = null,
    initialVariables: Map<String, String> = emptyMap(),
    initialExecutionId: String? = null,
    onBack: () -> Unit,
    viewModel: WorkflowViewModel? = previewOrNull { koinViewModel() },
) {
    val definitions by viewModel?.definitions?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val history by viewModel?.history?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val models by viewModel?.models?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val schedules by viewModel?.schedules?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val backgroundRuns by viewModel?.backgroundActiveRuns?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val error by viewModel?.error?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel?.clearError() } }
    var pendingRun by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var scheduleEditor by remember { mutableStateOf<WorkflowScheduleEntity?>(null) }
    var scheduleEditorIsNew by remember { mutableStateOf(false) }
    val activeState by viewModel?.activeState?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val approval by viewModel?.approvalRequest?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val editorState by viewModel?.editorState?.collectAsStateWithLifecycle() ?: previewStateOf<WorkflowEditorUiState?>(null)
    var discardRequested by rememberSaveable { mutableStateOf(false) }
    // 通知深链：进入页面即定位到指定执行（活跃运行或历史回看）
    LaunchedEffect(initialExecutionId) {
        if (initialExecutionId != null) viewModel?.viewRun(initialExecutionId)
    }
    var autoStarted by rememberSaveable(initialWorkflowId) { mutableStateOf(false) }
    LaunchedEffect(initialWorkflowId, definitions, autoStarted) {
        if (!autoStarted && initialWorkflowId != null) {
            definitions.firstOrNull { it.id == initialWorkflowId }?.let {
                autoStarted = true
                pendingRun = it
            }
        }
    }
    val catalogVisible = activeState == null && editorState == null
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = if (catalogVisible) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (catalogVisible) RuntimeTopBar(title = "天衍工作流", onBack = onBack, scrollBehavior = scrollBehavior)
            else RuntimeTopBar(
                title = activeState?.definition?.name ?: editorState?.definition?.name ?: "天衍工作流",
                statusText = projectName.takeIf(String::isNotBlank)?.let { "工作区 · $it" },
                onBack = {
                    when {
                        activeState != null -> viewModel?.closeRun()
                        editorState?.isDirty == true -> discardRequested = true
                        editorState != null -> viewModel?.closeEditor()
                        else -> onBack()
                    }
                },
            )
        },
    ) { padding ->
        when {
            editorState != null -> WorkflowEditorView(
                state = editorState!!,
                activeRunState = activeState,
                onRun = {
                    if (editorState!!.isDirty) {
                        viewModel?.saveEditor()
                    }
                    pendingRun = editorState!!.definition
                },
                onCancelRun = { viewModel?.cancel() },
                onSelectNode = { arg0 -> viewModel?.selectNode(arg0) },
                onMoveNode = { arg0, arg1, arg2 -> viewModel?.moveNode(arg0, arg1, arg2) },
                onBeginConnection = { arg0 -> viewModel?.beginConnection(arg0) },
                onCancelConnection = { viewModel?.cancelConnection() },
                onConnect = { arg0 -> viewModel?.connectTo(arg0) },
                onAddNode = { arg0 -> viewModel?.addNode(arg0) },
                onUpdateNode = { arg0 -> viewModel?.updateNode(arg0) },
                onUpdateMetadata = { arg0, arg1, arg2, arg3 -> viewModel?.updateMetadata(arg0, arg1, arg2, arg3) },
                onRemoveNode = { viewModel?.removeSelectedNode() },
                onRemoveNodeById = { arg0 -> viewModel?.removeNode(arg0) },
                onConnectNodes = { arg0, arg1, arg2, arg3 -> viewModel?.connectNodes(arg0, arg1, arg2, arg3) },
                onDisconnectNodes = { arg0, arg1 -> viewModel?.disconnectNodes(arg0, arg1) },
                onDisconnectAllForNode = { arg0 -> viewModel?.disconnectAllForNode(arg0) },
                onUpdateEdge = { arg0 -> viewModel?.updateEdge(arg0) },
                onRemoveEdge = { arg0 -> viewModel?.removeEdge(arg0) },
                onUndo = { viewModel?.undoEdit() },
                onRedo = { viewModel?.redoEdit() },
                onSave = { viewModel?.saveEditor() },
                onAutoLayout = { viewModel?.autoLayout() },
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            )
            activeState == null -> WorkflowCatalog(
                definitions = definitions,
                onRun = { pendingRun = it },
                history = history,
                onHistory = { arg0 -> viewModel?.showHistory(arg0) },
                onRerun = { arg0 -> viewModel?.rerun(arg0) },
                onResume = { arg0, arg1 -> viewModel?.resume(arg0, arg1) },
                onEdit = { arg0 -> viewModel?.edit(arg0) },
                onDelete = { arg0 -> viewModel?.deleteWorkflow(arg0) },
                onCreate = { viewModel?.createWorkflow() },
                onImport = { arg0 -> viewModel?.importWorkflow(arg0) },
                backgroundRuns = backgroundRuns,
                onViewBackgroundRun = { arg0 -> viewModel?.viewRun(arg0) },
                schedules = schedules,
                onNewSchedule = {
                    scheduleEditor = null
                    scheduleEditorIsNew = true
                },
                onEditSchedule = { scheduleEditor = it },
                onToggleSchedule = { arg0, arg1 -> viewModel?.toggleSchedule(arg0, arg1) },
                onDeleteSchedule = { arg0 -> viewModel?.deleteSchedule(arg0) },
                modifier = Modifier.fillMaxSize().then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)), bottomPadding = padding.calculateBottomPadding(),
            )
            else -> WorkflowRunView(
                state = activeState!!,
                onCancel = { viewModel?.cancel() },
                modifier = Modifier.fillMaxSize().then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)), bottomPadding = padding.calculateBottomPadding(),
            )
        }
    }
    approval?.let { request ->
        ApprovalDialog(request, onDecision = { approved, variables -> viewModel?.decide(request.nodeId, approved, variables, request.reviewToken) })
    }
    pendingRun?.let { definition ->
        // APK 扫描是全盘 walkTopDown，必须在协程里异步加载，禁止在组合期间同步执行
        var apks by remember(definition.id, projectName) { mutableStateOf<List<DiscoveredApk>>(emptyList()) }
        val apkScope = rememberCoroutineScope()
        LaunchedEffect(definition.id, projectName) {
            apks = viewModel?.scanWorkspaceApks(projectName).orEmpty()
        }
        WorkflowStartDialog(
            definition = definition,
            supplied = initialVariables,
            models = models,
            availableApks = apks,
            onRefreshApks = {
                apkScope.launch { apks = viewModel?.scanWorkspaceApks(projectName).orEmpty() }
            },
            onDismiss = { pendingRun = null },
        ) { variables ->
            pendingRun = null
            viewModel?.start(definition, projectName, variables)
        }
    }
    if (discardRequested) {
        RuntimeAlertDialog(
            onDismissRequest = { discardRequested = false },
            title = { Text("放弃未保存更改？") },
            text = { Text("当前工作流还有未保存的编辑。") },
            dismissButton = { RuntimeOutlinedButton(onClick = { discardRequested = false }) { Text("继续编辑") } },
            confirmButton = {
                RuntimeButton(onClick = { discardRequested = false; viewModel?.closeEditor() }) { Text("放弃更改") }
            },
        )
    }
    if (scheduleEditorIsNew || scheduleEditor != null) {
        WorkflowScheduleEditDialog(
            definitions = definitions,
            models = models,
            existing = scheduleEditor.takeIf { !scheduleEditorIsNew },
            onDismiss = {
                scheduleEditor = null
                scheduleEditorIsNew = false
            },
            onSave = { entity ->
                viewModel?.saveSchedule(entity)
                scheduleEditor = null
                scheduleEditorIsNew = false
            },
        )
    }
}

@Composable
private fun WorkflowRunView(
    state: WorkflowRuntimeState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    BoxWithConstraints(modifier) {
        var selectedNode by remember(state.executionId) { mutableStateOf<String?>(null) }
        if (top.tianyan.app.ui.components.widthClassOf(maxWidth.value) == top.tianyan.app.ui.components.RuntimeWidthClass.Expanded) {
            Row(Modifier.fillMaxSize()) {
                WorkflowCanvas2D(state.definition, state, Modifier.weight(1f).fillMaxSize(), selectedNodeId = selectedNode, onNodeSelected = { selectedNode = it })
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(12.dp).padding(bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RunSummary(state)
                    val node = state.definition.nodes.firstOrNull { it.id == selectedNode }
                    if (node == null) Text("点选节点查看日志、错误与产物")
                    else {
                        Text(node.title, style = MaterialTheme.typography.titleMedium)
                        WorkflowNodeDetails(state.nodeStates[node.id])
                    }
                }
            }
        } else {
            WorkflowTimelineView(state, Modifier.fillMaxSize(), bottomPadding)
        }
        if (state.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)) {
            RuntimeOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = bottomPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("取消", maxLines = 1) }
        }
    }
}

@Composable
fun WorkflowTimelineView(state: WorkflowRuntimeState, modifier: Modifier = Modifier, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            RunSummary(state)
        }
        items(state.definition.nodes, key = { it.id }) { node ->
            val run = state.nodeStates[node.id]
            val status = run?.status ?: NodeRunStatus.IDLE
            val isRunning = status == NodeRunStatus.RUNNING || status == NodeRunStatus.STREAMING
            val isWaiting = status == NodeRunStatus.WAITING_APPROVAL
            val color = statusColor(status)

            val pulseAlpha = if (isRunning) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse_${node.id}")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.40f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseAlpha",
                )
                alpha
            } else if (isWaiting) {
                0.85f
            } else {
                0.55f
            }

            val cardContainerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f * pulseAlpha)
            } else if (isWaiting) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.20f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }

            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = cardContainerColor,
                borderColor = color.copy(alpha = pulseAlpha),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RuntimeIcon(statusIcon(status), Modifier.size(18.dp), tint = color.copy(alpha = if (isRunning) pulseAlpha else 1f))
                        Spacer(Modifier.width(10.dp))
                        Text(node.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val duration = run?.output?.durationMs
                        if (duration != null && duration > 0L) {
                            Text("${duration}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(end = 6.dp))
                        }
                        Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, fontWeight = if (isRunning || isWaiting) FontWeight.Bold else FontWeight.Normal)
                    }
                    run?.progressMessage?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    WorkflowNodeDetails(run)
                }
            }
        }
        item { Spacer(Modifier.height(64.dp)) }
    }
}

@Composable
private fun RunSummary(state: WorkflowRuntimeState) {
    val color = when (state.status) {
        WorkflowRunStatus.SUCCESS -> Color(0xFF2E7D32)
        WorkflowRunStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    RuntimeCard(modifier = Modifier.fillMaxWidth(), borderColor = color.copy(alpha = 0.45f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuntimeIcon(statusIcon(state.nodeStates.values.lastOrNull()?.status ?: NodeRunStatus.PENDING), Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(workflowStatusLabel(state.status), fontWeight = FontWeight.SemiBold)
                WorkflowElapsed(state)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun statusColor(status: NodeRunStatus): Color = when (status) {
    NodeRunStatus.SUCCESS -> Color(0xFF2E7D32)
    NodeRunStatus.FAILED, NodeRunStatus.CANCELLED -> MaterialTheme.colorScheme.error
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING, NodeRunStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.primary
    NodeRunStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}

internal fun statusIcon(status: NodeRunStatus) = when (status) {
    NodeRunStatus.SUCCESS -> RuntimeIconName.Check
    NodeRunStatus.FAILED, NodeRunStatus.CANCELLED -> RuntimeIconName.Alert
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING -> RuntimeIconName.Play
    NodeRunStatus.WAITING_APPROVAL -> RuntimeIconName.Shield
    NodeRunStatus.SKIPPED -> RuntimeIconName.ArrowUp
    else -> RuntimeIconName.Hub
}

internal fun statusLabel(status: NodeRunStatus) = when (status) {
    NodeRunStatus.IDLE -> "空闲"
    NodeRunStatus.PENDING -> "等待"
    NodeRunStatus.RUNNING -> "运行中"
    NodeRunStatus.STREAMING -> "输出中"
    NodeRunStatus.WAITING_APPROVAL -> "待确认"
    NodeRunStatus.SUCCESS -> "成功"
    NodeRunStatus.FAILED -> "失败"
    NodeRunStatus.SKIPPED -> "已跳过"
    NodeRunStatus.CANCELLED -> "已取消"
}

internal fun workflowStatusLabel(status: WorkflowRunStatus) = when (status) {
    WorkflowRunStatus.IDLE -> "准备执行"
    WorkflowRunStatus.RUNNING -> "工作流运行中"
    WorkflowRunStatus.WAITING_APPROVAL -> "等待人工确认"
    WorkflowRunStatus.SUCCESS -> "工作流已完成"
    WorkflowRunStatus.FAILED -> "工作流执行失败"
    WorkflowRunStatus.CANCELLED -> "工作流已取消"
}

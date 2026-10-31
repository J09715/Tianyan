package top.tianyan.app.ui.chat
import kotlinx.coroutines.flow.first
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.ui.text.style.TextOverflow
import top.tianyan.app.harness.workflow.ProactiveWorkflowSuggestion
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.MainDestination
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.LazyListState
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.database.AgentPlanEntity
import top.tianyan.app.harness.QueuedPrompt
import top.tianyan.app.harness.compaction.CompactionSnapshot
import top.tianyan.app.harness.mcp.McpWorkspaceRecommender
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.feature.a2uipoc.LocalA2uiSessionId
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.checkpoint.RewindScope
import top.tianyan.app.harness.session.ConversationBranch
import top.tianyan.app.runtime.DistributionCatalog
/** 会改动工作区文件的工具：这些工具执行后「仓库」入口按需高亮提示新改动 */
private val REPOSITORY_HIGHLIGHT_TOOLS = setOf(
    HarnessTool.WRITE,
    HarnessTool.EDIT,
    HarnessTool.BASE,
    HarnessTool.PROCESS,
)
@Composable
private fun chatBottomInsets(): WindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime)

/** 天衍 · 智枢对话界面 (Tianyan Agent)：智能结对编程、工具自动化调用与代码生成 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenNavigation: () -> Unit,
    onInstallMcp: () -> Unit = {},
    onCreateModelProfile: () -> Unit = {},
    onOpenFile: ((projectName: String, relativePath: String) -> Unit)? = null,
    terminalPane: (@Composable (project: String) -> Unit)? = null,
    browserPane: (@Composable (onExit: (() -> Unit)?) -> Unit)? = null,
    browserActivityTick: Long = 0L,
    browserBackPressed: (() -> Boolean)? = null,
    onOpenRepository: ((projectName: String) -> Unit)? = null,
    viewModel: ChatViewModel? = previewOrNull { koinViewModel() },
) {
    val messages by viewModel?.messages?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val running by viewModel?.running?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val error by viewModel?.error?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val input by viewModel?.input?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val pendingAttachments by viewModel?.pendingAttachments?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val status by viewModel?.status?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val thinkingLive by viewModel?.thinkingLive?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val thinkingExpanded by viewModel?.thinkingExpanded?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val thinkingAutoTranslate by viewModel?.thinkingAutoTranslate?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val chatRoundCollapse by viewModel?.chatRoundCollapse?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val sessions by viewModel?.sessions?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val workspaceCatalog by viewModel?.workspaceCatalog?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.runtime.SessionWorkspaceCatalogState()); val workspaces = workspaceCatalog.projects
    val models by viewModel?.models?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val providerModelIds by viewModel?.providerModelIds?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val discoveringProviderModels by viewModel?.discoveringProviderModels?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val providerModelDiscoveryError by viewModel?.providerModelDiscoveryError?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val modelPickerProfileId by viewModel?.modelPickerProfileId?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val workspace by viewModel?.workspace?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val mcpRecommendations by viewModel?.mcpRecommendations?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val workflowSuggestions by viewModel?.workflowSuggestions?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val btwQueries by viewModel?.btwController?.queries?.collectAsStateWithLifecycle()
        ?: previewStateOf(emptyList<top.tianyan.app.core.model.BtwQuery>())
    val btwStreaming by viewModel?.btwController?.streaming?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val shareActions by viewModel?.shareActions?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val hiddenSkillSuggestions by viewModel?.hiddenSkillSuggestions?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val sessionProjectType by viewModel?.projectType?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val matchingCommands by viewModel?.matchingCommands?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val matchingMentions by viewModel?.matchingMentions?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val attachedMentions by viewModel?.attachedMentions?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val queuedPrompts by viewModel?.queuedPrompts?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val sendMode by viewModel?.sendMode?.collectAsStateWithLifecycle() ?: previewStateOf(ComposerSendMode.NEXT_RUN)
    val branches by viewModel?.branches?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val runtimeEvents by viewModel?.runtimeEvents?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val currentSessionId by viewModel?.currentSessionId?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val sessionRunStates by viewModel?.sessionRunStates?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val activeDistroId by viewModel?.activeDistroId?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val installedDistros by viewModel?.installedDistros?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val allSkills by viewModel?.allSkills?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val pinnedCapabilities by viewModel?.pinnedCapabilities?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val pinnedMentionIds by viewModel?.pinnedMentionIds?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val memories by viewModel?.memories?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val scratchpads by viewModel?.scratchpads?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val mcpServers by viewModel?.mcpServers?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val mcpConnectionStates by viewModel?.mcpConnectionStates?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpToolCounts by viewModel?.mcpToolCounts?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val initializing by viewModel?.initializing?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val speech = rememberChatSpeech(currentSessionId, messages, running, initializing)
    val pendingApprovals by viewModel?.pendingApprovals?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val activePlan by viewModel?.activePlan?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val activeCompaction by viewModel?.activeCompaction?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val contextUsage by viewModel?.contextUsage?.collectAsStateWithLifecycle() ?: previewStateOf(ContextUsage())
    val onboardingPrivilege by viewModel?.privilegeOnboarding?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val attachmentsProcessing by viewModel?.attachmentsProcessing?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val notice by viewModel?.notice?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val subagentResult by viewModel?.subagentResult?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    // 弹窗开关与编辑目标：用 rememberSaveable 保存，旋转 / 进程重建后不丢失
    var showModels by rememberSaveable { mutableStateOf(false) }; var showApprovalModes by rememberSaveable { mutableStateOf(false) }
    var showRunModes by rememberSaveable { mutableStateOf(false) }; var showSkillsMcpSheet by rememberSaveable { mutableStateOf(false) }
    var showBranches by rememberSaveable { mutableStateOf(false) }; var showRuntimeTimeline by rememberSaveable { mutableStateOf(false) }
    var showMemorySheet by rememberSaveable { mutableStateOf(false) }
    val timelineFocus = rememberSaveable(currentSessionId) { mutableStateOf<String?>(null) }
    val timelineItems = remember(currentSessionId) { mutableStateOf(ChatTimelineSnapshot()) }
    var showReasoningSlider by rememberSaveable { mutableStateOf(false) }
    var branchFromMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    // 编辑目标消息只保存 id，避免把不可保存的实体放进状态保存器
    var editTargetMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    val editTargetMessage = remember(messages, editTargetMessageId) {
        messages.filterIsInstance<UserMessage>().firstOrNull { it.id == editTargetMessageId }
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val currentSession = remember(sessions, currentSessionId) { sessions.firstOrNull { it.id == currentSessionId } }
    val boundModelProfile = remember(models, currentSession?.modelId) {
        currentSession?.modelId?.let { id -> models.firstOrNull { it.id == id } }
    }
    val activeModel = remember(models, boundModelProfile, currentSession?.modelVariant) {
        val profile = boundModelProfile ?: models.firstOrNull { it.isActive }
        profile?.copy(
            model = currentSession?.modelVariant
                ?.takeIf { boundModelProfile != null && it.isNotBlank() }
                ?: profile.model.substringBefore(',').trim(),
        )
    }
    val currentApprovalMode = remember(currentSession?.approvalMode) { ApprovalMode.fromId(currentSession?.approvalMode) }
    val currentRunMode = remember(currentSession?.runMode) { RunMode.fromId(currentSession?.runMode) }
    val activeWorkspaceProject = remember(workspace, workspaces) {
        workspaces.firstOrNull { it.linuxPath == workspace }
    }
    val effectiveWorkspaceProject = remember(activeWorkspaceProject, sessionProjectType) {
        val override = ProjectType.entries.firstOrNull { it.name == sessionProjectType.uppercase() }
        activeWorkspaceProject?.let { project -> override?.let { project.copy(projectType = it) } ?: project }
    }
    val distroDisplayName = remember(activeDistroId) {
        runCatching { DistributionCatalog.require(activeDistroId).displayName }
            .getOrDefault(activeDistroId)
    }

    // 性能：不再直接依赖 `messages` 整个 list 引用（流式输出时每个 token 块都会换新引用，
    // 导致这几处 O(n) 派生每帧重算）。改为依赖「由消息列表推导出的、真正影响结果的稳定键」。
    val toolResults = remember(
        // ToolResult 的数量 + 末条内容长度：只要没有新增/更新工具结果，就不需要重算映射。
        messages.count { it is ToolResult },
        messages.lastOrNull { it is ToolResult }?.let { (it as ToolResult).output.length },
    ) {
        messages.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    }
    val lastAssistantMessageId = remember(
        // 只有「新增了一条 AssistantText」才会改变结果，故依赖其数量与末条 id。
        messages.count { it is AssistantText },
        messages.lastOrNull { it is AssistantText }?.id,
    ) {
        messages.filterIsInstance<AssistantText>().lastOrNull()?.id
    }
    // 两者语义不同（实时思考高亮 vs 重新生成定位），但取值一致，复用同一计算结果
    val liveThinkingMessageId = lastAssistantMessageId
    val currentBranch = remember(branches) { branches.firstOrNull { it.isCurrent } }

    // Agent 联动：上次访问「仓库」页之后 agent 又写过文件（write/edit/base/process），
    // 则工作条「仓库」入口高亮，提示有新改动可提交；进入仓库页即熄灭。
    var lastRepositoryVisitAt by rememberSaveable { mutableStateOf(0L) }
    val repositoryHighlight = remember(messages, lastRepositoryVisitAt) {
        messages.filterIsInstance<ToolCall>().any { call ->
            call.createdAt > lastRepositoryVisitAt && call.tool in REPOSITORY_HIGHLIGHT_TOOLS
        }
    }

    val isImeVisible = WindowInsets.isImeVisible
    val coroutineScope = rememberCoroutineScope()

    // Navigation3 removes inactive tab content from composition. Persist this marker with the
    // entry so returning to 智枢 does not perform a second, redundant scrollToItem during the
    // tab transition.
    var initialPositionedSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 会话键只需首条消息 id，依赖单值而非整个 list。
    val currentSessionKey = remember(messages.firstOrNull()?.id) { messages.firstOrNull()?.id ?: "" }

    val lastMessageSignature = remember(messages) {
        val last = messages.lastOrNull()
        when (last) {
            is AssistantText -> "${last.id}:${last.reasoning?.length ?: 0}:${last.text.length}"
            is ToolCall -> "${last.id}:${last.args.hashCode()}"
            is ToolResult -> "${last.id}:${last.output.length}"
            is UserMessage -> "${last.id}:${last.text.length}"
            else -> "${messages.size}"
        }
    }

    val followLatest = rememberChatFollowLatest(listState)
    // 🌟 1. 消息发送与流式输出跟随滚动
    // 仅当用户本来就停在底部附近时才跟随输出滚动；上滑阅读历史时不打扰，
    // 重新滑回底部（或新发消息）后恢复自动跟随。
    var lastSeenMessageCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size, lastMessageSignature) {
        val userSent = messages.size > lastSeenMessageCount && messages.lastOrNull() is UserMessage
        lastSeenMessageCount = messages.size
        if (messages.isNotEmpty()) {
            delay(30)
            val reposition = initialPositionedSessionKey != currentSessionKey
            if (reposition) initialPositionedSessionKey = currentSessionKey
            if (reposition || followLatest || userSent) listState.safeScrollToAbsoluteBottom()
        }
    }

    ChatImeFollowLatest(listState, isImeVisible, followLatest)

    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    val grantLabel = stringResource(R.string.chat_snackbar_grant)
    val gotItLabel = stringResource(R.string.chat_snackbar_got_it)
    LaunchedEffect(Unit) {
        viewModel?.permissionRequests?.collect { req ->
            val actionLabel = when (req.permission) {
                "WRITE_SETTINGS" -> grantLabel
                else -> gotItLabel
            }
            val result = snackbarHostState.showSnackbar(
                message = req.reason,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && req.permission == "WRITE_SETTINGS") {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
            }
        }
    }

    // VM 层轻量通知（附件处理失败 / 模型档案已存在 / 新会话已创建等）以 Toast 上抛
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(appContext, it, Toast.LENGTH_SHORT).show()
            viewModel?.clearNotice()
        }
    }

    // rewind 完成：Snackbar 附「撤销回滚」动作（Toast 无法承载动作）
    val rewindUndoLabel = stringResource(R.string.chat_rewind_undo)
    LaunchedEffect(Unit) {
        viewModel?.rewindCompletedEvents?.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = rewindUndoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel?.undoLastRewind()
            }
        }
    }

    // 切换会话后自动关闭遗留的子智能体成果抽屉，避免跨会话展示旧 lane
    LaunchedEffect(currentSessionId) {
        val opened = subagentResult
        if (opened != null && opened.sessionId != currentSessionId) viewModel?.closeSubagentResult()
    }

    // 模型·权限·主线·轮次 工具条：紧凑态浏览器整页打开时被覆盖，其余形态固定在顶部。
    // onOpenBrowser 非空时工作台追加"浏览器"入口（agent 有新动态时高亮）；companion 非空时显示伴随面板开关。
    val openModels = {
        if (models.isEmpty()) onCreateModelProfile() else showModels = true
    }
    val noProjectHint = stringResource(R.string.chat_repository_no_project)
    var toolchainGap by remember { mutableStateOf(false) }; var showToolchainPanel by remember { mutableStateOf(false) }

    val chatTopBar: @Composable (onOpenBrowser: (() -> Unit)?, browserHighlight: Boolean, companion: ChatCompanionToggle?) -> Unit =
        { onOpenBrowser, browserHighlight, companion ->
            ChatTopBar(
                runMode = currentRunMode,
                currentBranch = currentBranch,
                runtimeEvents = runtimeEvents,
                running = running,
                onOpenSessions = onOpenNavigation, onOpenBots = { onNavigate(MainDestination.Bots) }, onOpenWorkshop = { onNavigate(MainDestination.Workspace) },
                onOpenRunModes = { showRunModes = true },
                onOpenBranches = { showBranches = true },
                onOpenRuntime = { showRuntimeTimeline = true }, onOpenMemory = { showMemorySheet = true },
                onOpenBrowser = onOpenBrowser,
                browserHighlight = browserHighlight,
                onOpenRepository = onOpenRepository?.let { open ->
                    {
                        val project = activeWorkspaceProject
                        if (project != null) {
                            lastRepositoryVisitAt = System.currentTimeMillis()
                            open(project.name)
                        } else {
                            Toast.makeText(appContext, noProjectHint, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                repositoryHighlight = repositoryHighlight,
                toolchainHighlight = toolchainGap, onOpenToolchain = { showToolchainPanel = true },
                onOpenReasoning = { showReasoningSlider = true }, companion = companion, speech = speech,
            )
        }

    // 模型回复里的 /workspace、/attachments 等沙箱路径在此翻译为宿主真实文件，
    // 否则 Coil 会按 Android 根文件系统路径加载而必然失败。
    CompositionLocalProvider(LocalSandboxHostRoots provides (viewModel?.sandboxHostRoots ?: emptyMap()), LocalA2uiSessionId provides currentSessionId, LocalChatTimelineFocus provides timelineFocus, LocalChatTimelineItems provides timelineItems, LocalChatContextLimit provides contextUsage.limitTokens) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        ChatPageSurface(Modifier.fillMaxSize().padding(padding)) {
            val knownMentionNames = remember(allSkills, mcpServers) {
                (allSkills.filter { it.isEnabled }.flatMap { skill ->
                    listOfNotNull(skill.name, skill.id, skill.triggerCommand?.removePrefix("/"))
                } + mcpServers.filter { it.isEnabled }.flatMap { listOf(it.name, it.id) })
                    .filter { it.isNotBlank() }
                    .distinct()
            }

            val bottomInsets = chatBottomInsets()

            // 对话主内容：双栏左栏与单栏整页共用（仅 modifier 不同）
            val chatContent: @Composable (Modifier) -> Unit = { paneModifier ->
                ChatPaneContent(
                    onboardingPrivilege = onboardingPrivilege,
                    modifier = paneModifier,
                    messages = messages,
                    listState = listState,
                    running = running,
                    status = status,
                    thinkingExpanded = thinkingExpanded,
                    thinkingAutoTranslate = thinkingAutoTranslate,
                    chatRoundCollapse = chatRoundCollapse,
                    thinkingLive = thinkingLive,
                    liveThinkingMessageId = liveThinkingMessageId,
                    onNavigateToSettings = { viewModel?.navigateToAgentSettings() },
                    toolResults = toolResults,
                    workspace = workspace,
                    workspaceProject = effectiveWorkspaceProject,
                    onOpenFile = onOpenFile,
                    onEditMessage = { editTargetMessageId = it.id },
                    onDeleteMessage = { arg0 -> viewModel?.deleteMessage(arg0) },
                    onRewindMessage = { arg0, arg1 -> viewModel?.rewindToMessage(arg0, arg1) }, error = error,
                    onClearError = { viewModel?.clearError() },
                    matchingCommands = matchingCommands, matchingMentions = matchingMentions,
                    attachedMentions = attachedMentions, knownMentionNames = knownMentionNames,
                    queuedPrompts = queuedPrompts,
                    onEditQueuedPrompt = { arg0 -> viewModel?.editQueuedPrompt(arg0) },
                    onRemoveQueuedPrompt = { arg0 -> viewModel?.removeQueuedPrompt(arg0) }, onConvertToSteer = { arg0 -> viewModel?.convertQueuedPromptToSteer(arg0) },
                    sendMode = sendMode,
                    input = input,
                    onInputChanged = { arg0 -> viewModel?.onInputChanged(arg0) },
                    onApplyCommand = { arg0 -> viewModel?.applySlashCommand(arg0) },
                    onApplyMention = { arg0 -> viewModel?.applyMention(arg0) },
                    onRemoveMention = { arg0 -> viewModel?.removeMention(arg0) },
                    attachments = pendingAttachments,
                    attachmentsProcessing = attachmentsProcessing,
                    onAttachmentsPicked = { arg0, arg1 -> viewModel?.onAttachmentsPicked(arg0, arg1) },
                    onRemoveAttachment = { arg0 -> viewModel?.removeAttachment(arg0) },
                    onSend = { viewModel?.sendFromComposer() },
                    onStop = { viewModel?.stop() },
                    lastAssistantMessageId = lastAssistantMessageId,
                    onRegenerate = { viewModel?.regenerateLast() },
                    onCreateBranch = { branchFromMessageId = it },
                    onRetryTool = { arg0 -> viewModel?.retryToolCall(arg0) },
                    initializing = initializing,
                    pinnedCapabilities = pinnedCapabilities,
                    onOpenSkillsMcp = { showSkillsMcpSheet = true },
                    onCompactContext = { viewModel?.compactContextNow() },
                    onUnpinMention = { arg0 -> viewModel?.unpinMention(arg0) },
                    activePlan = activePlan,
                    pendingApprovals = pendingApprovals,
                    onResolveApproval = { arg0, arg1, arg2 -> viewModel?.resolveApproval(arg0, arg1, arg2) },
                    onResolveQuestion = { arg0, arg1 -> viewModel?.resolveQuestion(arg0, arg1) },
                    contextUsage = contextUsage,
                    activeModel = activeModel,
                    onOpenModels = openModels, approvalMode = currentApprovalMode, onOpenApprovalModes = { showApprovalModes = true },
                    onUpdateReasoning = { arg0, arg1 -> viewModel?.updateActiveModelReasoning(arg0, arg1) },
                    activeCompaction = activeCompaction,
                    mcpRecommendations = mcpRecommendations,
                    onEnableMcpRecommendation = { arg0 -> viewModel?.enableMcpRecommendation(arg0) },
                    onInstallMcpRecommendation = onInstallMcp,
                    onDismissMcpRecommendation = { arg0 -> viewModel?.dismissMcpRecommendation(arg0) },
                    workflowSuggestions = workflowSuggestions,
                    btwQueries = btwQueries,
                    btwStreaming = btwStreaming,
                    onCancelBtw = { viewModel?.btwController?.cancel() },
                    onLaunchWorkflowSuggestion = { arg0 -> viewModel?.launchWorkflowSuggestion(arg0) },
                    onDismissWorkflowSuggestion = { arg0 -> viewModel?.dismissWorkflowSuggestion(arg0) },
                    shareActions = shareActions,
                    hiddenSkillSuggestions = hiddenSkillSuggestions,
                    onApplySkillSuggestion = { arg0, arg1 -> viewModel?.applySkillSuggestion(arg0, arg1) },
                    onDismissSkillSuggestion = { arg0 -> viewModel?.dismissSkillSuggestion(arg0) },
                    onViewSubagentLanes = { showBranches = true },
                    subagentBranches = branches,
                    onOpenSubagentBranch = { arg0 -> viewModel?.openSubagentResult(arg0) },
                )
            }

            // 三态布局：紧凑态浏览器整页、中间态右侧滑出、展开态常驻可拖拽双栏
            ChatAdaptiveLayout(
                workspace = workspace, terminalPane = terminalPane, browserPane = browserPane,
                browserActivityTick = browserActivityTick, browserBackPressed = browserBackPressed,
                bottomInsets = bottomInsets, topBar = chatTopBar, chatContent = chatContent,
            )
        }
    }

    // 编辑并重新发送对话框
    editTargetMessage?.let { target ->
        EditAndResendDialog(
            originalText = target.text,
            onDismiss = { editTargetMessageId = null },
            onConfirm = { newText ->
                viewModel?.editAndResend(target.id, newText)
                editTargetMessageId = null
            },
        )
    }

    if (showModels) {
        ModelDialog(
            models = models,
            selectedProfileId = activeModel?.id,
            selectedModelVariant = activeModel?.model,
            providerModelIds = providerModelIds,
            discoveringProviderModels = discoveringProviderModels,
            providerModelDiscoveryError = providerModelDiscoveryError,
            modelPickerProfileId = modelPickerProfileId,
            onDismiss = {
                viewModel?.closeProviderModelPicker()
                showModels = false
            },
            onSelectProfile = { id -> viewModel?.setActiveModel(id) },
            onSelectSubModel = { id, subModel -> viewModel?.selectModel(id, subModel) },
            onOpenModelPicker = { arg0 -> viewModel?.openProviderModelPicker(arg0) },
            onCloseModelPicker = { viewModel?.closeProviderModelPicker() },
            onRefreshModels = { arg0 -> viewModel?.discoverProviderModels(arg0) },
            onSwitchModel = { arg0, arg1 -> viewModel?.switchModelInProfile(arg0, arg1) },
            onCreate = onCreateModelProfile,
            onDelete = { arg0 -> viewModel?.deleteModel(arg0) },
        )
    }

    if (showSkillsMcpSheet) {
        // 打开挂载面板时自动探测一次 MCP 连通性
        LaunchedEffect(Unit) { viewModel?.refreshMcpConnections() }
        SkillsAndMcpSheet(
            allSkills = allSkills,
            mcpServers = mcpServers,
            mcpConnectionStates = mcpConnectionStates,
            mcpToolCounts = mcpToolCounts,
            pinnedMentionIds = pinnedMentionIds,
            onDismiss = { showSkillsMcpSheet = false },
            onToggleSkill = { id, enabled ->
                viewModel?.setSkillEnabled(id, enabled)
            },
            onToggleMcpServer = { id, enabled ->
                viewModel?.setMcpServerEnabled(id, enabled)
            },
            onTogglePin = { arg0 -> viewModel?.togglePinMention(arg0) },
            onNavigateToSettings = {
                showSkillsMcpSheet = false
                onNavigate(MainDestination.Settings)
            },
        )
    }

    if (showBranches) {
        BranchBrowserSheet(
            branches = branches,
            running = running,
            onDismiss = { showBranches = false },
            onSwitch = { branch ->
                viewModel?.switchBranch(branch)
                showBranches = false
            },
            // 成果抽屉叠在分支列表之上，关闭后自然回到列表，再关闭即回主会话
            onOpenSubagent = { arg0 -> viewModel?.openSubagentResult(arg0) },
        )
    }

    // 子智能体完整调研成果（独立 lane 的只读 transcript）
    subagentResult?.let { state ->
        SubagentResultSheet(
            state = state,
            onRefresh = { viewModel?.refreshSubagentResult() },
            onDismiss = { viewModel?.closeSubagentResult() },
        )
    }

    ToolchainHost(showToolchainPanel, { showToolchainPanel = false }) { toolchainGap = it }
    if (showRuntimeTimeline) {
        RuntimeTimelineSheet(
            events = runtimeEvents,
            messages = messages,
            memories = memories,
            scratchpads = scratchpads,
            onDeleteMemory = { arg0 -> viewModel?.deleteMemory(arg0) },
            onDeleteScratchpad = { arg0 -> viewModel?.deleteScratchpad(arg0) },
            onClearScratchpads = { viewModel?.clearScratchpads() },
            onNavigateToMessage = { messageId ->
                showRuntimeTimeline = false
                coroutineScope.launch {
                    // 等待同一列表的真实投影展开目标轮次，避免手动折叠改变索引后定位错位。
                    timelineFocus.value = messageId
                    val renderItems = kotlinx.coroutines.withTimeoutOrNull(1_000) {
                        androidx.compose.runtime.snapshotFlow { timelineItems.value }.first { it.focusedId == messageId && it.items.any { item -> item.containsMessage(messageId) } }.items
                    } ?: return@launch
                    val targetIndex = renderItems.indexOfFirst { item ->
                        item.containsMessage(messageId)
                    }
                    if (targetIndex < 0) return@launch
                    // 头部仅包含初始化/空态与压缩横幅，任务计划不占单独条目。
                    val headerOffset = (if (initializing) 1 else 0) +
                        (if (!initializing && messages.isEmpty()) 1 else 0) +
                        (if (activeCompaction != null) 1 else 0)
                    listState.safeScrollToItem(targetIndex + headerOffset, animated = true)
                }
            },
            onDismiss = { showRuntimeTimeline = false },
        )
    }

    if (showApprovalModes) {
        ApprovalModeSheet(currentApprovalMode = currentApprovalMode,
            onSelect = { mode -> viewModel?.setCurrentSessionApprovalMode(mode); showApprovalModes = false },
            onDismiss = { showApprovalModes = false }) }

    if (showRunModes) {
        RunModeSheet(currentRunMode = currentRunMode,
            onSelect = { mode -> viewModel?.setCurrentSessionRunMode(mode); showRunModes = false },
            onDismiss = { showRunModes = false }) }

    if (showReasoningSlider) {
        PlainBackdropModalBottomSheet(onDismissRequest = { showReasoningSlider = false }) { ReasoningEffortSlider(
            currentMode = activeModel?.reasoningMode, currentEffort = activeModel?.reasoningEffort,
            onSelect = { mode, effort -> viewModel?.updateActiveModelReasoning(mode, effort); showReasoningSlider = false },
            onClose = { showReasoningSlider = false }) }
    }

    if (showMemorySheet) {
        SessionMemorySheet(memories = memories, scratchpads = scratchpads, sessionId = currentSessionId, failureMemoryAccess = viewModel?.failureMemoryAccess,
            onDeleteMemory = { arg0 -> viewModel?.deleteMemory(arg0) }, onDeleteScratchpad = { arg0 -> viewModel?.deleteScratchpad(arg0) },
            onClearScratchpads = { viewModel?.clearScratchpads() }, onDismiss = { showMemorySheet = false })
    }

    branchFromMessageId?.let { messageId ->
        CreateBranchDialog(messageId = messageId, onDismiss = { branchFromMessageId = null },
            onCreate = { id, name -> viewModel?.createBranch(id, name); branchFromMessageId = null })
    }
    } // CompositionLocalProvider(LocalSandboxHostRoots)
}

@Composable
private fun ChatPaneContent(
    modifier: Modifier = Modifier,
    messages: List<HarnessMessage>,
    onboardingPrivilege: OnboardingPrivilege? = null,
    listState: LazyListState,
    running: Boolean,
    status: String?,
    thinkingExpanded: Boolean,
    thinkingAutoTranslate: Boolean = false,
    chatRoundCollapse: Boolean = false,
    thinkingLive: Boolean,
    liveThinkingMessageId: String?,
    onNavigateToSettings: (() -> Unit)? = null,
    toolResults: Map<String, ToolResult>,
    workspace: String,
    workspaceProject: WorkspaceProject? = null,
    onOpenFile: ((projectName: String, relativePath: String) -> Unit)?,
    onEditMessage: (UserMessage) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRewindMessage: (String, RewindScope) -> Unit = { _, _ -> },
    error: String?,
    onClearError: () -> Unit,
    matchingCommands: List<SlashCommandItem>,
    matchingMentions: List<MentionItem> = emptyList(),
    attachedMentions: List<MentionItem> = emptyList(),
    knownMentionNames: List<String> = emptyList(),
    queuedPrompts: List<QueuedPrompt>,
    onEditQueuedPrompt: (QueuedPrompt) -> Unit,
    onRemoveQueuedPrompt: (QueuedPrompt) -> Unit,
    onConvertToSteer: (QueuedPrompt) -> Unit = {},
    sendMode: ComposerSendMode,
    input: String,
    shareActions: ShareQuickActionsState? = null,
    onInputChanged: (String) -> Unit,
    onApplyCommand: (SlashCommandItem) -> Unit,
    onApplyMention: (MentionItem) -> Unit = {},
    onRemoveMention: (MentionItem) -> Unit = {},
    attachments: List<ChatAttachment> = emptyList(),
    attachmentsProcessing: Boolean = false,
    onAttachmentsPicked: (List<Uri>, Boolean) -> Unit = { _, _ -> },
    onRemoveAttachment: (ChatAttachment) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    lastAssistantMessageId: String?,
    onRegenerate: () -> Unit,
    onCreateBranch: (String) -> Unit,
    onRetryTool: (String) -> Unit,
    initializing: Boolean = false,
    pinnedCapabilities: List<MentionItem> = emptyList(),
    onOpenSkillsMcp: () -> Unit = {},
    onCompactContext: () -> Unit = {},
    onUnpinMention: (String) -> Unit = {},
    activeModel: AiModelEntity? = null,
    onOpenModels: () -> Unit, approvalMode: ApprovalMode, onOpenApprovalModes: () -> Unit,
    onUpdateReasoning: (mode: String?, effort: String?) -> Unit = { _, _ -> },
    pendingApprovals: List<AgentApprovalRequestEntity> = emptyList(),
    activePlan: AgentPlanEntity? = null,
    activeCompaction: CompactionSnapshot? = null,
    mcpRecommendations: List<McpWorkspaceRecommender.Recommendation> = emptyList(),
    onEnableMcpRecommendation: (String) -> Unit = {},
    onInstallMcpRecommendation: () -> Unit = {},
    onDismissMcpRecommendation: (String) -> Unit = {},
    workflowSuggestions: List<ProactiveWorkflowSuggestion> = emptyList(),
    onLaunchWorkflowSuggestion: (ProactiveWorkflowSuggestion) -> Unit = {},
    onDismissWorkflowSuggestion: (String) -> Unit = {},
    btwQueries: List<top.tianyan.app.core.model.BtwQuery> = emptyList(),
    btwStreaming: String = "",
    onCancelBtw: () -> Unit = {},
    onResolveApproval: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onResolveQuestion: (String, String) -> Unit = { _, _ -> },
    contextUsage: ContextUsage = ContextUsage(),
    onViewSubagentLanes: () -> Unit = {},
    subagentBranches: List<ConversationBranch> = emptyList(),
    onOpenSubagentBranch: (ConversationBranch) -> Unit = {},
    hiddenSkillSuggestions: Set<String> = emptySet(),
    onApplySkillSuggestion: (SkillSuggestion, Boolean) -> Unit = { _, _ -> },
    onDismissSkillSuggestion: (String) -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) {
        ChatMessageViewport(listState, Modifier.weight(1f).fillMaxWidth()) { ChatMessageList(
            modifier = Modifier.fillMaxSize(),
            listState = listState,
            messages = messages,
            toolResults = toolResults,
            initializing = initializing,
            running = running,
            status = status,
            workspace = workspace,
            workspaceProject = workspaceProject,
            onboardingPrivilege = onboardingPrivilege,
            thinkingExpanded = thinkingExpanded,
            thinkingAutoTranslate = thinkingAutoTranslate,
            chatRoundCollapse = chatRoundCollapse,
            thinkingLive = thinkingLive,
            liveThinkingMessageId = liveThinkingMessageId,
            lastAssistantMessageId = lastAssistantMessageId,
            onNavigateToSettings = onNavigateToSettings,
            knownMentionNames = knownMentionNames,
            onSelectCommand = onApplyCommand,
            onEditMessage = onEditMessage,
            onDeleteMessage = onDeleteMessage,
            onRewindMessage = onRewindMessage,
            onCreateBranch = onCreateBranch,
            onRegenerate = onRegenerate,
            onRetryTool = onRetryTool,
            onOpenFile = onOpenFile,
            activeCompaction = activeCompaction,
            activePlan = activePlan,
            pendingApprovals = pendingApprovals,
            onResolveApproval = onResolveApproval,
            onResolveQuestion = onResolveQuestion,
            onViewSubagentLanes = onViewSubagentLanes,
            subagentBranches = subagentBranches,
            onOpenSubagent = onOpenSubagentBranch,
            hiddenSkillSuggestions = hiddenSkillSuggestions,
            onApplySkillSuggestion = onApplySkillSuggestion,
            onDismissSkillSuggestion = onDismissSkillSuggestion,
        ) }

        activePlan?.let { plan ->
            val steps = remember(plan.stepsJson) { PlanStepParser.parse(plan.stepsJson) }
            if (steps.isNotEmpty()) {
                StickyPlanBar(
                    goal = plan.goal,
                    steps = steps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
        }

        error?.let { ChatErrorBanner(error = it, onClear = onClearError) }

        McpRecommendationBanner(
            recommendations = mcpRecommendations,
            onEnable = onEnableMcpRecommendation,
            onInstall = onInstallMcpRecommendation,
            onDismiss = onDismissMcpRecommendation,
        )

        ProactiveWorkflowBanner(
            suggestions = workflowSuggestions,
            onLaunch = onLaunchWorkflowSuggestion,
            onDismiss = onDismissWorkflowSuggestion,
        )

        BtwPanel(
            queries = btwQueries,
            streaming = btwStreaming,
            onCancel = onCancelBtw,
        )

        shareActions?.let { share -> ShareQuickActionsRow(state = share, modifier = Modifier.padding(vertical = 4.dp)) }

        Column(Modifier.chatInputScrim().windowInsetsPadding(chatBottomInsets())) { ChatComposer(
            listState = listState,
            running = running,
            initializing = initializing,
            status = status,
            messages = messages,
            toolResults = toolResults,
            workspace = workspace,
            input = input,
            onInputChanged = onInputChanged,
            onSend = onSend,
            onStop = onStop,
            sendMode = sendMode,
            matchingCommands = matchingCommands,
            onApplyCommand = onApplyCommand,
            matchingMentions = matchingMentions,
            onApplyMention = onApplyMention,
            attachments = attachments,
            attachmentsProcessing = attachmentsProcessing,
            onAttachmentsPicked = onAttachmentsPicked,
            onRemoveAttachment = onRemoveAttachment,
            queuedPrompts = queuedPrompts,
            onEditQueuedPrompt = onEditQueuedPrompt,
            onRemoveQueuedPrompt = onRemoveQueuedPrompt,
            onConvertToSteer = onConvertToSteer,
            knownMentionNames = knownMentionNames,
            attachedMentions = attachedMentions,
            onRemoveMention = onRemoveMention,
            pinnedCapabilities = pinnedCapabilities,
            onUnpinMention = onUnpinMention,
            onOpenSkillsMcp = onOpenSkillsMcp,
            onCompactContext = onCompactContext,
            activeModel = activeModel,
            onOpenModels = onOpenModels, approvalMode = approvalMode, onOpenApprovalModes = onOpenApprovalModes,
            onUpdateReasoning = onUpdateReasoning,
            contextUsage = contextUsage,
        ) }
    }
    }
}
/** Event-driven workflow actions shown directly above the composer as interactive pills. */
@Composable
private fun ProactiveWorkflowBanner(
    suggestions: List<ProactiveWorkflowSuggestion>,
    onLaunch: (ProactiveWorkflowSuggestion) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Hub, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(
                        modifier = Modifier
                            .clickable { onLaunch(suggestion) }
                            .padding(end = 4.dp),
                    ) {
                        Text(
                            text = if (suggestion.projectName.isNotBlank()) "${suggestion.projectName} · ${suggestion.title}" else suggestion.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                        )
                        if (suggestion.description.isNotBlank()) {
                            Text(
                                text = suggestion.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = { onLaunch(suggestion) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("运行", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = { onDismiss(suggestion.workflowId) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Close,
                            Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }
    }
}

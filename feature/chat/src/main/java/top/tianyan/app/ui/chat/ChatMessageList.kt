package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.checkpoint.RewindScope
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.scrollFadingEdge
import top.tianyan.app.ui.components.SkeletonBlock
import top.tianyan.app.ui.components.SkeletonCircle
import top.tianyan.app.ui.components.SkeletonTextLine
import top.tianyan.app.ui.theme.Radius
import top.tianyan.app.ui.theme.Spacing
import androidx.compose.foundation.lazy.LazyListState
import top.tianyan.app.core.model.QuickPhrase
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.database.AgentPlanEntity
import top.tianyan.app.harness.compaction.CompactionSnapshot
import top.tianyan.app.runtime.ProjectType

/**
 * 工具卡片折叠状态 Saver：Map 本身不能存入 Bundle（rememberSaveable 会抛 IllegalArgumentException），
 * 把 entries 序列化为 ArrayList<Pair<String, Boolean>>（两者均可安全保存）。
 */
private val ExpandedOverridesSaver = Saver<Map<String, Boolean>, ArrayList<Pair<String, Boolean>>>(
    save = { state -> ArrayList(state.map { (key, value) -> key to value }) },
    restore = { saved -> saved.toMap() },
)

/**
 * 消息流 LazyColumn：骨架屏、空态引导、压缩横幅、任务看板、消息渲染项与审批卡。
 */
@Composable
internal fun ChatMessageList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    initializing: Boolean,
    running: Boolean,
    imageGenerationModel: Boolean = false,
    status: String?,
    workspace: String,
    workspaceProject: WorkspaceProject?,
    onboardingPrivilege: OnboardingPrivilege?,
    thinkingExpanded: Boolean,
    thinkingLive: Boolean,
    liveThinkingMessageId: String?,
    lastAssistantMessageId: String?,
    knownMentionNames: List<String>,
    quickPhrases: List<QuickPhrase>,
    onSelectPhrase: (QuickPhrase) -> Unit,
    onSelectCommand: (SlashCommandItem) -> Unit,
    onEditMessage: (UserMessage) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onRewindMessage: (String, RewindScope) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit,
    onRetryTool: (String) -> Unit,
    onOpenFile: ((String, String) -> Unit)?,
    activeCompaction: CompactionSnapshot?,
    activePlan: AgentPlanEntity?,
    pendingApprovals: List<AgentApprovalRequestEntity>,
    onResolveApproval: (String, Boolean) -> Unit,
    onViewSubagentLanes: () -> Unit = {},
    subagentBranches: List<top.tianyan.app.harness.session.ConversationBranch> = emptyList(),
    onOpenSubagent: (top.tianyan.app.harness.session.ConversationBranch) -> Unit = {},
) {
    // 折叠状态用自定义 Saver：Map 不能直接存入 Bundle（会抛 IllegalArgumentException）
    var expandedOverrides by rememberSaveable(stateSaver = ExpandedOverridesSaver) { mutableStateOf(mapOf<String, Boolean>()) }

    val renderItems = remember(messages, toolResults, expandedOverrides) {
        projectChatMessages(
            messages = messages,
            toolResults = toolResults,
            expandedOverrides = expandedOverrides,
        )
    }
    val waitingForFirstOutput = remember(messages, running, imageGenerationModel) {
        if (!running) false else {
            val lastUserIndex = messages.indexOfLast { it is UserMessage }
            imageGenerationModel && lastUserIndex >= 0 && messages.drop(lastUserIndex + 1).none { message ->
                when (message) {
                    is AssistantText -> message.text.isNotBlank() || !message.reasoning.isNullOrBlank()
                    is ToolCall -> true
                    else -> false
                }
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.scrollFadingEdge(top = 4.dp, bottom = 20.dp),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.xl),
    ) {
        if (initializing) {
            item {
                ChatMessageSkeleton(
                    modifier = Modifier.padding(vertical = Spacing.lg),
                )
            }
        } else {
            if (messages.isEmpty()) {
                item {
                    EmptyChatGuidance(
                        workspaceProject = workspaceProject,
                        onboardingPrivilege = onboardingPrivilege,
                        quickPhrases = quickPhrases,
                        onSelectPhrase = onSelectPhrase,
                        onSelectCommand = onSelectCommand,
                    )
                }
            }
            // 上下文压缩透明度横幅：真实折叠条数 + 摘要原地展开预览。
            activeCompaction?.let { snapshot ->
                item(key = "session_compaction_banner") {
                    CompactionBanner(
                        snapshot = snapshot,
                        modifier = Modifier.padding(bottom = Spacing.xs),
                    )
                }
            }
            // contentType 让 LazyColumn 能跳过与可见项无关的重组：消息多时同一帧内的无效重组
            // 会被放大成整页掉帧。key 已能定位，contentType 决定能否跳过。
            itemsIndexed(
                items = renderItems,
                key = { _, item -> item.stableKey },
                contentType = { _, item -> chatItemContentType(item) },
            ) { index, item ->
                val top = when {
                    item is ChatRenderItem.MessageItem && item.message is UserMessage -> 12.dp
                    // 思考过程、工具调用卡片、能力事件之间的垂直外边距压至紧凑的 2.5dp
                    isThinkingOrActionItem(item) && isThinkingOrActionItem(renderItems.getOrNull(index - 1)) -> 2.5.dp
                    // 思考/工具刚结束紧接的最终回复气泡间距压缩为 4dp
                    item is ChatRenderItem.MessageItem && item.message is AssistantText && isThinkingOrActionItem(renderItems.getOrNull(index - 1)) -> 4.dp
                    else -> 8.dp
                }
                if (index > 0) Spacer(Modifier.height(top))
                when (item) {
                    is ChatRenderItem.CollapseButtonItem -> {
                        RoundCollapseButton(
                            item = item,
                            onToggle = {
                                val currentExpanded = item.isExpanded
                                expandedOverrides = expandedOverrides + (item.roundKey to !currentExpanded)
                            },
                        )
                    }
                    is ChatRenderItem.MessageItem -> {
                        when (val message = item.message) {
                            is CapabilityEvent -> CapabilityEventCard(message)
                            is UserMessage -> UserBubble(
                                message = message,
                                knownMentionNames = knownMentionNames,
                                onEdit = { onEditMessage(message) },
                                onDelete = { onDeleteMessage(message.id) },
                                onCreateBranch = { onCreateBranch(message.id) },
                                onRewind = { scope -> onRewindMessage(message.id, scope) },
                            )
                            is AssistantText -> AssistantBubble(
                                message = message,
                                defaultExpanded = thinkingExpanded,
                                live = thinkingLive && message.id == liveThinkingMessageId,
                                showRegenerate = message.id == lastAssistantMessageId,
                                onRegenerate = onRegenerate,
                                onCreateBranch = { onCreateBranch(message.id) },
                            )
                            is ToolCall -> {
                                if (message.tool == HarnessTool.SUBAGENT) {
                                    SubagentCard(
                                        call = message,
                                        result = toolResults[message.id],
                                        subagentBranches = subagentBranches,
                                        onOpenSubagent = onOpenSubagent,
                                        onViewDetails = onViewSubagentLanes,
                                    )
                                } else {
                                    ToolCard(
                                        call = message,
                                        result = toolResults[message.id],
                                        workspace = workspace,
                                        onOpenFile = onOpenFile,
                                        running = running,
                                        liveStatus = status,
                                        // 思考链去重已在投影时预计算，重组路径零扫描
                                        showReasoning = item.showReasoning,
                                        defaultExpanded = thinkingExpanded,
                                        onRetry = { onRetryTool(message.id) },
                                    )
                                }
                            }
                            is ToolResult -> Unit
                        }
                    }
                }
            }
            if (waitingForFirstOutput) {
                item(key = "assistant_generation_placeholder") {
                    Spacer(Modifier.height(Spacing.sm))
                    AssistantGenerationPlaceholder()
                }
            }
        }
        item {
            pendingApprovals.firstOrNull()?.let { request ->
                ApprovalRequestCard(
                    request = request,
                    onApprove = { onResolveApproval(request.id, true) },
                    onReject = { onResolveApproval(request.id, false) },
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

/** LazyColumn contentType：结构相同的渲染项共用一个类型号，异构项互不复用组合缓存。 */
private fun chatItemContentType(item: ChatRenderItem): Int = when {
    item is ChatRenderItem.CollapseButtonItem -> 0
    item is ChatRenderItem.MessageItem && item.message is UserMessage -> 1
    item is ChatRenderItem.MessageItem && item.message is AssistantText -> 2
    item is ChatRenderItem.MessageItem &&
        item.message is ToolCall &&
        item.message.tool == HarnessTool.SUBAGENT -> 3
    item is ChatRenderItem.MessageItem && item.message is ToolCall -> 4
    item is ChatRenderItem.MessageItem && item.message is CapabilityEvent -> 5
    else -> 6
}

/** Visible while a non-streaming model (notably image generators) is still preparing its result. */
@Composable
private fun AssistantGenerationPlaceholder() {
    RuntimeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), Radius.smShape),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(RuntimeIconName.Image, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.chat_generating_content),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.chat_generating_content_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        }
    }
}

@Composable
internal fun EmptyChatGuidance(
    workspaceProject: WorkspaceProject? = null,
    onboardingPrivilege: OnboardingPrivilege? = null,
    quickPhrases: List<QuickPhrase> = emptyList(),
    onSelectPhrase: (QuickPhrase) -> Unit = {},
    onSelectCommand: (SlashCommandItem) -> Unit,
) {
    val context = LocalContext.current
    val enabledPhrases = remember(quickPhrases, workspaceProject) {
        val active = quickPhrases.filter { it.isEnabled }
        if (active.isEmpty()) emptyList()
        else {
            val projectType = workspaceProject?.projectType
            val matched = when (projectType) {
                ProjectType.ANDROID -> active.filter { it.targetProjectType == "ANDROID" || it.targetProjectType == null }
                ProjectType.FLUTTER -> active.filter { it.targetProjectType == "FLUTTER" || it.targetProjectType == null }
                ProjectType.REVERSE -> active.filter { it.targetProjectType == "REVERSE" || it.targetProjectType == null }
                ProjectType.RED_TEAM -> active.filter { it.targetProjectType == "RED_TEAM" || it.targetProjectType == null }
                else -> active.filter { it.targetProjectType == null }
            }
            if (matched.isNotEmpty()) matched else active.take(4)
        }
    }

    val quickCommands = remember(workspaceProject) {
        when (workspaceProject?.projectType) {
            ProjectType.ANDROID -> listOf(
                SlashCommandItem("/android-check", context.getString(R.string.chat_android_check), context.getString(R.string.chat_android_check_description), context.getString(R.string.chat_android_check_prompt), RuntimeIconName.Check),
                SlashCommandItem("/android-build-install", context.getString(R.string.chat_android_build), context.getString(R.string.chat_android_build_description), context.getString(R.string.chat_android_build_prompt), RuntimeIconName.Play),
                SlashCommandItem("/android-debug", context.getString(R.string.chat_android_debug), context.getString(R.string.chat_android_debug_description), context.getString(R.string.chat_android_debug_prompt), RuntimeIconName.Alert),
            )
            ProjectType.FLUTTER -> listOf(
                SlashCommandItem("/flutter-check", context.getString(R.string.chat_flutter_check), context.getString(R.string.chat_flutter_check_description), context.getString(R.string.chat_flutter_check_prompt), RuntimeIconName.Check),
                SlashCommandItem("/flutter-build-install", context.getString(R.string.chat_flutter_build), context.getString(R.string.chat_flutter_build_description), context.getString(R.string.chat_flutter_build_prompt), RuntimeIconName.Play),
                SlashCommandItem("/flutter-debug", context.getString(R.string.chat_flutter_debug), context.getString(R.string.chat_flutter_debug_description), context.getString(R.string.chat_flutter_debug_prompt), RuntimeIconName.Alert),
            )
            ProjectType.REVERSE -> listOf(
                SlashCommandItem("/reverse-analyze", context.getString(R.string.chat_reverse_analyze), context.getString(R.string.chat_reverse_analyze_description), context.getString(R.string.chat_reverse_analyze_prompt), RuntimeIconName.Search),
                SlashCommandItem("/reverse-decode", context.getString(R.string.chat_reverse_decode), context.getString(R.string.chat_reverse_decode_description), context.getString(R.string.chat_reverse_decode_prompt), RuntimeIconName.Code),
            )
            ProjectType.RED_TEAM -> listOf(
                SlashCommandItem("/redteam-preflight", "红队体检", "检查工具箱与技能就绪度", "跑 redteam 工具的 preflight，列出缺失项与修复命令，不要跳过体检直接开扫。", RuntimeIconName.Shield),
                SlashCommandItem("/redteam-bind", "绑定目标", "绑定授权目标与范围", "先确认授权范围，再用 redteam 工具的 session_info 确认当前绑定；目标用 asset_add 落成事实。", RuntimeIconName.Shield),
                SlashCommandItem("/redteam-report", "演练报告", "汇总事实生成报告", "用 redteam 工具的 report 生成当前演练报告，结论必须来自会话事实，不要写事实里没有的推断。", RuntimeIconName.Document),
            )
            else -> SlashCommands.presetCommands(context).take(4)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        onboardingPrivilege?.let { privilege ->
            val ready = privilege == OnboardingPrivilege.SHIZUKU_READY || privilege == OnboardingPrivilege.ROOT_READY
            Surface(
                color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = Radius.smShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when (privilege) {
                            OnboardingPrivilege.SANDBOX -> R.string.chat_onboarding_sandbox
                            OnboardingPrivilege.SANDBOX_UNLOCKABLE -> R.string.chat_onboarding_unlockable
                            OnboardingPrivilege.SHIZUKU_READY -> R.string.chat_onboarding_shizuku
                            OnboardingPrivilege.ROOT_READY -> R.string.chat_onboarding_root
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
        Text(
            stringResource(R.string.chat_agent_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.chat_quick_start),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (enabledPhrases.isNotEmpty()) {
                enabledPhrases.forEach { phrase ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = Radius.smShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPhrase(phrase) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            RuntimeIcon(parseIconName(phrase.iconName), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    phrase.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    phrase.description.ifBlank { phrase.content },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                quickCommands.forEach { cmd ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = Radius.smShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCommand(cmd) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            RuntimeIcon(cmd.icon, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cmd.command,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseIconName(name: String): RuntimeIconName {
    return when (name.lowercase()) {
        "play" -> RuntimeIconName.Play
        "check" -> RuntimeIconName.Check
        "alert" -> RuntimeIconName.Alert
        "code" -> RuntimeIconName.Code
        "plus" -> RuntimeIconName.Plus
        "package" -> RuntimeIconName.Package
        "search" -> RuntimeIconName.Search
        "brain" -> RuntimeIconName.Brain
        "bot" -> RuntimeIconName.Bot
        "chat" -> RuntimeIconName.Chat
        "refresh" -> RuntimeIconName.Refresh
        "terminal" -> RuntimeIconName.Terminal
        "tool", "wrench" -> RuntimeIconName.Wrench
        else -> RuntimeIconName.Play
    }
}

@Composable
internal fun RoundCollapseButton(
    item: ChatRenderItem.CollapseButtonItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (item.isExpanded) {
        stringResource(R.string.chat_collapse)
    } else {
        val durationSuffix = if (item.hiddenDurationMs > 0) {
            " · " + formatChatDuration(item.hiddenDurationMs)
        } else {
            ""
        }
        stringResource(R.string.chat_expand_more, item.hiddenSteps, item.totalSteps, durationSuffix)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

/**
 * 会话流骨架。形状与真实气泡对齐：用户气泡右侧、宽度约屏宽 65%；助手侧带头像圆点
 * 与两行正文 + 一块代码区。扫光由 `RuntimeSkeleton.kt` 统一提供（与工坊页同速同色），
 * 这里只描述形状。
 */
@Composable
internal fun ChatMessageSkeleton(modifier: Modifier = Modifier) {
    val loadingLabel = stringResource(top.tianyan.app.feature.components.R.string.components_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm)
            .semantics { contentDescription = loadingLabel },
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // 用户消息占位（右侧）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(42.dp),
                shape = Radius.mdShape,
            )
        }
        // 助手消息占位（左侧）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SkeletonCircle(size = 32.dp)
            SkeletonTextLine(widthFraction = 0.85f, height = 14.dp)
            SkeletonTextLine(widthFraction = 0.55f, height = 14.dp)
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(60.dp),
                shape = Radius.smShape,
            )
        }
        // 用户消息占位 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(42.dp),
                shape = Radius.mdShape,
            )
        }
    }
}


package top.tianyan.app.ui.chat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.checkpoint.RewindScope
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.IconTile
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.ScrollFadeOverlay
import androidx.compose.foundation.lazy.LazyListState
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.database.AgentPlanEntity
import top.tianyan.app.harness.compaction.CompactionSnapshot
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.harness.AskUserQuestions
import top.tianyan.app.harness.session.ConversationBranch

/** 工具卡片折叠状态 Saver：Map 不能存入 Bundle，故把 entries 转为安全的 ArrayList<Pair<String, Boolean>>。
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
    status: String?,
    workspace: String,
    workspaceProject: WorkspaceProject?,
    onboardingPrivilege: OnboardingPrivilege?,
    thinkingExpanded: Boolean,
    thinkingAutoTranslate: Boolean = false,
    chatRoundCollapse: Boolean = false,
    thinkingLive: Boolean,
    liveThinkingMessageId: String?,
    lastAssistantMessageId: String?,
    onNavigateToSettings: (() -> Unit)? = null,
    knownMentionNames: List<String>,
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
    onResolveApproval: (String, Boolean, Boolean) -> Unit,
    onResolveQuestion: (String, String) -> Unit = { _, _ -> },
    onViewSubagentLanes: () -> Unit = {},
    subagentBranches: List<ConversationBranch> = emptyList(),
    onOpenSubagent: (ConversationBranch) -> Unit = {},
    hiddenSkillSuggestions: Set<String> = emptySet(),
    onApplySkillSuggestion: (SkillSuggestion, Boolean) -> Unit = { _, _ -> },
    onDismissSkillSuggestion: (String) -> Unit = {},
) {
    // 折叠状态用自定义 Saver：Map 不能直接存入 Bundle（会抛 IllegalArgumentException）
    var expandedOverrides by rememberSaveable(stateSaver = ExpandedOverridesSaver) { mutableStateOf(mapOf<String, Boolean>()) }

    val reveal = rememberRoundRevealState()
    val renderItems = rememberChatRenderItems(messages, toolResults, expandedOverrides, chatRoundCollapse, reveal.limits)
    val waitingForFirstOutput = remember(
        // 只依赖「末条消息的 id + 其内容签名」：流式期间末条内容变化会改变签名（真正需要重算），
        // 而历史消息变化不影响该判定，因此无需依赖整个 messages 列表引用（避免每帧 O(n) 重算）。
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.let { last ->
            when (last) {
                is AssistantText -> "${last.text.length}:${last.reasoning?.length ?: 0}"
                is ToolCall -> "tool:${last.id}"
                else -> "other:${last.id}"
            }
        },
        running,
    ) {
        isWaitingForFirstToken(messages, running)
    }

    // 顶部悬浮栏使用轻量渐变，避免列表滚动期间全量离屏合成。
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
    Box(modifier = modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp, bottom = 8.dp),
    ) {
        if (initializing) {
            item {
                ChatMessageSkeleton(modifier = Modifier.padding(vertical = 16.dp))
            }
        } else {
            if (messages.isEmpty()) {
                item {
                    EmptyChatGuidance(
                        workspaceProject = workspaceProject,
                        onboardingPrivilege = onboardingPrivilege,
                        onSelectCommand = onSelectCommand,
                    )
                }
            }
            // 上下文压缩透明度横幅：真实折叠条数 + 摘要原地展开预览。
            activeCompaction?.let { snapshot ->
                item(key = "session_compaction_banner") {
                    CompactionBanner(
                        snapshot = snapshot,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            itemsIndexed(renderItems, key = { _, item -> item.stableKey }) { index, item ->
                if (index > 0) Spacer(Modifier.height(2.dp))
                when (item) {
                    is ChatRenderItem.CollapseButtonItem -> {
                        RoundCollapseButton(
                            item = item,
                            onToggle = {
                                val wasExpanded = item.isExpanded
                                expandedOverrides = expandedOverrides + (item.roundKey to !wasExpanded)
                                reveal.onToggled(item, wasExpanded, chatRoundCollapse)
                            },
                        )
                    }
                    is ChatRenderItem.MessageItem -> {
                        when (val message = item.message) {
                            is CapabilityEvent -> CapabilityEventCard(message)
                            is TaskDelivery -> TaskDeliveryCard(message)
                            is SkillSuggestion -> if (message.id !in hiddenSkillSuggestions) {
                                SkillSuggestionCard(
                                    suggestion = message,
                                    onCreate = { onApplySkillSuggestion(message, true) }, onUpdate = { onApplySkillSuggestion(message, false) },
                                    onDismiss = { onDismissSkillSuggestion(message.id) },
                                )
                            }
                            is ModelSwitchEvent -> ModelSwitchCard(message)
                            is TurnChangesEvent -> TurnChangesCard(message)
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
                                autoTranslate = thinkingAutoTranslate,
                                onNavigateToSettings = onNavigateToSettings,
                            )
                            is ToolCall -> {
                                // 原始下标由投影阶段预计算（见 projectChatMessages），组合期 O(1)，
                                // 不再在每个 Lazy 项里对 messages 做 indexOfFirst 的 O(n) 全表扫描。
                                val rawIndex = item.rawIndex
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
                                        showReasoning = message.reasoning != null &&
                                            !reasoningAlreadyShown(messages, rawIndex, message.reasoning),
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
                    Spacer(Modifier.height(8.dp))
                    ChatTypingIndicator()
                }
            }
        }
        item {
            pendingApprovals.firstOrNull()?.let { request ->
                if (request.toolName == AskUserQuestions.TOOL_NAME) {
                    AskUserCard(
                        request = request,
                        onAnswer = onResolveQuestion,
                        onSkip = { onResolveApproval(request.id, false, false) },
                    )
                } else {
                    ApprovalRequestCard(
                        request = request,
                        onApprove = { rememberForSession -> onResolveApproval(request.id, true, rememberForSession) },
                        onReject = { onResolveApproval(request.id, false, false) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
        if (!top.tianyan.app.ui.theme.LocalChatBackgroundActive.current) ScrollFadeOverlay(top = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp, bottom = 0.dp)
    }
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
internal fun ChatMessageSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 用户消息占位（右侧）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(brush),
            )
        }
        Spacer(Modifier.height(16.dp))
        // 助手消息占位（左侧）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush),
            )
        }
        Spacer(Modifier.height(16.dp))
        // 用户消息占位 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(brush),
            )
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "chatShimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chatShimmerTranslate",
    )
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.25f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )
}


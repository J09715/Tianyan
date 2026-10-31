package top.tianyan.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.*
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.theme.semanticColors

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
internal fun ToolStepsCard(item: ToolStepsItem, results: Map<String, ToolResult>, messages: List<HarnessMessage>,
    workspace: String, onOpenFile: ((String, String) -> Unit)?, running: Boolean, liveStatus: String?,
    defaultExpanded: Boolean, onRetry: (String) -> Unit, executing: Boolean = running,
    liveReasoningId: String? = null, autoTranslate: Boolean = false,
    onNavigateToSettings: (() -> Unit)? = null) {
    var expanded by rememberSaveable(item.stableKey) { mutableStateOf(true) }
    var visibleCount by rememberSaveable(item.stableKey) { mutableIntStateOf(8) }
    val focus = LocalChatTimelineFocus.current
    val focusedId = focus?.value
    val focusInChain = focusedId != null && item.containsMessage(focusedId)
    LaunchedEffect(focusedId, focusInChain) { if (focusInChain) expanded = true }
    val visible = remember(item.steps, results, executing, visibleCount, focusedId) {
        visibleToolChainSteps(item.steps, results, executing, visibleCount, focusedId)
    }
    val hidden = item.steps.size - visible.size
    val stateLabel = stringResource(if (expanded) R.string.chat_drawer_section_expanded else R.string.chat_drawer_section_collapsed)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val attention = item.steps.count { step ->
        val id = step.toolCallId ?: return@count false
        toolStepState(results[id], executing) in
            setOf(ToolStepState.FAILED, ToolStepState.UNKNOWN, ToolStepState.WAITING, ToolStepState.DEFERRED)
    }
    Column(Modifier.fillMaxWidth().then(chatGreetingModifier()).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable {
            expanded = !expanded
            if (!expanded) focus?.value = null
        }.semantics { stateDescription = stateLabel }.padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_tool_chain_title, item.toolCount), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (attention > 0) Text(stringResource(R.string.chat_tool_chain_attention, attention),
                style = MaterialTheme.typography.labelSmall, color = semanticColors().warning, maxLines = 1)
            RuntimeIcon(if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 8.dp).drawBehind {
            drawLine(lineColor, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        }.padding(start = 12.dp)) {
            if (hidden > 0) RuntimeTextButton(onClick = { visibleCount += 8 }, modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                Text(stringResource(R.string.chat_steps_show_more, hidden), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            visible.forEach { step ->
                key(step.stableKey) {
                    when (step) {
                        is ChatRenderItem.ReasoningItem -> ThinkingBlock(
                            id = step.messageId,
                            reasoning = step.reasoning,
                            defaultExpanded = defaultExpanded,
                            live = step.messageId == liveReasoningId,
                            durationMs = step.durationMs,
                            autoTranslate = autoTranslate,
                            onNavigateToSettings = onNavigateToSettings,
                        )
                        is ChatRenderItem.MessageItem -> {
                            val call = step.message as ToolCall
                            ToolCard(call, results[call.id], workspace, onOpenFile, running, liveStatus,
                                showReasoning = call.reasoning != null &&
                                    !reasoningAlreadyShown(messages, step.rawIndex, call.reasoning),
                                defaultExpanded = defaultExpanded, onRetry = { onRetry(call.id) },
                                executing = executing)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

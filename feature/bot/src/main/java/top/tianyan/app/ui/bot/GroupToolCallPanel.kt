package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.GroupToolStep
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.*

/** Compact process disclosure. Only the selected step opens details, outside the message stream. */
@Composable
internal fun GroupToolCallPanel(messageId: String, steps: List<GroupToolStep>, streaming: Boolean) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(messageId) { mutableStateOf(true) }
    var visibleCount by rememberSaveable(messageId) { mutableIntStateOf(8) }
    var selectedId by rememberSaveable(messageId) { mutableStateOf<String?>(null) }
    val pending = steps.count { it.success == null }
    val failed = steps.count { it.success == false }
    val hidden = steps.take((steps.size - visibleCount).coerceAtLeast(0)).count { it.success == true }
    val action = stringResource(if (expanded) R.string.fbot_steps_collapse else R.string.fbot_steps_expand)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().testTag("group-tools:$messageId")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp)
            .semantics { stateDescription = action }
            .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
            .padding(horizontal = 2.dp, vertical = 4.dp).testTag("group-tools-toggle:$messageId"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.fbot_tool_calls_title, steps.size), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = runtimeProcessColor(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (failed > 0 || (!streaming && pending > 0)) Text(
                stringResource(if (failed > 0) R.string.fbot_tool_failure_count else R.string.fbot_tool_unknown_count,
                    if (failed > 0) failed else pending),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed > 0) MaterialTheme.colorScheme.error else runtimeProcessColor())
            RuntimeIcon(if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                Modifier.size(16.dp), runtimeProcessColor())
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 8.dp).drawBehind {
            drawLine(lineColor, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        }.padding(start = 12.dp).testTag("group-tools-details:$messageId")) {
            if (hidden > 0) RuntimeTextButton(onClick = { visibleCount += 8 }) {
                Text(stringResource(R.string.fbot_process_more_steps, hidden))
            }
            // Keep unfinished and failed steps visible even when outside the recent window.
            steps.forEachIndexed { index, step ->
                if (index >= steps.size - visibleCount || step.success != true) key(step.id) {
                    GroupToolStepRow(step, streaming, onClick = { selectedId = step.id })
                }
            }
        }
    }
    steps.firstOrNull { it.id == selectedId }?.let { step ->
        GroupToolStepDetails(step, streaming, onDismiss = { selectedId = null })
    }
}

@Composable
private fun GroupToolStepRow(step: GroupToolStep, streaming: Boolean, onClick: () -> Unit) {
    val running = streaming && step.success == null
    val status = stringResource(when (step.success) {
        true -> R.string.fbot_tool_success
        false -> R.string.fbot_tool_failure
        null -> if (streaming) R.string.fbot_step_running else R.string.fbot_tool_unknown
    })
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { stateDescription = status }.padding(horizontal = 2.dp, vertical = 4.dp).testTag("group-step:${step.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RuntimeProcessIcon(groupToolIcon(step.name), running)
        Text(groupToolTitle(step.name), Modifier.weight(1f).then(runtimeProcessPulse(running)),
            style = MaterialTheme.typography.bodySmall, color = runtimeProcessColor(),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (step.success == false || (step.success == null && !streaming)) Text(status,
            style = MaterialTheme.typography.labelSmall,
            color = if (step.success == false) MaterialTheme.colorScheme.error else runtimeProcessColor())
        RuntimeProcessTimer(step.id, running, durationMs = step.durationMs)
        RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp), runtimeProcessColor())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupToolStepDetails(step: GroupToolStep, streaming: Boolean, onDismiss: () -> Unit) {
    RuntimeModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeProcessIcon(groupToolIcon(step.name))
                Text(groupToolTitle(step.name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                RuntimeProcessTimer(step.id, streaming && step.success == null, durationMs = step.durationMs)
            }
            SelectionContainer {
                Text(listOfNotNull(step.name, step.detail).joinToString("\n\n"), style = MaterialTheme.typography.bodyMedium)
            }
            RuntimeTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.fbot_close))
            }
        }
    }
}

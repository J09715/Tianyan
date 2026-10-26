package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.session.ConversationBranch
import top.tianyan.app.ui.components.*

@Composable
internal fun ChatWorkspaceBar(
    onOpenBrowser: (() -> Unit)?, browserHighlight: Boolean,
    onOpenRepository: (() -> Unit)?, repositoryHighlight: Boolean,
) {
    val entries = buildList {
        if (onOpenBrowser != null) add(WorkbenchAction(RuntimeIconName.Globe,
            stringResource(R.string.chat_browser_entry), onClick = onOpenBrowser, highlighted = browserHighlight))
        if (onOpenRepository != null) add(WorkbenchAction(RuntimeIconName.GitBranch,
            stringResource(R.string.chat_repository), onClick = onOpenRepository, highlighted = repositoryHighlight))
    }
    WorkbenchActionGrid(entries)
}

@Composable
internal fun ChatWorkbenchControls(
    runMode: RunMode, branch: ConversationBranch?, events: List<HarnessEvent>, running: Boolean,
    onRunMode: () -> Unit, onBranches: () -> Unit, onRuntime: () -> Unit, onTools: () -> Unit,
    toolchainHighlight: Boolean, onReasoning: (() -> Unit)? = null, onFloatingChat: (() -> Unit)? = null,
    speech: ChatSpeechToggle? = null,
) {
    val rounds = events.count { it is HarnessEvent.ProviderRoundStarted }
    val actions = buildList {
        speech?.let { toggle -> add(WorkbenchAction(
            if (toggle.enabled) RuntimeIconName.VolumeOn else RuntimeIconName.VolumeOff,
            stringResource(if (toggle.enabled) R.string.chat_speech_disable else R.string.chat_speech_enable),
            onClick = toggle.onToggle, highlighted = toggle.enabled)) }
        add(WorkbenchAction(runModeIcon(runMode), stringResource(R.string.chat_menu_run_mode),
            stringResource(if (runMode == RunMode.PLAN) R.string.chat_run_mode_plan else R.string.chat_run_mode_build), onRunMode))
        add(WorkbenchAction(RuntimeIconName.GitBranch, stringResource(R.string.chat_branches_title),
            branch?.name ?: stringResource(R.string.chat_main_line), onBranches))
        add(WorkbenchAction(RuntimeIconName.Logs, stringResource(R.string.chat_runtime_overview),
            if (running) stringResource(R.string.chat_round_number, rounds)
            else stringResource(R.string.chat_menu_runtime_subtitle, events.size), onRuntime, highlighted = running))
        add(WorkbenchAction(RuntimeIconName.Wrench, stringResource(R.string.chat_toolchain_entry),
            if (toolchainHighlight) stringResource(R.string.chat_workbench_tools_attention) else null,
            onTools, highlighted = toolchainHighlight))
        if (onReasoning != null) add(WorkbenchAction(RuntimeIconName.Sparkles,
            stringResource(R.string.chat_reasoning_effort), onClick = onReasoning))
        if (onFloatingChat != null) add(WorkbenchAction(RuntimeIconName.OpenInNew,
            stringResource(R.string.chat_workbench_floating_window), onClick = onFloatingChat))
    }
    WorkbenchActionGrid(actions)
}

private data class WorkbenchAction(
    val icon: RuntimeIconName,
    val label: String,
    val detail: String? = null,
    val onClick: () -> Unit,
    val highlighted: Boolean = false,
)

@Composable
private fun WorkbenchActionGrid(actions: List<WorkbenchAction>) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    WorkbenchActionTile(action, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WorkbenchActionTile(action: WorkbenchAction, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    RuntimeCard(
        modifier = modifier,
        containerColor = if (action.highlighted) colors.secondaryContainer else colors.surfaceContainerLow,
        onClick = action.onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeIcon(action.icon, Modifier.size(22.dp),
                if (action.highlighted) colors.onSecondaryContainer else colors.primary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(action.label, style = MaterialTheme.typography.labelLarge,
                    color = if (action.highlighted) colors.onSecondaryContainer else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                action.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (action.highlighted) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

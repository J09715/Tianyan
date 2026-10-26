package top.tianyan.app.ui.chat

import androidx.compose.ui.draw.shadow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.session.ConversationBranch
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.chat.floating.rememberFloatingChatLauncher

/** Floating navigation and actions; advanced controls unfold on demand. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ChatTopBar(
    runMode: RunMode, currentBranch: ConversationBranch?,
    runtimeEvents: List<HarnessEvent>, running: Boolean,
    onOpenSessions: () -> Unit, onOpenBots: () -> Unit, onOpenWorkshop: () -> Unit,
    onOpenRunModes: () -> Unit, onOpenBranches: () -> Unit,
    onOpenRuntime: () -> Unit, onOpenBrowser: (() -> Unit)? = null, browserHighlight: Boolean = false,
    onOpenRepository: (() -> Unit)? = null, repositoryHighlight: Boolean = false,
    toolchainHighlight: Boolean = false, onOpenToolchain: () -> Unit,
    onOpenReasoning: (() -> Unit)? = null, companion: ChatCompanionToggle? = null,
    speech: ChatSpeechToggle? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val openFloatingChat = rememberFloatingChatLauncher()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (!LocalRuntimeWidthClass.current.hasPersistentNavigation) {
            RuntimeCard(modifier = Modifier.shadow(4.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f), spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f)), shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), contentPadding = PaddingValues(0.dp)) {
                RuntimeIconButton(onClick = onOpenSessions, modifier = Modifier.size(48.dp),
                    contentDescription = stringResource(R.string.chat_open_session_list)) {
                    RuntimeIcon(RuntimeIconName.Menu, Modifier.size(22.dp))
                }
            }
        } else Spacer(Modifier.width(48.dp))
        RuntimeCard(modifier = Modifier.shadow(4.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f), spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f)), shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), contentPadding = PaddingValues(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeIconButton(onClick = onOpenBots, modifier = Modifier.size(48.dp),
                    contentDescription = stringResource(MainDestination.Bots.labelRes)) {
                    RuntimeIcon(MainDestination.Bots.icon, Modifier.size(22.dp))
                }
                RuntimeIconButton(onClick = onOpenWorkshop, modifier = Modifier.size(48.dp),
                    contentDescription = stringResource(MainDestination.Workspace.labelRes)) {
                    RuntimeIcon(MainDestination.Workspace.icon, Modifier.size(22.dp))
                }
                RuntimeIconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp),
                    contentDescription = stringResource(R.string.chat_workbench_controls)) {
                    RuntimeIcon(RuntimeIconName.More, Modifier.size(22.dp),
                        if (toolchainHighlight || repositoryHighlight || browserHighlight) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (expanded) {
        val closeThen: (() -> Unit) -> () -> Unit = { action -> { expanded = false; action() } }
        PlainBackdropModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_workbench_controls), style = MaterialTheme.typography.titleLarge)
                Row {
                    companion?.let { toggle -> RuntimeIconButton(onClick = closeThen(toggle.onToggle),
                        contentDescription = stringResource(if (toggle.open) top.tianyan.app.feature.components.R.string.components_companion_collapse
                            else top.tianyan.app.feature.components.R.string.components_companion_open)) {
                        RuntimeIcon(RuntimeIconName.PanelRightOpen, Modifier.size(24.dp))
                    } }
                }
                ChatWorkspaceBar(
                    onOpenBrowser = onOpenBrowser?.let(closeThen), browserHighlight = browserHighlight,
                    onOpenRepository = onOpenRepository?.let(closeThen), repositoryHighlight = repositoryHighlight)
                Text(stringResource(R.string.chat_workbench_session), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChatWorkbenchControls(runMode, currentBranch, runtimeEvents, running,
                    closeThen(onOpenRunModes), closeThen(onOpenBranches),
                    closeThen(onOpenRuntime), closeThen(onOpenToolchain), toolchainHighlight,
                    onReasoning = onOpenReasoning?.let(closeThen), onFloatingChat = openFloatingChat?.let(closeThen),
                    speech = speech?.copy(onToggle = closeThen(speech.onToggle)))
            }
        }
    }
}

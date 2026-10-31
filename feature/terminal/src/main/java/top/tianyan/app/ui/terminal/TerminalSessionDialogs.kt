package top.tianyan.app.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.tianyan.app.feature.terminal.R
import top.tianyan.app.runtime.DistributionCatalog
import top.tianyan.app.runtime.terminal.TerminalSessionHandle
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

@Composable
internal fun SessionListDialog(
    handles: List<TerminalSessionHandle>,
    activeId: String?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    onClose: (String) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_sessions)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.terminal_active_sessions, handles.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (handles.size == 1) RuntimeCard(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.terminal_only_session_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                handles.forEach { handle ->
                    val active = handle.id == activeId
                    val distroName = runCatching { DistributionCatalog.require(handle.distributionId).displayName }
                        .getOrDefault(handle.distributionId)
                    val contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth().semantics { selected = active },
                        containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(16.dp),
                        onClick = { onSwitch(handle.id) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(24.dp), contentColor)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(handle.label, style = MaterialTheme.typography.titleMedium, color = contentColor,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(distroName, style = MaterialTheme.typography.labelMedium, color = contentColor,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(handle.workingDirectory, style = MaterialTheme.typography.bodySmall,
                                    color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (active) Text(stringResource(R.string.terminal_current),
                                    style = MaterialTheme.typography.labelLarge, color = contentColor)
                            }
                            RuntimeIconButton(onClick = { onClose(handle.id) }, modifier = Modifier.size(48.dp),
                                contentDescription = stringResource(if (handles.size == 1) R.string.terminal_reset_session_desc else R.string.terminal_close_session_desc)) {
                                RuntimeIcon(if (handles.size == 1) RuntimeIconName.PowerSettingsNew else RuntimeIconName.Close,
                                    Modifier.size(24.dp), contentColor)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCreate) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.terminal_new))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_close)) } },
    )
}

@Composable
internal fun CloseSessionConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.terminal_close_confirm_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.terminal_close_confirm_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) stringResource(R.string.terminal_close_confirm_countdown, countdown)
                    else stringResource(R.string.terminal_close_confirm),
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
        },
    )
}


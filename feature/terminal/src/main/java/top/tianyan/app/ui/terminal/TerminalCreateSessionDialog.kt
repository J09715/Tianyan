package top.tianyan.app.ui.terminal
import top.tianyan.app.ui.components.RuntimeAlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.terminal.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.runtime.WorkspaceProject

@Composable
internal fun CreateTerminalDialog(
    workspaces: List<WorkspaceProject>,
    installedDistros: List<InstalledDistro> = emptyList(),
    nextSessionIndex: Int,
    onDismiss: () -> Unit,
    onCreate: (label: String, workingDirectory: String, distroId: String?) -> Unit,
) {
    val defaultLabel = stringResource(R.string.terminal_default_label, nextSessionIndex)
    var label by remember(nextSessionIndex) { mutableStateOf(defaultLabel) }
    var selectedDir by remember { mutableStateOf("/root") }
    var selectedDistroId by remember(installedDistros) { mutableStateOf(installedDistros.firstOrNull { it.isActive }?.id ?: installedDistros.firstOrNull()?.id) }
    val quickLabels = listOf(
        stringResource(R.string.terminal_quick_main),
        stringResource(R.string.terminal_quick_build),
        stringResource(R.string.terminal_quick_service),
        stringResource(R.string.terminal_quick_git),
        stringResource(R.string.terminal_quick_python),
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.terminal_new_session), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (installedDistros.size > 1) {
                    Text(stringResource(R.string.terminal_target_distribution), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        installedDistros.forEach { d ->
                            TerminalChoiceChip(d.displayName, selectedDistroId == d.id) { selectedDistroId = d.id }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Text(stringResource(R.string.terminal_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                RuntimeFilledTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text(stringResource(R.string.terminal_label_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Quick session labels.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickLabels.forEach { tag ->
                        TerminalChoiceChip(tag, label == tag) { label = tag }
                    }
                }

                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(stringResource(R.string.terminal_initial_cwd), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalDirOption(
                        name = stringResource(R.string.terminal_root_directory), path = "/root",
                        selected = selectedDir == "/root", onSelect = { selectedDir = "/root" },
                    )
                    workspaces.forEach { ws ->
                        TerminalDirOption(name = ws.name, path = ws.linuxPath,
                            selected = selectedDir == ws.linuxPath, onSelect = { selectedDir = ws.linuxPath })
                    }
                }

            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(label.ifBlank { defaultLabel }, selectedDir, selectedDistroId) },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(stringResource(R.string.terminal_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
        },
    )
}

@Composable
private fun TerminalDirOption(name: String, path: String, selected: Boolean, onSelect: () -> Unit) {
    top.tianyan.app.ui.components.RuntimeCard(
        modifier = Modifier.fillMaxWidth(), onClick = onSelect, shape = MaterialTheme.shapes.large,
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeIcon(if (selected) RuntimeIconName.Check else RuntimeIconName.Folder, Modifier.size(24.dp),
                if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TerminalChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    top.tianyan.app.ui.components.RuntimeFilledTonalButton(
        onClick = onClick, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp),
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

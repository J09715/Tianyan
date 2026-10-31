package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*

@Composable
internal fun LocalBackupSettingsCard(first: Boolean = true, last: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    PreferenceSurface(first = first, last = last) {
        SettingsRow(RuntimeIconName.Save, stringResource(R.string.local_backup_title),
            stringResource(R.string.settings_material_backup_summary), onClick = { open = true }, tonalIcon = true)
    }
    if (open) LocalBackupDialog(onDismiss = { open = false })
}

@Composable
internal fun LocalBackupDialog(onDismiss: () -> Unit, viewModel: LocalBackupViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var restorePreferences by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restorePreferences = false; viewModel.preview(uri) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), viewModel::saveExport)
    LaunchedEffect(state.exportFile) {
        state.exportFile?.let { saver.launch("tianyan_backup.zip") }
    }
    RuntimeAlertDialog(
        onDismissRequest = { if (!state.busy && state.exportFile == null) { viewModel.clear(); onDismiss() } },
        title = { Text(stringResource(R.string.local_backup_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.local_backup_scope), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.local_backup_exclusions), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val preview = state.prepared?.preview
                if (preview != null) {
                    Text(stringResource(R.string.local_backup_preview), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.local_backup_counts,
                        preview.counts["harness_models"] ?: 0, preview.counts["harness_sessions"] ?: 0,
                        preview.counts["harness_entries"] ?: 0, preview.counts["agent_skills"] ?: 0,
                        preview.counts["workspaces"] ?: 0))
                    Text(stringResource(R.string.local_backup_files, preview.assetCount, preview.assetBytes / (1024 * 1024), preview.skippedRecords),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.local_backup_merge_detail), style = MaterialTheme.typography.bodySmall)
                    Row {
                        RuntimeCheckbox(checked = restorePreferences, enabled = !state.busy, onCheckedChange = { restorePreferences = it })
                        Text(stringResource(R.string.local_backup_preferences, preview.preferenceCount),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                } else {
                    RuntimeOutlinedButton(onClick = viewModel::export, enabled = !state.busy && state.exportFile == null,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_backup_export)) }
                    RuntimeOutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !state.busy && state.exportFile == null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.local_backup_choose))
                    }
                }
                if (state.busy) RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.completed) Text(stringResource(R.string.local_backup_completed), color = MaterialTheme.colorScheme.primary)
                if (state.error) Text(stringResource(state.errorMessage), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (state.prepared != null) RuntimeButton(enabled = !state.busy, onClick = { viewModel.restore(restorePreferences) }) {
                Text(stringResource(R.string.local_backup_restore))
            }
        },
        dismissButton = {
            RuntimeTextButton(enabled = !state.busy && state.exportFile == null, onClick = {
                if (state.prepared != null) viewModel.clear() else { viewModel.clear(); onDismiss() }
            }) { Text(stringResource(if (state.prepared == null) R.string.local_backup_close else R.string.local_backup_back)) }
        },
    )
}

package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.tools.LocalPluginImportState
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun LocalPluginImportConfirmation(pending: LocalPluginImportState.PendingConfirmation, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val manifest = pending.manifest
    RuntimeAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.package_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${manifest.name} · ${manifest.version}", style = MaterialTheme.typography.titleMedium)
                Text(manifest.description)
                Text("Schema ${manifest.schemaVersion} · ${manifest.architectures.joinToString()}")
                manifest.plugin?.let { spec ->
                    Text(spec.id)
                    Text("PROCESS · SDK ${spec.api.minimum}–${spec.api.maximum}")
                    spec.harness?.let { harness ->
                        Text(stringResource(R.string.package_harness_extensions, harness.entry, harness.tools.size, harness.providers.size, harness.nodes.size))
                        if (harness.providers.isNotEmpty()) Text(stringResource(R.string.package_harness_provider_scope))
                    }
                    if (spec.dependencies.isNotEmpty()) {
                        Text(stringResource(R.string.package_dependencies))
                        spec.dependencies.forEach { Text("${it.id} ≥ ${it.minimumVersion}" + (it.maximumVersionExclusive?.let { upper -> " < $upper" } ?: "")) }
                    }
                    if (spec.resources.isNotEmpty()) {
                        Text(stringResource(R.string.package_resources))
                        spec.resources.forEach { Text("${it.key} · ${it.path} · ${it.sharing}") }
                    }
                }
                Text(stringResource(R.string.package_new_capabilities))
                Text(pending.addedCapabilities.joinToString("\n").ifEmpty { stringResource(R.string.package_none) })
                Text(stringResource(R.string.package_confirm_scope), style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { RuntimeTextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = { RuntimeButton(onClick = onConfirm) { Text(stringResource(R.string.package_install)) } },
    )
}

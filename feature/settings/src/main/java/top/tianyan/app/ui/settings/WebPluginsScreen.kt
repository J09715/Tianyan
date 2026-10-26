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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*

@Composable
fun WebPluginsScreen(onOpen: (String, String, String) -> Unit, onBack: () -> Unit, model: WebPluginsViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val packages by model.packages.collectAsStateWithLifecycle()
    var removal by remember { mutableStateOf<ToolManifest?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::preview) }
    RuntimeSettingsScaffold(stringResource(R.string.web_packages_title), onBack,
        actions = {
            RuntimeTextButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !state.busy && state.pending == null) {
                Text(stringResource(R.string.web_packages_import))
            }
        },
    ) {
        item { Text(stringResource(R.string.web_packages_scope), style = MaterialTheme.typography.bodyMedium) }
        if (state.busy) item { Text(stringResource(R.string.web_packages_busy)) }
        if (state.failed) item {
            Text(stringResource(if (state.bootstrapTooLarge) R.string.web_packages_proot_too_large else R.string.web_packages_failed), color = MaterialTheme.colorScheme.error)
            RuntimeTextButton(onClick = model::refresh, enabled = !state.busy) { Text(stringResource(R.string.web_packages_retry)) }
        }
        if (packages.isEmpty() && !state.busy) item { Text(stringResource(R.string.web_packages_empty)) }
        packages.forEach { manifest -> item(manifest.id) {
            RuntimeCard(Modifier.fillMaxWidth()) {
                Text(manifest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${manifest.plugin!!.id} · ${manifest.version}", style = MaterialTheme.typography.bodySmall)
                Text(manifest.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (manifest.plugin!!.webSource == "PROOT_STATIC") Text(stringResource(R.string.web_packages_proot_label), style = MaterialTheme.typography.labelMedium)
                manifest.plugin!!.serviceToolId?.let { Text(stringResource(R.string.web_packages_service_label, it), style = MaterialTheme.typography.labelMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeTextButton(onClick = { onOpen(manifest.id, manifest.version, manifest.plugin!!.id) }, enabled = !state.busy) { Text(stringResource(R.string.web_packages_open)) }
                    RuntimeTextButton(onClick = { removal = manifest }, enabled = !state.busy) { Text(stringResource(R.string.web_packages_remove)) }
                }
            }
        } }
    }
    state.pending?.let { pending ->
        val manifest = pending.preview.manifest
        RuntimeAlertDialog(
            onDismissRequest = model::cancelPreview,
            title = { Text(stringResource(R.string.web_packages_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${manifest.name}\n${manifest.plugin!!.id} · ${manifest.version}")
                    Text(manifest.description)
                    Text(stringResource(R.string.web_packages_confirmation))
                    if (manifest.plugin!!.webSource == "PROOT_STATIC") Text(stringResource(R.string.web_packages_proot_source))
                    manifest.plugin!!.serviceToolId?.let { Text(stringResource(R.string.web_packages_service_source, it)) }
                    if (manifest.plugin!!.capabilities.isEmpty()) Text(stringResource(R.string.web_packages_no_capabilities))
                    manifest.plugin!!.capabilities.forEach { capability ->
                        Text(stringResource(
                            when (capability.name) {
                                "CLIPBOARD_WRITE" -> R.string.web_packages_clipboard_write
                                "INSTALL_APK" -> R.string.web_packages_install_apk_detail
                                "LOGCAT" -> R.string.web_packages_logcat_detail
                                else -> R.string.web_packages_app_info
                            },
                        ))
                    }
                }
            },
            confirmButton = { RuntimeTextButton(onClick = { model.confirmImport(pending) }, enabled = !state.busy) { Text(stringResource(R.string.web_packages_import)) } },
            dismissButton = { RuntimeTextButton(onClick = model::cancelPreview, enabled = !state.busy) { Text(stringResource(R.string.web_packages_cancel)) } },
        )
    }
    removal?.let { expected ->
        RuntimeAlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.web_packages_remove_title)) },
            text = { Text(stringResource(R.string.web_packages_remove_detail, expected.name)) },
            confirmButton = { RuntimeTextButton(onClick = { removal = null; model.remove(expected) }, enabled = !state.busy) { Text(stringResource(R.string.web_packages_remove)) } },
            dismissButton = { RuntimeTextButton(onClick = { removal = null }) { Text(stringResource(R.string.web_packages_cancel)) } },
        )
    }
}

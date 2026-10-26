package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.settings.R
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.packages.PackagedPluginEntry
import top.tianyan.app.ui.components.*

@Composable
fun PackagedPluginInventorySection(onOpenTool: (String) -> Unit, viewModel: PackagedPluginsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var installedOnly by rememberSaveable { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<PackagedPluginEntry?>(null) }
    val entries = if (installedOnly) state.entries.filter { it.installedVersion != null } else state.entries
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.harness_plugins_title), stringResource(R.string.harness_plugins_scope),
            modifier = Modifier.padding(top = 12.dp))
        RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            viewModel.harnessPlugins.forEachIndexed { index, plugin ->
                if (index > 0) InventoryDivider()
                val points = plugin.extensions.groupingBy { it.point }.eachCount()
                RuntimePluginListRow(
                    title = if (plugin.id == "tianyan.harness") stringResource(R.string.harness_plugins_core_title) else plugin.id,
                    subtitle = plugin.id,
                    icon = RuntimeIconName.Brain,
                    detail = stringResource(R.string.harness_plugins_counts, points[PluginExtensionPoint.TOOL] ?: 0,
                        points[PluginExtensionPoint.CONTEXT] ?: 0, points[PluginExtensionPoint.PROVIDER] ?: 0, points[PluginExtensionPoint.WORKFLOW] ?: 0),
                    status = plugin.version,
                )
            }
        }
        SectionHeader(stringResource(R.string.packaged_plugins_title), stringResource(R.string.packaged_plugins_scope),
            modifier = Modifier.padding(top = 12.dp))
        RuntimeSegmentedControl(
            options = listOf(stringResource(R.string.packaged_plugins_filter_installed), stringResource(R.string.packaged_plugins_filter_all)),
            selectedIndex = if (installedOnly) 0 else 1, onSelect = { installedOnly = it == 0 }, modifier = Modifier.fillMaxWidth())
        if (state.failed) Text(stringResource(R.string.packaged_plugins_failed), color = MaterialTheme.colorScheme.error)
        if (entries.isEmpty() && !state.failed) Text(stringResource(R.string.packaged_plugins_empty),
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isNotEmpty()) RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) InventoryDivider()
                val status = when (entry.state) {
                    "UPDATE_AVAILABLE" -> R.string.packaged_plugins_update
                    "INSTALLING" -> R.string.packaged_plugins_installing
                    "FAILED" -> R.string.packaged_plugins_check
                    "DISABLED" -> R.string.packaged_plugins_disabled
                    else -> if (entry.installedVersion != null) R.string.packaged_plugins_installed else R.string.packaged_plugins_uninstalled
                }
                RuntimePluginListRow(title = entry.name, icon = RuntimeIconName.Package,
                    subtitle = stringResource(R.string.packaged_plugins_display_version, entry.installedVersion ?: entry.descriptor.version),
                    status = stringResource(status), onClick = { onOpenTool(entry.toolId) },
                    onLongClick = { diagnostics = entry })
            }
        }
        val diagnostic = diagnostics
        if (diagnostic != null) {
            val logs = remember(diagnostic.toolId) { viewModel.installLogs(diagnostic.toolId) }
            PackagedPluginDiagnosticsDialog(diagnostic, logs, onDismiss = { diagnostics = null })
        }
    }
}

@Composable
private fun InventoryDivider() = HorizontalDivider(Modifier.padding(start = 68.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

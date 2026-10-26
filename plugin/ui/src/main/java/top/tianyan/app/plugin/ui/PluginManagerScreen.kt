package top.tianyan.app.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.runtime.PluginController
import top.tianyan.app.plugin.runtime.PluginPhase
import top.tianyan.app.ui.components.*

@Composable
fun PluginManagerScreen(
    plugins: List<NativePagePlugin>, controller: PluginController,
    onOpen: (PluginExtensionRef) -> Unit, onBack: () -> Unit,
    packageContent: @Composable () -> Unit = {},
) {
    val states by controller.states.collectAsState()
    val initialized by controller.initialized.collectAsState()
    val preferenceFailure by controller.preferenceFailure.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { action() } finally { busy = false } }
    }
    Scaffold(topBar = { RuntimeTopBar(stringResource(R.string.ui_plugin_manager_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader(stringResource(R.string.ui_plugin_pages_title), stringResource(R.string.ui_plugin_pages_description)) }
            if (preferenceFailure) item {
                Text(stringResource(R.string.ui_plugin_preferences_failed), color = MaterialTheme.colorScheme.error)
                if (!initialized) RuntimeTextButton(onClick = { perform { controller.initialize() } }, enabled = !busy) {
                    Text(stringResource(R.string.ui_plugin_retry))
                }
            }
            items(plugins, key = { it.descriptor.id }) { plugin ->
                val status = states.getValue(plugin.descriptor.id)
                val title = plugin.title()
                RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                            RuntimeIcon(plugin.icon, Modifier.padding(12.dp).size(22.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val description = plugin.description()
                            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        RuntimeSwitch(modifier = Modifier.semantics { contentDescription = title },
                            checked = status.selected, onCheckedChange = { enabled -> perform { controller.setEnabled(plugin.descriptor.id, enabled) } },
                            enabled = initialized && !busy && status.phase !in setOf(PluginPhase.STARTING, PluginPhase.DRAINING))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(phaseLabel(status.phase), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = if (status.phase == PluginPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (status.phase == PluginPhase.FAILED) RuntimeTextButton(
                            onClick = { perform { controller.setEnabled(plugin.descriptor.id, status.selected) } }, enabled = !busy,
                        ) { Text(stringResource(R.string.ui_plugin_retry)) }
                        if (status.phase == PluginPhase.RUNNING) {
                            plugin.entries.distinctBy { it.pageId }.forEach { entry ->
                                RuntimeTextButton(onClick = { onOpen(PluginExtensionRef(plugin.descriptor.id, entry.pageId)) }) {
                                    Text(stringResource(R.string.ui_plugin_open), maxLines = 1)
                                    RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.ui_plugin_manager_scope), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item { packageContent() }
        }
    }
}

@Composable
private fun phaseLabel(phase: PluginPhase): String = stringResource(when (phase) {
    PluginPhase.READY -> R.string.ui_plugin_ready
    PluginPhase.STARTING -> R.string.ui_plugin_starting
    PluginPhase.RUNNING -> R.string.ui_plugin_running
    PluginPhase.DRAINING -> R.string.ui_plugin_draining
    PluginPhase.STOPPED -> R.string.ui_plugin_stopped
    PluginPhase.BLOCKED -> R.string.ui_plugin_blocked
    PluginPhase.FAILED -> R.string.ui_plugin_failed
})

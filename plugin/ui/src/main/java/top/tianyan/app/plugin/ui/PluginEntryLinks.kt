package top.tianyan.app.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.runtime.PluginController
import top.tianyan.app.plugin.runtime.PluginPhase
import top.tianyan.app.ui.components.RuntimeNavigationTile

/** Entries share the drawer's destination grid and disappear when admission closes. */
@Composable
fun PluginEntryLinks(plugins: List<NativePagePlugin>, controller: PluginController,
    location: PluginEntryLocation, onOpen: (PluginExtensionRef) -> Unit, selectedPage: PluginExtensionRef? = null) {
    val states by controller.states.collectAsState()
    val entries = plugins.filter { states[it.descriptor.id]?.phase == PluginPhase.RUNNING }
        .flatMap { plugin -> plugin.entries.filter { it.location == location }.map { plugin to it } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (plugin, entry) ->
                    RuntimeNavigationTile(entry.title(), plugin.icon,
                        { onOpen(PluginExtensionRef(plugin.descriptor.id, entry.pageId)) }, Modifier.weight(1f),
                        active = selectedPage == PluginExtensionRef(plugin.descriptor.id, entry.pageId))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

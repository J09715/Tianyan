package top.tianyan.app.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.ui.components.RuntimeTextButton

/** Resolves a page without owning a navigation stack or introducing a new ViewModel owner. */
@Composable
fun PluginPageHost(
    pages: PluginPages,
    reference: PluginExtensionRef,
    arguments: Map<String, String> = emptyMap(),
    embedded: Boolean = false,
    onBack: () -> Unit,
) {
    val controller = LocalPluginController.current
    val lease = if (controller != null) {
        val states by controller.states.collectAsState()
        val status = states[reference.pluginId]
        rememberPluginLease(controller, reference.pluginId, status?.phase, status?.generation ?: 0)
    } else null
    val page = pages.resolve(reference).takeIf { controller == null || lease != null }
    if (page != null) {
        key(reference, lease?.activation?.generation) {
            page.content(PluginPageRequest(arguments, embedded, onBack, lease?.activation))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ui_plugin_page_unavailable), style = MaterialTheme.typography.bodyLarge)
            if (!embedded) {
                RuntimeTextButton(onClick = onBack) { Text(stringResource(R.string.ui_plugin_page_back)) }
            }
        }
    }
}

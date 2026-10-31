package top.tianyan.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeFilledTonalButton

/** Full upstream web apps use their own browser origin and authentication, outside the page bridge. */
@Composable
internal fun ServiceBrowserButton(port: Int?, running: Boolean) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.service_browser_unavailable)
    RuntimeOutlinedButton(enabled = running && port != null, onClick = {
        val safePort = port?.takeIf { it in 1..65535 } ?: return@RuntimeOutlinedButton
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$safePort/")))
        }.onFailure { Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show() }
    }) { Text(stringResource(R.string.service_browser_open)) }
}

/** A loopback service owns its login; the host must not fabricate token query parameters. */
internal fun hasSelfManagedLoopbackPanel(manifest: ToolManifest?): Boolean =
    manifest?.servicePort?.let { port -> manifest.plugin?.capabilities?.any {
        it.name == "LOCAL_WEB" && it.target == "loopback:$port"
    } } == true

internal fun hasAuthenticatedServicePanel(manifest: ToolManifest?): Boolean =
    manifest?.plugin?.capabilities?.any { it.name == "LOCAL_WEB" && it.target == "authenticated-v1" } == true

@Composable
internal fun SelfManagedServiceAccessCard(port: Int?, running: Boolean, nativePanel: Boolean, onOpen: () -> Unit) {
    if (port == null || port !in 1..65535) return
    SettingsDetailCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.service_panel_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.service_panel_auth), style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text("http://127.0.0.1:$port/", style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.service_panel_loopback), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            RuntimeFilledTonalButton(enabled = running && nativePanel, onClick = onOpen) {
                Text(stringResource(R.string.service_panel_open))
            }
            if (!nativePanel) Text(stringResource(R.string.service_panel_upgrade), style = MaterialTheme.typography.bodySmall)
            ServiceBrowserButton(port, running)
        }
    }
}

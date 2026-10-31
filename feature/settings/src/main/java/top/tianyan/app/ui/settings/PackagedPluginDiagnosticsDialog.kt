package top.tianyan.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.feature.settings.R
import top.tianyan.app.plugin.packages.PackagedPluginEntry
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun PackagedPluginDiagnosticsDialog(
    entry: PackagedPluginEntry,
    logs: Flow<List<InstallLogEntity>>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val collected by logs.collectAsStateWithLifecycle(initialValue = emptyList<InstallLogEntity>())
    val report = remember(entry, collected) { buildPluginDiagnostics(entry, collected) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packaged_plugins_diagnostics_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = report,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Tianyan plugin diagnostics", report))
                Toast.makeText(context, R.string.packaged_plugins_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.packaged_plugins_diagnostics_copy)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.packaged_plugins_close)) }
        },
    )
}

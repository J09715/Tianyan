package top.tianyan.app.ui.developer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.tianyan.app.runtime.remote.RemoteSystemService
import top.tianyan.app.ui.components.*

@Composable
internal fun RemoteCommandPanel(serverId: String, service: RemoteSystemService) {
    var command by remember { mutableStateOf("cat /etc/os-release") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val target = runCatching { service.server(serverId) }.getOrNull()
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("服务器命令", style = MaterialTheme.typography.titleMedium)
            Text("命令在当前服务器的工作目录执行，使用连接用户的实际权限。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            RuntimeFilledTextField(command, { command = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("命令") }, maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            RuntimeButton(enabled = !busy && command.isNotBlank(), onClick = {
                val snapshot = command
                scope.launch {
                    busy = true; output = ""
                    try { output = service.execute(serverId, snapshot, target) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { output = "执行失败，请检查服务器连接和权限" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "执行中…" else "执行") }
            if (busy) RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (output.isNotBlank()) Text(output, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

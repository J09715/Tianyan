package top.tianyan.app.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.*

@Composable
internal fun TerminalRestoreWarnings(viewModel: TerminalViewModel?) {
    if (viewModel == null) return
    val failures by viewModel.restoreFailures.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    if (failures.isEmpty()) return
    RuntimeTextButton(onClick = { expanded = true }) { Text("${failures.size} 个终端未恢复 · 查看记录") }
    if (expanded) RuntimeAlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text("未恢复的终端") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已保留原连接身份。这些终端不会改连其它服务器；移除记录仅删除本机的终端配置。", style = MaterialTheme.typography.bodySmall)
                RuntimeTextButton(onClick = viewModel::retryFailedRestorations) { Text("重试恢复") }
                failures.forEach { failure ->
                    Text(failure.label, style = MaterialTheme.typography.titleSmall)
                    Text(failure.message, style = MaterialTheme.typography.bodySmall)
                    RuntimeTextButton(onClick = { viewModel.removeFailedRestoration(failure.id) }) { Text("移除记录") }
                }
            }
        },
        confirmButton = { RuntimeTextButton(onClick = { expanded = false }) { Text("关闭") } },
    )
}

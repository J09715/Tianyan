package top.tianyan.app.ui.workflow

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowDefinitionExport
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun WorkflowExportButton(definition: WorkflowDefinition, isDirty: Boolean) {
    var snapshot by remember { mutableStateOf<Pair<WorkflowDefinition, Boolean>?>(null) }
    RuntimeOutlinedButton(onClick = { snapshot = definition to isDirty }, modifier = Modifier.fillMaxWidth()) { Text("导出 JSON", maxLines = 1) }
    snapshot?.let { (source, dirty) -> WorkflowExportDialog(source, dirty, onDismiss = { snapshot = null }) }
}

@Composable
private fun WorkflowExportDialog(definition: WorkflowDefinition, isDirty: Boolean, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var raw by remember(definition) { mutableStateOf<String?>(null) }
    var error by remember(definition) { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(definition) {
        val result = withContext(Dispatchers.Default) { runCatching { WorkflowDefinitionExport.encode(definition) } }
        raw = result.getOrNull()
        error = result.exceptionOrNull()?.message
    }
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text("导出工作流 JSON") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isDirty) "导出当前已应用的编辑草稿，包含未保存修改。" else "导出当前编辑器中的工作流定义。")
            Text("JSON 包含节点配置和默认变量，复制前可在这里核对内容；再次导入会创建新的手动工作流。", style = MaterialTheme.typography.bodySmall)
            raw?.let { text ->
                Text("${definition.nodes.size} 个节点 · ${definition.edges.size} 条连接 · ${text.length} 个字符", style = MaterialTheme.typography.labelSmall)
                RuntimeFilledTextField(value = text.take(24_000), onValueChange = {}, readOnly = true,
                    label = { Text("JSON 预览") }, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                if (text.length > 24_000) Text("预览展示前 24000 个字符，“复制完整 JSON”会复制全部内容。", style = MaterialTheme.typography.bodySmall)
            }
            if (raw == null && error == null) Text("正在生成…", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (copied) Text("完整 JSON 已复制", style = MaterialTheme.typography.bodySmall)
            WorkflowSaveDocumentButton(raw, definition.name)
        }
    }, dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("关闭", maxLines = 1) } }, confirmButton = {
        RuntimeButton(enabled = raw != null && !copying, onClick = {
            val text = raw ?: return@RuntimeButton
            copying = true
            scope.launch {
                try {
                    val clip = ClipData.newPlainText("workflow_definition", text).apply {
                        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    }
                    clipboard.setClipEntry(ClipEntry(clip))
                    copied = true
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "复制失败，请重试" }
                finally { copying = false }
            }
        }) { Text(if (copying) "正在复制" else "复制完整 JSON", maxLines = 1) }
    })
}

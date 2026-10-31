package top.tianyan.app.ui.workflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun WorkflowCatalogEntry(onCreate: () -> Unit, onImport: (WorkflowDefinition) -> Unit) {
    var importing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("把构建、诊断、逆向与 Git 操作编排成可观察、可暂停的 DAG。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeButton(onClick = onCreate, modifier = Modifier.weight(1f)) { Text("新建", maxLines = 1) }
            RuntimeOutlinedButton(onClick = { importing = true }, modifier = Modifier.weight(1f)) { Text("导入 JSON", maxLines = 1) }
        }
    }
    if (importing) WorkflowImportDialog(onDismiss = { importing = false }, onImport = { definition ->
        importing = false
        onImport(definition)
    })
}

@Composable
private fun WorkflowImportDialog(onDismiss: () -> Unit, onImport: (WorkflowDefinition) -> Unit) {
    var raw by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) reading = false
        else scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    val stream = resolver.openInputStream(uri) ?: error("无法打开所选 JSON 文件")
                    WorkflowDocumentTransfer.read(stream) { coroutine.ensureActive() }
                }
                require(text.isNotBlank()) { "JSON 文件为空" }
                if (raw != text) {
                    preview = null
                    error = null
                    raw = text
                    checking = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fileError = error.message ?: "读取 JSON 文件失败" }
            finally { reading = false }
        }
    }
    LaunchedEffect(raw) {
        val input = raw
        preview = null
        error = null
        if (input.isBlank()) { checking = false; return@LaunchedEffect }
        checking = true
        delay(250)
        val result = withContext(Dispatchers.Default) { runCatching { WorkflowDefinitionImport.parse(input) } }
        if (raw != input) return@LaunchedEffect
        preview = result.getOrNull()
        error = result.exceptionOrNull()?.message
        checking = false
    }
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text("导入工作流 JSON") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择 UTF-8 JSON 文件或粘贴完整定义，校验后作为新工作流进入编辑器，再保存和运行。导入采用手动触发，节点 ID 与变量引用保持原样。")
            RuntimeOutlinedButton(enabled = !reading, modifier = Modifier.fillMaxWidth(), onClick = {
                if (reading) return@RuntimeOutlinedButton
                reading = true
                fileError = null
                try { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                catch (_: Exception) { reading = false; fileError = "无法打开系统文件选择窗口" }
            }) { Text(if (reading) "正在读取…" else "选择 JSON 文件", maxLines = 1) }
            RuntimeFilledTextField(value = raw, onValueChange = { if (!reading) { raw = it; preview = null; error = null; fileError = null; checking = it.isNotBlank() } },
                enabled = !reading, label = { Text("工作流 JSON") }, minLines = 4, maxLines = 8, isError = error != null || fileError != null,
                modifier = Modifier.fillMaxWidth())
            if (checking) Text("正在校验…", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            fileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            preview?.let { Text("${it.name}\n${it.nodes.size} 个节点 · ${it.edges.size} 条连接 · 最大执行并发 ${it.maxParallelNodes}",
                style = MaterialTheme.typography.bodyMedium) }
        }
    }, dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("取消", maxLines = 1) } },
        confirmButton = { RuntimeButton(enabled = preview != null && !checking && !reading && fileError == null,
            onClick = { if (!checking && !reading && fileError == null) preview?.let(onImport) }) { Text("导入并编辑", maxLines = 1) } })
}

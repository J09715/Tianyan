package top.tianyan.app.ui.workflow

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.ui.components.*

@Composable
internal fun WorkflowStructuredResultButton(result: JsonObject) {
    var snapshot by remember { mutableStateOf<JsonObject?>(null) }
    RuntimeOutlinedButton(onClick = { snapshot = result }, modifier = Modifier.fillMaxWidth()) {
        Text("查看结构化结果", maxLines = 1)
    }
    snapshot?.let { source -> WorkflowStructuredResultDialog(source) { snapshot = null } }
}

@Composable
private fun WorkflowStructuredResultDialog(result: JsonObject, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var raw by remember(result) { mutableStateOf<String?>(null) }
    var pages by remember(result) { mutableStateOf(emptyList<String>()) }
    var page by remember(result) { mutableIntStateOf(0) }
    var error by remember(result) { mutableStateOf<String?>(null) }
    var copying by remember(result) { mutableStateOf(false) }
    var copied by remember(result) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    LaunchedEffect(result) {
        try {
            val document = withContext(Dispatchers.Default) {
                val text = WorkflowResultDocument.encode(result)
                text to WorkflowResultDocument.pages(text)
            }
            pages = document.second
            raw = document.first
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法生成结果 JSON" }
    }
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text("结构化结果 JSON") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("查看打开窗口时的结果。复制和保存包含完整 JSON；结果文件用于核对和分析，不是工作流定义。", style = MaterialTheme.typography.bodySmall)
            raw?.let { text ->
                Text("${text.length} 个字符 · 第 ${page + 1} / ${pages.size} 页", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeOutlinedButton(enabled = page > 0, onClick = { if (page > 0) page-- }, modifier = Modifier.weight(1f)) { Text("上一页", maxLines = 1) }
                    RuntimeOutlinedButton(enabled = page < pages.lastIndex, onClick = { if (page < pages.lastIndex) page++ }, modifier = Modifier.weight(1f)) { Text("下一页", maxLines = 1) }
                }
                key(page) {
                    SelectionContainer { Text(pages[page], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                WorkflowSaveDocumentButton(text, "workflow_result")
            }
            if (raw == null && error == null) Text("正在生成…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (copied) Text("完整结果 JSON 已复制", style = MaterialTheme.typography.bodySmall)
        }
    }, dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("关闭", maxLines = 1) } }, confirmButton = {
        RuntimeButton(enabled = raw != null && !copying, onClick = {
            if (copying) return@RuntimeButton
            val text = raw ?: return@RuntimeButton
            copying = true
            copied = false
            scope.launch {
                try {
                    val clip = ClipData.newPlainText("workflow_result", text).apply {
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

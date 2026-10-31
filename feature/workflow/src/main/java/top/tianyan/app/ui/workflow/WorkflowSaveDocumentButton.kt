package top.tianyan.app.ui.workflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun WorkflowSaveDocumentButton(raw: String?, name: String) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending
        pending = null
        if (uri == null) writing = false
        else if (text == null) { writing = false; fileError = "导出预览已失效，请重新导出" }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    val stream = resolver.openOutputStream(uri, "wt") ?: error("无法打开所选目标文件")
                    WorkflowDocumentTransfer.write(stream, text) { coroutine.ensureActive() }
                }
                saved = true
                fileError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fileError = error.message ?: "保存 JSON 文件失败" }
            finally { writing = false }
        }
    }
    RuntimeOutlinedButton(enabled = raw != null && !writing, modifier = Modifier.fillMaxWidth(), onClick = {
        if (writing) return@RuntimeOutlinedButton
        val text = raw ?: return@RuntimeOutlinedButton
        pending = text
        saved = false
        fileError = null
        writing = true
        try { picker.launch(WorkflowDocumentTransfer.fileName(name)) }
        catch (_: Exception) { pending = null; writing = false; fileError = "无法打开系统文件保存窗口" }
    }) { Text(if (writing) "正在保存…" else "保存为 JSON 文件", maxLines = 1) }
    if (saved) Text("完整 JSON 文件已保存")
    fileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

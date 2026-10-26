package top.tianyan.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.tianyan.app.runtime.environment.ExecutionFileEntry
import top.tianyan.app.runtime.remote.RemoteSystemService
import top.tianyan.app.ui.components.*

@Composable
internal fun RemoteWorkspaceScreen(serverId: String, onNavigate: (MainDestination) -> Unit, service: RemoteSystemService? = previewOrNull { koinInject() }, onBack: () -> Unit = { onNavigate(MainDestination.Agent) }) {
    var path by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember(path) { mutableStateOf(emptyList<ExecutionFileEntry>()) }
    var loading by remember(path) { mutableStateOf(true) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var createProject by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val target = service?.let { runCatching { it.server(serverId) }.getOrNull() }
    LaunchedEffect(path, refresh, service) {
        val current = service
        if (current == null) { loading = false; entries = emptyList(); message = null; return@LaunchedEffect }
        loading = true; entries = emptyList(); message = null
        try { entries = if (path.isEmpty()) current.projects(serverId, target) else current.list(serverId, path, target) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "无法读取服务器目录，请检查连接、工作目录和权限" }
        finally { loading = false }
    }
    BackHandler(path.isNotBlank()) { path = path.substringBeforeLast('/', "") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar(title = if (path.isBlank()) "工坊" else path.substringAfterLast('/'),
            statusText = target?.name ?: "当前服务器", onBack = { if (path.isNotBlank()) path = path.substringBeforeLast('/', "") else onBack() },
            actions = { RuntimeIconButton(onClick = { refresh++ }, contentDescription = "刷新") { RuntimeIcon(RuntimeIconName.Refresh) } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)), contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, PaddingValues(16.dp, 16.dp, 16.dp,
            24.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile(RuntimeIconName.FolderOpen, size = 40.dp, color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(if (path.isBlank()) "服务器项目" else "项目文件", style = MaterialTheme.typography.titleMedium)
                                Text((target?.workspace.orEmpty().trimEnd('/') + "/" + path).trimEnd('/'), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (path.isBlank()) RuntimeFilledTonalButton(onClick = { projectName = ""; message = null; createProject = true }) { Text("新建项目") }
                    }
                }
            }
            if (loading) item { RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!loading && entries.isEmpty() && message == null) item {
                Text(if (path.isBlank()) "工作目录中还没有项目，可在此新建项目或将已有工程放入服务器工作目录。" else "此目录为空。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(entries, key = { it.name }) { item ->
                RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), onClick = {
                    if (loading) return@RuntimeCard
                    val relative = listOf(path, item.name).filter { it.isNotEmpty() }.joinToString("/")
                    val parent = path
                    if (item.directory) path = relative
                    else scope.launch {
                        val current = service ?: return@launch
                        loading = true
                        try { val content = current.read(serverId, relative, target); if (path == parent) editor = relative to content }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = "无法打开文件，仅支持 1 MiB 内的 UTF-8 文本文件" }
                        finally { loading = false }
                    }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(if (item.directory) RuntimeIconName.Folder else RuntimeIconName.Code, size = 36.dp, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (item.directory) "浏览目录" else "${item.size / 1024} KB · 点击编辑", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeIcon(RuntimeIconName.ChevronRight, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (createProject) RuntimeAlertDialog(onDismissRequest = { if (!creating) createProject = false }, title = { Text("新建服务器项目") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将在当前服务器的工作目录下创建项目目录。")
            OutlinedTextField(projectName, { projectName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !creating, label = { Text("项目名称") })
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { RuntimeTextButton(enabled = !creating && projectName.isNotBlank(), onClick = {
            val snapshot = projectName.trim()
            scope.launch {
                val current = service ?: return@launch
                creating = true
                try { current.createProject(serverId, snapshot, target); createProject = false; refresh++ }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "创建失败，请检查名称是否有效、是否重复及目录权限" }
                finally { creating = false }
            }
        }) { Text(if (creating) "创建中…" else "创建") } },
        dismissButton = { RuntimeTextButton(enabled = !creating, onClick = { createProject = false }) { Text("取消") } })
    service?.let { current -> editor?.let { (file, original) -> RemoteFileEditor(serverId, file, original, current, target) { editor = null; refresh++ } } }
}

@Composable
private fun RemoteFileEditor(serverId: String, path: String, original: String, service: RemoteSystemService, target: top.tianyan.app.core.model.RemoteServer?, onDismiss: () -> Unit) {
    var content by remember(path, original) { mutableStateOf(original) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    RuntimeAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(path, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth(), maxLines = 14, minLines = 8,
                enabled = !busy, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { RuntimeTextButton(enabled = !busy && content != original, onClick = {
            val snapshot = content
            scope.launch {
                busy = true
                try { service.save(serverId, path, original, snapshot, target); onDismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "保存失败，文件可能已被外部修改，请重新打开并检查连接、权限" }
                finally { busy = false }
            }
        }) { Text(if (busy) "保存中…" else "保存到服务器") } },
        dismissButton = { RuntimeTextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } })
}

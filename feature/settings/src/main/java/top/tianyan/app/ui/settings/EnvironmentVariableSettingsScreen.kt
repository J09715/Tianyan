package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeSettingsScaffold
import top.tianyan.app.ui.theme.semanticColors
import top.tianyan.app.ui.components.SectionHeader
import top.tianyan.app.core.model.EnvironmentVariable
import top.tianyan.app.core.model.RuntimeState
@Composable
fun EnvironmentVariableSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val entries by viewModel?.environmentVariables?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val values by viewModel?.environmentValues?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val effectiveEntries by viewModel?.effectiveEnvironment?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val privacyMode by viewModel?.environmentPrivacyMode?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val loading by viewModel?.environmentLoading?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val error by viewModel?.environmentError?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val activeDistroId by viewModel?.activeDistroId?.collectAsStateWithLifecycle() ?: previewStateOf("ubuntu")
    val runtimeState by viewModel?.runtimeState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.RuntimeState.Ready)
    // 对象状态仅保存可 Bundle 化的字段，旋转后按 key/entry 恢复
    var showEditor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editingKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var editorInitialKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var editorInitialValue by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var deleteKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingKey?.let { key -> entries.firstOrNull { it.id == key } }
    val showDelete = deleteKey?.let { key -> entries.firstOrNull { it.id == key } }

    fun openEnvironmentEditor(
        entry: EnvironmentVariable?,
        key: String,
        value: String,
    ) {
        viewModel?.clearEnvironmentError()
        editingKey = entry?.id
        editorInitialKey = key
        editorInitialValue = value
        showEditor = true
    }

    if (showEditor) {
        EnvironmentVariableEditor(
            entry = editing,
            initialKey = editorInitialKey,
            currentValue = editorInitialValue,
            error = error,
            onDismiss = {
                viewModel?.clearEnvironmentError()
                showEditor = false
                editingKey = null
            },
            onSave = { key, value, note ->
                if (editing == null) viewModel?.addEnvironmentVariable(key, value, note) { if (it) { showEditor = false } }
                else viewModel?.updateEnvironmentVariable(editing.id, key, value, note) { if (it) { showEditor = false; editingKey = null } }
            },
        )
    }
    showDelete?.let { entry ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("删除环境变量") },
            text = { Text("确定删除 ${entry.key}？") },
            confirmButton = { TextButton(onClick = { viewModel?.deleteEnvironmentVariable(entry.id); deleteKey = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("取消") } },
        )
    }

    RuntimeSettingsScaffold(
        title = "环境变量", onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel?.refreshEnvironmentVariables() }, contentDescription = "刷新环境变量", enabled = !loading && runtimeState is RuntimeState.Ready) {
                RuntimeIcon(RuntimeIconName.Refresh)
            }
            IconButton(onClick = { openEnvironmentEditor(null, "", "") }, contentDescription = "添加环境变量", enabled = !loading && runtimeState is RuntimeState.Ready) {
                RuntimeIcon(RuntimeIconName.Plus)
            }
        },
    ) {
        item {
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Shield,
                    title = "Agent 隐私遮盖",
                    subtitle = "仅遮盖发送给 Agent 和写入对话的变量值；本页仍显示明文",
                    trailing = { Switch(checked = privacyMode, onCheckedChange = { arg0 -> viewModel?.setEnvironmentPrivacyMode(arg0) }) },
                )
            }
        }
        item {
            SettingsDetailCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = semanticColors().warningContainer,
                borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    RuntimeIcon(RuntimeIconName.Alert, tint = semanticColors().warning)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "警告 · 修改环境变量可能导致运行异常",
                            style = MaterialTheme.typography.titleSmall,
                            color = semanticColors().onWarningContainer,
                        )
                        Text(
                            "错误覆盖 JAVA_HOME、GRADLE_HOME、LANG 等变量，可能使终端、构建工具或插件无法启动。请只修改你明确了解用途的变量；Tianyan 运行时关键变量会被强制保护。",
                            style = MaterialTheme.typography.bodySmall,
                            color = semanticColors().onWarningContainer,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "用户变量保存在 $activeDistroId 的 Linux /etc/profile.d 中，并在下一次命令或终端会话启动时生效。值以受限文件权限保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { message ->
            item {
                SettingsDetailCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SectionHeader(
                title = "用户变量",
                subtitle = "可编辑的 Tianyan 用户配置",
                trailing = { Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        if (loading && entries.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        } else if (entries.isEmpty()) {
            item { SettingsDetailCard(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无用户变量", style = MaterialTheme.typography.titleMedium); Text("点击右上角 + 添加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        } else {
            items(entries, key = { it.id }) { entry ->
                SettingsDetailCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.key,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.note.isNotBlank()) {
                                Text(
                                    entry.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                values[entry.key].orEmpty().ifEmpty { "（空值）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) }, contentDescription = "编辑 ${entry.key}") { RuntimeIcon(RuntimeIconName.Edit) }
                        IconButton(onClick = { deleteKey = entry.id }, contentDescription = "删除 ${entry.key}") { RuntimeIcon(RuntimeIconName.Trash, tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = "当前有效环境",
                subtitle = "新命令实际可见的变量与值",
                trailing = { Text(effectiveEntries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        if (loading && effectiveEntries.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        } else if (effectiveEntries.isEmpty()) {
            item {
                SettingsDetailCard(modifier = Modifier.fillMaxWidth()) {
                    Text("暂无可读取的运行时环境", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                SettingsGroup {
                    effectiveEntries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val managedEntry = entries.firstOrNull { it.key == entry.key }
                        SettingsRow(
                            icon = RuntimeIconName.Key,
                            title = entry.key,
                            subtitle = entry.value.ifEmpty { "（空值）" },
                            onClick = {
                                if (managedEntry == null) {
                                    openEnvironmentEditor(null, entry.key, entry.value)
                                } else {
                                    openEnvironmentEditor(managedEntry, managedEntry.key, values[managedEntry.key].orEmpty())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentVariableEditor(
    entry: EnvironmentVariable?,
    initialKey: String,
    currentValue: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var key by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(initialKey) }
    var value by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(currentValue) }
    var note by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(entry?.note.orEmpty()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry != null) "编辑环境变量" else if (initialKey.isNotBlank()) "配置环境变量" else "添加环境变量") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(18.dp), MaterialTheme.colorScheme.tertiary)
                    Text(
                        if (entry == null && initialKey.isNotBlank()) {
                            "这会在用户配置中覆盖 Linux 当前值。错误配置可能导致相关命令无法运行。"
                        } else {
                            "修改后会影响新启动的命令与终端，请确认变量名称和值正确。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                RuntimeFilledTextField(value = key, onValueChange = { key = it.uppercase() }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(value = value, onValueChange = { value = it }, label = { Text("值") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value, note) }, enabled = key.isNotBlank() && (entry != null || value.isNotEmpty())) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

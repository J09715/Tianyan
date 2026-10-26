package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.components.*

@Composable
internal fun TeamPublicationDialog(group: BotMemberGroup, onDismiss: () -> Unit,
    loadImpact: suspend (String) -> List<TeamUpgradeImpact>, loadVersions: suspend (String) -> List<TeamPublishedVersion>,
    onPublish: suspend (String, Int, Set<String>) -> Int, onUpgrade: suspend (String, Int, Set<String>) -> Unit) {
    var impact by remember { mutableStateOf<List<TeamUpgradeImpact>>(emptyList()) }
    var versions by remember { mutableStateOf<List<TeamPublishedVersion>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(group.id, group.draftRevision, group.publishedVersion, refresh) {
        loaded = false
        try { impact = loadImpact(group.id); versions = loadVersions(group.id); loaded = true }
        catch (failure: Exception) { error = failure.message }
    }
    val latest = versions.firstOrNull()
    val changed = latest == null || latest.name != group.name || latest.memberIds != group.memberIds || latest.definition != group.definition
    val target = if (changed) group.publishedVersion + 1 else group.publishedVersion
    RuntimeAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("发布团队 · ${group.name}") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (changed) "草稿将发布为 v$target；已发布版本不会被覆盖。" else "当前草稿与 v$target 一致，可升级所选群。")
            if (loaded && changed) {
                val before = latest?.memberIds.orEmpty().toSet()
                val after = group.memberIds.toSet()
                val oldRules = latest?.definition?.governance?.rules.orEmpty().associateBy { it.id }
                val newRules = group.definition?.governance?.rules.orEmpty().associateBy { it.id }
                Text("成员新增 ${(after - before).size}、移除 ${(before - after).size}；审议规则变更 ${(oldRules.keys + newRules.keys).count { oldRules[it] != newRules[it] }} 条。", style = MaterialTheme.typography.bodySmall)
            }
            Text("选择本次升级范围。未选中的群保持原版本；有未结束任务、待核对结果或待处理消息的群，请结束工作后刷新再升级。", style = MaterialTheme.typography.bodySmall)
            Text("升级后，旧方案须在新团队版本下重新提交审核；历史结果保留，旧任务不跨版本续办。", style = MaterialTheme.typography.bodySmall)
            Text("引用 ${impact.size} 个群 · 未结束任务 ${impact.sumOf { it.unfinishedTasks }} 项 · 本次选择 ${selected.size} 个群")
            if (!loaded) Text("正在读取发布影响…")
            impact.forEach { row ->
                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("${row.name} · v${row.version ?: 0}")
                            Text(if (row.canUpgrade) "可升级到 v$target" else "保留旧版：${row.unfinishedTasks} 项未结束任务，${row.pendingWork} 项待处理工作", style = MaterialTheme.typography.bodySmall)
                        }
                        RuntimeSwitch(modifier = Modifier.testTag("upgrade-${row.groupId}").semantics { contentDescription = "升级${row.name}" }, checked = row.groupId in selected && row.canUpgrade,
                            enabled = loaded && !saving && row.canUpgrade && row.version != target,
                            onCheckedChange = { selected = if (it) selected + row.groupId else selected - row.groupId })
                    }
                }
            }
            RuntimeTextButton(enabled = !saving, onClick = { selected = emptySet(); refresh++ }) { Text("刷新影响范围") }
            if (versions.isNotEmpty()) {
                Text("发布历史", style = MaterialTheme.typography.titleSmall)
                versions.forEach { Text("v${it.version} · ${it.memberIds.size} 名成员 · ${it.definition.governance.rules.size} 条审议规则", style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { RuntimeButton(enabled = loaded && !saving && group.definition != null && (changed || selected.isNotEmpty()), onClick = {
        saving = true
        scope.launch {
            try {
                // Recheck the selection in the transaction; a busy room rejects the whole publication.
                if (changed) onPublish(group.id, group.draftRevision, selected) else onUpgrade(group.id, target, selected)
                onDismiss()
            } catch (failure: Exception) { error = failure.message; selected = emptySet(); refresh++ }
            finally { saving = false }
        }
    }) { Text(if (saving) "正在发布…" else if (changed) "发布 v$target" else "升级到 v$target") } },
        dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

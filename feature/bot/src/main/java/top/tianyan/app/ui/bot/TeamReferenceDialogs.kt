package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.components.*

@Composable
internal fun TeamReferenceDialog(groups: List<BotMemberGroup>, members: List<Bot>, currentId: String?, onDismiss: () -> Unit,
    onSelect: suspend (String) -> Unit) {
    var selected by remember { mutableStateOf(currentId) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val available = groups.filter { it.publishedSnapshot != null }
    RuntimeAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("引用团队组") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("群聊引用已发布的团队版本。草稿和新发布不会自动改变本群；有未结束任务时暂不能切换。", style = MaterialTheme.typography.bodySmall)
            if (available.isEmpty()) Text("请先在团队组中保存并发布，或从市场导入团队模板。")
            available.forEach { group ->
                RuntimeCard(modifier = Modifier.fillMaxWidth(), onClick = { if (!saving) selected = group.id }) {
                    Text((if (selected == group.id) "✓ " else "") + group.name, style = MaterialTheme.typography.titleSmall)
                    Text("v${group.publishedVersion} · ${group.publishedSnapshot?.memberIds?.size ?: 0} 名成员")
                }
            }
            available.firstOrNull { it.id == selected }?.let { group ->
                group.publishedSnapshot?.let { TeamArchitectureGraph(it.definition.team(it.memberIds), it.definition.governance, members) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { RuntimeButton(enabled = !saving && available.any { it.id == selected }, onClick = {
        saving = true
        scope.launch {
            try { onSelect(requireNotNull(selected)); onDismiss() } catch (failure: Exception) { error = failure.message }
            finally { saving = false }
        }
    }) { Text(if (saving) "引用中…" else "引用团队") } },
        dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun TeamGraphDialog(team: GroupTeam, governance: GroupGovernance, members: List<Bot>, onDismiss: () -> Unit,
    tasks: List<BotAssignment> = emptyList(), proposals: List<GroupProposal> = emptyList(), group: GroupChat? = null, messages: List<GroupMessage> = emptyList()) {
    val runtime = remember(group, team, governance, tasks, proposals, messages) { group?.let { buildTeamRuntime(it, team, governance, tasks, proposals, messages) } }
    TeamArchitecturePage(team, governance, members, onDismiss = onDismiss, runtime = runtime)
}

@Composable
internal fun ProposalRecordsDialog(proposals: List<GroupProposal>, onDismiss: () -> Unit) {
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text("本群方案审议记录") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (proposals.isEmpty()) Text("暂无方案记录。规则由团队组提供，各群的方案和审核结果独立保存。")
            proposals.take(40).forEach { proposal -> RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("${proposal.rule.name} · v${proposal.version} · ${proposal.decision}", style = MaterialTheme.typography.titleSmall)
                    Text(proposal.reason.ifBlank { proposal.content.take(1000) }, style = MaterialTheme.typography.bodySmall)
                }
            } }
        }
    }, confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text("关闭") } })
}

package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.components.*

@Composable
internal fun TeamRuntimeDetails(runtime: TeamGraphRuntime, members: List<Bot>, memberId: String?,
    proposalIds: List<String>?, onDismiss: () -> Unit) {
    val name = members.firstOrNull { it.id == memberId }?.name ?: "方案流转"
    val tasks = runtime.tasks.filter { task -> if (memberId != null) task.botId == memberId else task.proposalId in proposalIds.orEmpty() }
    val proposals = runtime.proposals.filter { if (proposalIds != null) it.id in proposalIds else memberId in listOf(it.rule.authorId, it.rule.reviewerId, it.rule.executorId, it.decidedBy) || it.decision == ProposalDecision.ESCALATED && memberId == runtime.group.adminBotId }
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text(name) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${runtime.group.name} · 团队 v${runtime.group.teamVersion ?: 0}", style = MaterialTheme.typography.bodySmall)
            memberId?.let { Text(runtime.nodes[it]?.label.orEmpty()) }
            if (tasks.isEmpty() && proposals.isEmpty()) Text("暂无任务或方案。")
            tasks.sortedByDescending { it.createdAt }.take(20).forEach { task ->
                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Text("${task.title} · ${statusLabel(task.status)}", style = MaterialTheme.typography.titleSmall)
                    Text(task.error ?: task.summary ?: task.task, style = MaterialTheme.typography.bodySmall)
                    Text("任务 ${task.id}" + (task.proposalVersion?.let { " · 方案 v$it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                }
            }
            proposals.forEach { proposal ->
                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Text("${proposal.rule.name} · v${proposal.version} · ${when (proposal.decision) { ProposalDecision.PENDING -> "待审议"; ProposalDecision.APPROVED -> "已批准"; ProposalDecision.REJECTED -> "已封驳"; ProposalDecision.ESCALATED -> "待主控裁决" }}", style = MaterialTheme.typography.titleSmall)
                    Text(proposal.content, style = MaterialTheme.typography.bodySmall)
                    if (proposal.reason.isNotBlank()) Text("审核依据：${proposal.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text("关闭") } })
}

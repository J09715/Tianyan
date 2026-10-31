package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.ui.components.*

@Composable
internal fun WorkflowHistoryCard(
    entry: WorkflowHistoryEntry,
    onRerun: (WorkflowHistoryEntry) -> Unit,
    onHistory: (WorkflowRuntimeState) -> Unit,
    onResume: (WorkflowRuntimeState, Set<String>) -> Unit,
) {
    val run = entry.state
    val plan = remember(run) { WorkflowRecovery.inspect(run) }
    var showResume by remember { mutableStateOf(false) }
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${run.definition.name} · ${workflowStatusLabel(run.status)} · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(run.startedAt ?: 0))}",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (entry.triggerSource == "SCHEDULE") "定时计划触发" else "手动触发",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!plan.canResume && run.status in setOf(WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)) {
                Text(plan.blockedReason.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.canResume) RuntimeOutlinedButton(onClick = { showResume = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("继续", maxLines = 1)
                }
                RuntimeOutlinedButton(onClick = { onRerun(entry) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("重跑", maxLines = 1) }
                RuntimeOutlinedButton(onClick = { onHistory(run) }, contentPadding = PaddingValues(9.dp)) { RuntimeIcon(RuntimeIconName.List, Modifier.size(16.dp)) }
            }
        }
    }
    if (showResume) WorkflowResumeDialog(run, plan, onDismiss = { showResume = false }) { retry ->
        showResume = false
        onResume(run, retry)
    }
}

@Composable
private fun WorkflowResumeDialog(
    run: WorkflowRuntimeState,
    plan: WorkflowRecoveryPlan,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var retries by remember(run) { mutableStateOf(emptySet<String>()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("继续 ${run.definition.name}") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("复用 ${plan.cachedOutputs.size} 个已保存结果，执行 ${plan.pendingNodeIds.size} 个剩余节点。")
                Text("请先核对工作区文件、应用状态和已产生的产物。继续将沿用原计划及已完成的审批；待处理的审批会重新请求。")
                Text("工作区：${run.context?.workspacePath.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                run.definition.nodes.filter { it.id in plan.pendingNodeIds }.forEach { node ->
                    if (node.id in plan.retryNodeIds) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${node.title}：失败或结果不确定。确认可安全重试后开启。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            RuntimeSwitch(checked = node.id in retries, onCheckedChange = { checked ->
                                retries = if (checked) retries + node.id else retries - node.id
                            })
                        }
                    } else Text("待执行：${node.title}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { RuntimeButton(onClick = { onConfirm(retries) }, enabled = retries == plan.retryNodeIds) { Text("继续执行") } },
    )
}

package top.tianyan.app.ui.workflow
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorkflowCatalog(
    definitions: List<WorkflowDefinition>,
    onRun: (WorkflowDefinition) -> Unit,
    onEdit: (WorkflowDefinition) -> Unit,
    onDelete: (WorkflowDefinition) -> Unit,
    onCreate: () -> Unit,
    onImport: (WorkflowDefinition) -> Unit,
    history: List<WorkflowHistoryEntry>,
    onHistory: (WorkflowRuntimeState) -> Unit,
    onRerun: (WorkflowHistoryEntry) -> Unit,
    onResume: (WorkflowRuntimeState, Set<String>) -> Unit,
    backgroundRuns: List<WorkflowRuntimeState>,
    onViewBackgroundRun: (String) -> Unit,
    schedules: List<WorkflowScheduleEntity> = emptyList(),
    onNewSchedule: () -> Unit = {},
    onEditSchedule: (WorkflowScheduleEntity) -> Unit = {},
    onToggleSchedule: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteSchedule: (String) -> Unit = {},
    modifier: Modifier = Modifier, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var deleteRequested by remember { mutableStateOf<WorkflowDefinition?>(null) }
    LazyColumn(
        modifier = modifier,
        contentPadding = top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 32.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { WorkflowCatalogEntry(onCreate, onImport) }
        if (backgroundRuns.isNotEmpty()) {
            item(key = "background_banner") {
                RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RuntimeIcon(RuntimeIconName.Play, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (backgroundRuns.size == 1) "1 个工作流正在后台运行" else "${backgroundRuns.size} 个工作流正在后台运行",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                backgroundRuns.joinToString("、") { it.definition.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RuntimeOutlinedButton(
                            onClick = { onViewBackgroundRun(backgroundRuns.first().executionId) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("查看", maxLines = 1)
                        }
                    }
                }
            }
        }
        items(definitions, key = { it.id }) { workflow ->
            RuntimeCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RuntimeIcon(RuntimeIconName.Hub, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(workflow.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${workflow.category} · ${workflow.nodes.size} 个节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(workflow.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!workflow.isBuiltin) {
                            RuntimeOutlinedButton(onClick = { deleteRequested = workflow }, contentPadding = PaddingValues(9.dp)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp))
                            }
                        }
                        RuntimeOutlinedButton(onClick = { onEdit(workflow) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp))
                            Text(if (workflow.isBuiltin) "创建副本" else "编辑", maxLines = 1)
                        }
                        RuntimeButton(onClick = { onRun(workflow) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            RuntimeIcon(RuntimeIconName.Play, Modifier.size(16.dp))
                            Text("运行", maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "定时计划",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                    RuntimeOutlinedButton(onClick = onNewSchedule, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
                        Text("新建计划", maxLines = 1)
                    }
                }
            }
            if (schedules.isEmpty()) {
                item {
                    Text(
                        "还没有定时计划。把工作流设为每天定时运行、周期巡检或一次性延时触发；到点即使应用未在运行，系统也会拉起执行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(schedules, key = { "schedule:${it.id}" }) { schedule ->
                val workflowName = definitions.firstOrNull { it.id == schedule.workflowId }?.name ?: schedule.workflowId
                RuntimeCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(schedule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "$workflowName · ${scheduleRepeatLabel(schedule)}" +
                                        schedule.nextRunAt?.let { next ->
                                            " · 下次 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))}"
                                        }.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = schedule.enabled, onCheckedChange = { onToggleSchedule(schedule.id, it) })
                        }
                        schedule.lastExecutionId?.let {
                            Text(
                                "上次触发：${schedule.lastRunAt?.let { last -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last)) } ?: "—"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeOutlinedButton(onClick = { onDeleteSchedule(schedule.id) }, contentPadding = PaddingValues(9.dp)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp))
                            }
                            RuntimeOutlinedButton(onClick = { onEditSchedule(schedule) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp))
                                Text("编辑", maxLines = 1)
                            }
                        }
                    }
                }
            }
        if (history.isNotEmpty()) item { Text("最近运行（最近 ${history.size} 条）", style = MaterialTheme.typography.titleMedium) }
        items(history, key = { "history:${it.state.executionId}" }) { entry ->
            WorkflowHistoryCard(entry, onRerun, onHistory, onResume)
        }
    }
    deleteRequested?.let { workflow ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteRequested = null },
            title = { Text("删除工作流？") },
            text = { Text("“${workflow.name}”及其运行历史将被永久删除，无法撤销。") },
            dismissButton = { RuntimeOutlinedButton(onClick = { deleteRequested = null }) { Text("取消") } },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        deleteRequested = null
                        onDelete(workflow)
                    },
                ) { Text("删除") }
            },
        )
    }
}


package top.tianyan.app.ui.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeOutlinedButton

internal fun updatedWorkflowMetadata(definition: WorkflowDefinition, name: String, description: String,
    category: String, maxParallelNodes: Int): WorkflowDefinition {
    require(maxParallelNodes in 1..8) { "最大执行并发必须在 1–8 之间" }
    return definition.copy(name = name, description = description, category = category,
        maxParallelNodes = maxParallelNodes, updatedAt = System.currentTimeMillis())
}

@Composable
internal fun WorkflowMetadataCard(state: WorkflowEditorUiState, onApply: (String, String, String, Int) -> Unit) {
    var name by remember(state.definition.id, state.definition.name) { mutableStateOf(state.definition.name) }
    var description by remember(state.definition.id, state.definition.description) { mutableStateOf(state.definition.description) }
    var category by remember(state.definition.id, state.definition.category) { mutableStateOf(state.definition.category) }
    var parallel by remember(state.definition.id, state.definition.maxParallelNodes) { mutableStateOf(state.definition.maxParallelNodes.toString()) }
    var expanded by remember { mutableStateOf(false) }
    val parallelValue = parallel.toIntOrNull()?.takeIf { it in 1..8 }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("工作流信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "修改", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            WorkflowExportButton(state.definition, state.isDirty)
            if (expanded) {
                RuntimeFilledTextField(name, { name = it }, label = { Text("工作流名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(parallel, { parallel = it }, label = { Text("最大执行并发（1–8）") },
                    supportingText = { Text(if (parallelValue == null) "请输入 1–8 的整数" else "人工审批节点不占名额；设为 1 可串行执行普通节点。") },
                    isError = parallelValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeOutlinedButton(enabled = parallelValue != null, onClick = { parallelValue?.let { onApply(name, description, category, it) } },
                    modifier = Modifier.align(Alignment.End)) { Text("应用信息") }
            } else {
                Text("${state.definition.name} · [${state.definition.category}] · 最大并发 ${state.definition.maxParallelNodes}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

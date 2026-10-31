package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.harness.ModelContextWindows
import top.tianyan.app.ui.components.*

/** 档案管理的默认状态属于全局配置，与聊天页的会话模型选择区分。 */
@Composable
internal fun ModelProfileCard(
    model: AiModelEntity,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val modelIds = remember(model.model) { model.model.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    RuntimeCard(modifier = Modifier.fillMaxWidth(), onClick = onEdit,
        containerColor = if (model.isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge, contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModelBadge(modelId = model.model, providerIdOrName = model.provider, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.name.ifBlank { model.provider }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.provider, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    RuntimeIconButton({ menuExpanded = true }, contentDescription = "${model.name}的更多操作") {
                        RuntimeIcon(RuntimeIconName.More)
                    }
                    DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("编辑档案") }, leadingIcon = { RuntimeIcon(RuntimeIconName.Edit) },
                            onClick = { menuExpanded = false; onEdit() })
                        DropdownMenuItem(text = { Text("导出配置") }, leadingIcon = { RuntimeIcon(RuntimeIconName.OpenInNew) },
                            onClick = { menuExpanded = false; onExport() })
                        if (!model.isActive) DropdownMenuItem(text = { Text("设为全局默认") },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.Check) }, onClick = { menuExpanded = false; onActivate() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("删除档案", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.Trash, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            if (modelIds.isNotEmpty()) {
                Text(modelIds.take(3).joinToString("\n") + if (modelIds.size > 3) "\n另有 ${modelIds.size - 3} 个模型" else "",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            val tokens = model.contextTokens ?: ModelContextWindows.resolve(model.model, model.provider)
            val details = buildList {
                tokens?.let { add("${formatModelContext(it)} 上下文") }
                if (model.apiKeyCount > 0) add("${model.apiKeyCount} 个 Key")
                if (model.requestsPerMinutePerKey > 0) add("${model.requestsPerMinutePerKey} RPM/Key")
            }.joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.baseUrl.isNotBlank()) Text(model.baseUrl, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isActive) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    Text("全局默认", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    RuntimeFilledTonalButton(onActivate) { Text("设为默认") }
                    Spacer(Modifier.weight(1f))
                }
                RuntimeTextButton(onEdit) { Text("编辑") }
            }
        }
    }
}

package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.tools.AgentProviderDefinition
import top.tianyan.app.core.tools.ProviderGroup
import top.tianyan.app.ui.components.*

@Composable
internal fun ProviderPickerDialog(
    providers: List<AgentProviderDefinition>,
    currentProviderId: String,
    onDismiss: () -> Unit,
    onSelect: (AgentProviderDefinition) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<ProviderGroup?>(null) }
    val filtered = remember(providers, query, group) {
        providers.filter { item ->
            (group == null || item.group == group) && (query.isBlank() ||
                listOf(item.name, item.id, item.baseUrl).any { it.contains(query.trim(), true) })
        }
    }
    RuntimeSelectionSheet(title = "选择服务商预设", subtitle = "选择预设后可继续编辑接口地址与模型", onDismissRequest = onDismiss) {
        RuntimeSearchBar(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "搜索服务商名称或端点")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                RuntimeFilterChip(group == null, { group = null }, label = { Text("全部") }, shape = MaterialTheme.shapes.small)
            }
            items(ProviderGroup.entries) { option ->
                RuntimeFilterChip(group == option, { group = option }, label = { Text(providerGroupLabel(option)) },
                    shape = MaterialTheme.shapes.small)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (filtered.isEmpty()) item {
                Text("未找到匹配的服务商", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filtered, key = { it.id }) { option ->
                RuntimeSelectionRow(headline = option.name, selected = option.id == currentProviderId,
                    onClick = { onSelect(option) },
                    supportingText = providerGroupLabel(option.group) + " · " + option.baseUrl.ifBlank { "自定义接口地址" },
                    leadingContent = { ProviderBadge(providerIdOrName = option.id, size = 32.dp) })
            }
        }
    }
}

private fun providerGroupLabel(group: ProviderGroup): String = when (group) {
    ProviderGroup.OFFICIAL -> "官方"
    ProviderGroup.CHINA -> "国内"
    ProviderGroup.AGGREGATOR -> "聚合"
    ProviderGroup.LOCAL -> "本地"
    ProviderGroup.CUSTOM -> "自定义"
}

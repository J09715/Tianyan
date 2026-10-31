package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.ui.components.*

@Composable
internal fun ModelProfilesContent(
    modifier: Modifier,
    bottomPadding: Dp = 0.dp,
    models: List<AiModelEntity>,
    onCreate: () -> Unit,
    onEdit: (AiModelEntity) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (AiModelEntity) -> Unit,
    onExportSingle: (AiModelEntity) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var defaultOnly by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(models, query, defaultOnly) { filterModelProfiles(models, query, defaultOnly) }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 32.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${models.size} 个模型档案", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RuntimeFilledTonalButton(onCreate) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("新增")
                }
            }
        }
        item { RuntimeSearchBar(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "搜索档案、供应商或模型") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeFilterChip(!defaultOnly, { defaultOnly = false }, label = { Text("全部") }, shape = MaterialTheme.shapes.small)
                RuntimeFilterChip(defaultOnly, { defaultOnly = true }, label = { Text("全局默认") }, shape = MaterialTheme.shapes.small)
            }
        }
        if (filtered.isEmpty()) item {
            EmptyPanel(RuntimeIconName.Model, if (models.isEmpty()) "暂无模型档案" else "没有匹配的档案",
                if (models.isEmpty()) "点击新增，或从右上角导入模型配置" else "试试其他关键词，或切换到全部档案")
        }
        items(filtered, key = { it.id }) { model ->
            ModelProfileCard(model, onEdit = { onEdit(model) }, onActivate = { onActivate(model.id) },
                onDelete = { onDelete(model) }, onExport = { onExportSingle(model) })
        }
    }
}

internal fun filterModelProfiles(models: List<AiModelEntity>, query: String, defaultOnly: Boolean): List<AiModelEntity> =
    models.filter { model ->
        (!defaultOnly || model.isActive) && (query.isBlank() || listOf(model.name, model.provider, model.model)
            .any { it.contains(query.trim(), ignoreCase = true) })
    }

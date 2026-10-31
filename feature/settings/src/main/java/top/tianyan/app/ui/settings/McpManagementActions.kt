package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.*

/** 操作区允许自然换行，不让多个小按钮争抢标题所在的一行。 */
@Composable
internal fun McpManagementActions(count: Int, onRefresh: () -> Unit, onDiscover: () -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MCP 服务 · $count", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            RuntimeIconButton(onRefresh, contentDescription = "刷新 MCP 服务") { RuntimeIcon(RuntimeIconName.Refresh) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeFilledTonalButton(onAdd) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加服务")
            }
            RuntimeTextButton(onDiscover) {
                RuntimeIcon(RuntimeIconName.Search, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("探测本机")
            }
        }
    }
}

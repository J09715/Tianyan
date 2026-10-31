package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.ui.theme.semanticColors

/**
 * 设置页与聊天能力面板共用的服务行。
 * 状态、工具数和授权信息可换行，说明与错误最多展示两行。
 */
@Composable
fun McpServerRow(
    name: String,
    enabled: Boolean,
    connectionState: McpConnectionState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    toolCount: Int? = null,
    authLabel: String? = null,
    authOk: Boolean = false,
    notice: String? = null,
    error: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    bottomActions: (@Composable () -> Unit)? = null,
) {
    val stateLabel = mcpStateLabel(enabled, connectionState)
    RuntimeCard(modifier = modifier.fillMaxWidth(), onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge, contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RuntimeIcon(RuntimeIconName.Network, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = mcpStateColor(enabled, connectionState))
                    if (toolCount != null && toolCount > 0) Text("$toolCount 工具", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    authLabel?.let { label -> Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (authOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                }
                val secondary = error?.takeIf { it.isNotBlank() } ?: notice?.takeIf { it.isNotBlank() }
                    ?: subtitle?.takeIf { it.isNotBlank() }
                if (secondary != null) Text(secondary.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !error.isNullOrBlank() -> MaterialTheme.colorScheme.error
                        !notice.isNullOrBlank() -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (bottomActions != null) FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { bottomActions() }
    }
}

@Composable
private fun mcpStateColor(enabled: Boolean, state: McpConnectionState): Color = when {
    !enabled -> MaterialTheme.colorScheme.outlineVariant
    state == McpConnectionState.ONLINE || state == McpConnectionState.SLEEPING -> semanticColors().success
    state == McpConnectionState.CHECKING || state == McpConnectionState.WAKING -> MaterialTheme.colorScheme.tertiary
    state == McpConnectionState.OFFLINE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun mcpStateLabel(enabled: Boolean, state: McpConnectionState): String = when {
    !enabled -> "已停用"
    state == McpConnectionState.ONLINE -> "在线"
    state == McpConnectionState.SLEEPING -> "已就绪（休眠）"
    state == McpConnectionState.WAKING -> "正在唤醒"
    state == McpConnectionState.CHECKING -> "检测中"
    state == McpConnectionState.OFFLINE -> "离线"
    else -> "状态未知"
}

package top.tianyan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.ui.theme.semanticColors

/**
 * MCP 服务在设置页与聊天能力面板共用的紧凑行：
 * 名称 + 工具数 + 授权徽章 + 启停槽位，错误/说明压到第二行单行展示。
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
) {
    val stateLabel = mcpStateLabel(enabled, connectionState)
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(mcpStateColor(enabled, connectionState))
                    .semantics { contentDescription = stateLabel },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (toolCount != null && toolCount > 0) {
                        Text(
                            text = "$toolCount 工具",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    authLabel?.let { label ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (authOk) semanticColors().successContainer
                            else MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (authOk) semanticColors().onSuccessContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                val stateHint = when {
                    !enabled -> null
                    connectionState == McpConnectionState.SLEEPING -> "已就绪（休眠）"
                    connectionState == McpConnectionState.WAKING -> "正在唤醒"
                    connectionState == McpConnectionState.CHECKING -> "检测中"
                    connectionState == McpConnectionState.OFFLINE -> "离线"
                    else -> null
                }
                val secondary = error?.takeIf { it.isNotBlank() }
                    ?: notice?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(stateHint, subtitle?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() }
                if (secondary != null) {
                    Text(
                        text = secondary.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !error.isNullOrBlank() -> MaterialTheme.colorScheme.error
                            !notice.isNullOrBlank() -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing,
                )
            }
        }
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

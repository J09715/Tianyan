package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.ui.components.McpServerRow
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSwitch as Switch

/**
 * 系统核心 / 自定义 MCP 服务的列表行。
 *
 * 独立成文件的原因：McpSettingsScreen 已超出架构棘轮基线，且行样式现在与聊天能力面板共用
 * [McpServerRow]；这里只保留设置页专属的启停开关与安装门禁提示。
 */
@Composable
internal fun McpServerItemCard(
    server: McpServerConfig,
    connectionState: McpConnectionState,
    error: String?,
    togglePending: Boolean,
    requiredComponentName: String?,
    toolCount: Int?,
    authLabel: String?,
    authOk: Boolean,
    onToggle: (Boolean) -> Unit,
    onClickDetail: () -> Unit,
) {
    McpServerRow(
        name = server.name,
        subtitle = listOf(server.transportType.name, server.description).filter { it.isNotBlank() }.joinToString(" · "),
        enabled = server.isEnabled,
        connectionState = connectionState,
        toolCount = toolCount,
        authLabel = authLabel,
        authOk = authOk,
        notice = requiredComponentName?.let { "需先安装「$it」：设置 → 工具中心 → MCP 能力仓库" },
        error = server.headersReadError ?: error,
        onClick = onClickDetail,
        trailing = {
            Switch(
                checked = server.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = server.name },
                thumbContent = if (server.isEnabled) {
                    {
                        RuntimeIcon(
                            RuntimeIconName.Check,
                            Modifier.size(16.dp),
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else null,
                // 未安装时锁死开关：打开只会让启动期工具发现每次白等一个超时
                enabled = server.headersReadError == null && !togglePending && (server.isEnabled || requiredComponentName == null),
            )
        },
    )
}

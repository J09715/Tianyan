package top.tianyan.app.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.ui.components.RuntimePreview

/** No ViewModels, services, credentials, or connection probes are created by these fixtures. */
@Preview(name = "服务状态 · 浅色", widthDp = 360, heightDp = 900, locale = "zh")
@Preview(name = "服务状态 · 深色大字体", widthDp = 360, heightDp = 900, fontScale = 1.3f,
    uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "zh")
@Composable
private fun PreferenceStatePreview() {
    RuntimePreview {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            McpServerItemCard(
                server = McpServerConfig(id = "online", name = "工作区文件与项目分析服务",
                    description = "提供当前工作区的文件查询与分析能力", isEnabled = true),
                connectionState = McpConnectionState.ONLINE, error = null, togglePending = false,
                requiredComponentName = null, toolCount = 12, authLabel = "已授权", authOk = true,
                onToggle = {}, onClickDetail = {})
            McpServerItemCard(
                server = McpServerConfig(id = "blocked", name = "等待安装的工具服务", isEnabled = false),
                connectionState = McpConnectionState.UNKNOWN, error = null, togglePending = false,
                requiredComponentName = "示例组件", toolCount = null, authLabel = null, authOk = false,
                onToggle = {}, onClickDetail = {})
            McpServerItemCard(
                server = McpServerConfig(id = "offline", name = "离线的外部服务", isEnabled = true),
                connectionState = McpConnectionState.OFFLINE, error = "暂时无法连接，请在详情中检查服务配置。",
                togglePending = false, requiredComponentName = null, toolCount = 0,
                authLabel = "未授权", authOk = false, onToggle = {}, onClickDetail = {})
            EditorToggleRow("自动适配模型能力", "依据当前模型提供的能力显示相关选项", true, onCheckedChange = {})
        }
    }
}

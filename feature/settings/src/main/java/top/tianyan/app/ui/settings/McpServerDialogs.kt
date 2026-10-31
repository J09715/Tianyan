package top.tianyan.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpAuthState
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpToolPolicy
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.ui.components.RuntimeSelectionSheet
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

/**
 * MCP 服务详情、命令预览、JSON 配置与工具探测弹窗
 */
@Composable
internal fun McpServerDetailDialog(
    server: McpServerConfig,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onTest: suspend () -> Result<List<McpToolInfo>>,
    onEdit: (() -> Unit)? = null,
    cachedTools: List<McpToolInfo> = emptyList(),
    toolPolicies: Map<String, McpToolPolicy> = emptyMap(),
    onSetToolEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onSetToolRisk: (String, String?) -> Unit = { _, _ -> },
    authState: McpAuthState = McpAuthState.Unsupported,
    onAuthorize: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    var testing by remember { mutableStateOf(false) }
    var discoveredTools by remember { mutableStateOf<List<McpToolInfo>?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val jsonConfig = remember(server) { server.toExportJsonConfig() }

    RuntimeSelectionSheet(
        title = server.name, subtitle = server.transportType.name, onDismissRequest = onDismiss,
        actions = {
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onEdit?.let { action -> OutlinedButton(onClick = action) { Text("编辑") } }
                if (!server.isBuiltin) TextButton(onClick = onDelete) { Text("删除服务", color = MaterialTheme.colorScheme.error) }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (server.description.isNotBlank()) {
                    Text(
                        server.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 启动命令 / URL 预览
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (server.transportType == McpTransportType.STDIO) "启动命令" else "服务端点 URL",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val cmdText = if (server.transportType == McpTransportType.STDIO) {
                                "${server.command} ${server.args.joinToString(" ")}"
                            } else {
                                server.serverUrl
                            }
                            Text(
                                text = cmdText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("mcp_cmd", cmdText))
                                    Toast.makeText(context, "命令已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Copy, Modifier.size(14.dp), MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }

                // JSON 配置代码块
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "MCP JSON 配置 (Cursor / Claude 格式)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("mcp_json", jsonConfig))
                                Toast.makeText(context, "JSON 配置已复制", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("复制 JSON", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = jsonConfig,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                // OAuth 身份与授权
                if (server.authMode == McpAuthMode.OAUTH) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "身份与授权",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val label = when (authState) {
                                McpAuthState.Unauthenticated -> "未授权"
                                McpAuthState.Authorizing -> "授权中…"
                                is McpAuthState.Authorized -> "已授权"
                                is McpAuthState.Error -> "授权失败"
                                McpAuthState.Unsupported -> "不支持"
                            }
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            when (authState) {
                                McpAuthState.Unauthenticated, is McpAuthState.Error ->
                                    Button(onClick = onAuthorize, enabled = server.oauthClientId.isNotBlank()) { Text("授权") }
                                is McpAuthState.Authorized ->
                                    OutlinedButton(onClick = onLogout) { Text("退出授权") }
                                McpAuthState.Authorizing ->
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                McpAuthState.Unsupported -> Unit
                            }
                        }
                    }
                }

                // 测试与工具探测区域
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "沙箱连通性与工具探测",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        FilledTonalButton(
                            onClick = {
                                if (!testing) {
                                    testing = true
                                    testError = null
                                    scope.launch {
                                        val res = onTest()
                                        testing = false
                                        res.onSuccess { tools ->
                                            discoveredTools = tools
                                            Toast.makeText(context, "成功探测到 ${tools.size} 个工具", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            testError = err.message ?: "连接失败"
                                            Toast.makeText(context, "连接失败：${err.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            if (testing) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("探测工具", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    testError?.let {
                        Text(
                            "探测失败: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val tools = discoveredTools ?: cachedTools
                    if (tools.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "工具 (${tools.size}) · 开关影响模型可见面；风险覆盖影响审批等级（服务端注解仍只升不降）",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            tools.forEach { tool ->
                                McpToolPolicyRow(
                                    tool = tool,
                                    policy = toolPolicies[tool.name] ?: McpToolPolicy(),
                                    onEnabledChange = { onSetToolEnabled(tool.name, it) },
                                    onRiskChange = { onSetToolRisk(tool.name, it) },
                                )
                            }
                        }
                    } else if (discoveredTools != null) {
                        Text(
                            "该服务已连通，但未返回任何工具定义",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

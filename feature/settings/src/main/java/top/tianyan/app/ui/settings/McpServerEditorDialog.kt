package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

/**
 * 新增 / 编辑自定义 MCP 服务。
 *
 * 编辑模式只展示表单（不提供批量 JSON 导入），并保留 id、env、内置标记等表单外字段。
 */
@Composable
internal fun McpServerEditorDialog(
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    initial: McpServerConfig? = null,
    onSaveBatch: (List<McpServerConfig>) -> Unit = { list -> list.forEach(onSave) },
) {
    val editing = initial != null
    var selectedTab by remember(initial) { mutableIntStateOf(0) }

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var transport by remember(initial) { mutableStateOf(initial?.transportType ?: McpTransportType.STDIO) }
    var command by remember(initial) { mutableStateOf(initial?.command ?: "npx") }
    var argsStr by remember(initial) { mutableStateOf(initial?.args?.joinToString(" ").orEmpty()) }
    var serverUrl by remember(initial) { mutableStateOf(initial?.serverUrl ?: "http://127.0.0.1:8000/sse") }
    var headersJson by remember(initial) { mutableStateOf(kotlinx.serialization.json.JsonObject(
        initial?.headers.orEmpty().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
    ).toString()) }
    val parsedHeaders = remember(headersJson) { parseMcpHeaders(headersJson) }
    var oauthEnabled by remember(initial) { mutableStateOf(initial?.authMode == McpAuthMode.OAUTH) }
    var oauthClientId by remember(initial) { mutableStateOf(initial?.oauthClientId.orEmpty()) }
    var oauthAuthorizationEndpoint by remember(initial) { mutableStateOf(initial?.oauthAuthorizationEndpoint.orEmpty()) }
    var oauthTokenEndpoint by remember(initial) { mutableStateOf(initial?.oauthTokenEndpoint.orEmpty()) }
    var oauthScope by remember(initial) { mutableStateOf(initial?.oauthScope.orEmpty()) }
    var oauthResource by remember(initial) { mutableStateOf(initial?.oauthResource.orEmpty()) }

    var jsonText by remember {
        mutableStateOf(
            """
            {
              "mcpServers": {
                "sqlite": {
                  "command": "uvx",
                  "args": ["mcp-server-sqlite", "--db-path", "/opt/tianyan/data/sqlite.db"]
                }
              }
            }
            """.trimIndent()
        )
    }
    val parsedServers = remember(jsonText) { parseMcpJson(jsonText) }

    val headerConflict = oauthEnabled && parsedHeaders.getOrNull().orEmpty().keys.any { it.equals("Authorization", true) }
    val valid = name.isNotBlank() && (transport != McpTransportType.SSE || (parsedHeaders.isSuccess && !headerConflict)) &&
        (transport != McpTransportType.STDIO || command.isNotBlank()) &&
        (transport != McpTransportType.SSE || serverUrl.isNotBlank()) &&
        (!oauthEnabled || (oauthClientId.isNotBlank() && oauthAuthorizationEndpoint.isNotBlank() && oauthTokenEndpoint.isNotBlank()))

    top.tianyan.app.ui.components.RuntimeSelectionSheet(
        title = if (editing) "编辑 MCP 服务" else "添加 MCP 服务",
        onDismissRequest = onDismiss,
        actions = {
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
            if (editing || selectedTab == 0) {
                Button(
                    onClick = {
                        val argsList = argsStr.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        val edited = (initial ?: McpServerConfig(id = "", name = "")).copy(
                            id = initial?.id ?: ("custom_" + System.currentTimeMillis()),
                            name = name.trim(),
                            description = description.trim(),
                            transportType = transport,
                            command = command.trim(),
                            args = argsList,
                            serverUrl = serverUrl.trim(),
                            headers = if (transport == McpTransportType.SSE) parsedHeaders.getOrThrow() else initial?.headers.orEmpty(),
                            headersReadError = null,
                            authMode = if (oauthEnabled) McpAuthMode.OAUTH else McpAuthMode.NONE,
                            oauthClientId = oauthClientId.trim(),
                            oauthAuthorizationEndpoint = oauthAuthorizationEndpoint.trim(),
                            oauthTokenEndpoint = oauthTokenEndpoint.trim(),
                            oauthScope = oauthScope.trim(),
                            oauthResource = oauthResource.trim(),
                            isEnabled = initial?.isEnabled ?: true,
                            isBuiltin = initial?.isBuiltin ?: false,
                        )
                        onSave(edited)
                    },
                    enabled = valid,
                ) {
                    Text(if (editing) "保存" else "添加")
                }
            } else {
                Button(
                    onClick = {
                        val list = parsedServers.getOrNull()
                        if (!list.isNullOrEmpty()) {
                            onSaveBatch(list)
                        }
                    },
                    enabled = parsedServers.isSuccess && parsedServers.getOrNull()?.isNotEmpty() == true,
                ) {
                    Text("导入 (${parsedServers.getOrNull()?.size ?: 0} 个服务)")
                }
            }
            }
        },
    ) {
        if (!editing) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            top.tianyan.app.ui.components.RuntimeFilterChip(selectedTab == 0, { selectedTab = 0 },
                label = { Text("表单模式") }, shape = MaterialTheme.shapes.small)
            top.tianyan.app.ui.components.RuntimeFilterChip(selectedTab == 1, { selectedTab = 1 },
                label = { Text("JSON 导入") }, shape = MaterialTheme.shapes.small)
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (editing || selectedTab == 0) {
                    RuntimeFilledTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("服务名称") },
                        placeholder = { Text("如: sqlite / github / fetch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RuntimeFilledTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("描述（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                Text("传输协议", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    top.tianyan.app.ui.components.RuntimeFilterChip(transport == McpTransportType.STDIO,
                        { transport = McpTransportType.STDIO }, label = { Text("Stdio") }, shape = MaterialTheme.shapes.small)
                    top.tianyan.app.ui.components.RuntimeFilterChip(transport == McpTransportType.SSE,
                        { transport = McpTransportType.SSE }, label = { Text("SSE / HTTP") }, shape = MaterialTheme.shapes.small)
                }
                Text(if (transport == McpTransportType.STDIO) "在沙箱中启动服务进程" else "连接远程 HTTP / SSE 服务",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (transport == McpTransportType.STDIO) {
                        RuntimeFilledTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("执行命令（如 npx / uvx / python3）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RuntimeFilledTextField(
                            value = argsStr,
                            onValueChange = { argsStr = it },
                            label = { Text("参数（空格分隔）") },
                            placeholder = { Text("-y @modelcontextprotocol/server-sqlite") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        RuntimeFilledTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("HTTP / SSE 端点 URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        initial?.headersReadError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        McpHeadersEditor(headersJson, { headersJson = it },
                            if (headerConflict) "自定义 Authorization 与 OAuth 不能同时启用" else parsedHeaders.exceptionOrNull()?.message)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("OAuth 2.0 + PKCE", style = MaterialTheme.typography.labelMedium)
                                Text("授权码模式；token 加密保存且不会导出", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = oauthEnabled, onCheckedChange = { oauthEnabled = it })
                        }
                        if (oauthEnabled) {
                            RuntimeFilledTextField(oauthClientId, { oauthClientId = it }, label = { Text("OAuth client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            RuntimeFilledTextField(oauthAuthorizationEndpoint, { oauthAuthorizationEndpoint = it }, label = { Text("Authorization endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            RuntimeFilledTextField(oauthTokenEndpoint, { oauthTokenEndpoint = it }, label = { Text("Token endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            RuntimeFilledTextField(oauthScope, { oauthScope = it }, label = { Text("Scope（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            RuntimeFilledTextField(oauthResource, { oauthResource = it }, label = { Text("Resource（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (editing && initial.env.isNotEmpty()) {
                        Text(
                            "环境变量（${initial.env.size} 项）与导入来源保持不变",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            } else {
                    Text(
                        "直接粘贴 Claude Desktop / Cursor 标准配置：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RuntimeFilledTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        label = { Text("MCP JSON 配置") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 280.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )

                    if (parsedServers.isSuccess) {
                        val list = parsedServers.getOrThrow()
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "已解析 ${list.size} 个服务: " + list.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        val errMsg = parsedServers.exceptionOrNull()?.message ?: "JSON 语法解析错误"
                        Text(
                            errMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        }
    }
}

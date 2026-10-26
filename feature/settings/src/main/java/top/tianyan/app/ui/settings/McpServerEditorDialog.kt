package top.tianyan.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.ui.components.RuntimeAlertDialog
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

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (editing) "编辑 MCP 服务" else "添加 MCP 服务", fontWeight = FontWeight.Bold)
                if (!editing) {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        divider = {},
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("📝 表单模式", style = MaterialTheme.typography.labelMedium) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("📋 JSON 导入", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editing || selectedTab == 0) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("服务名称") },
                        placeholder = { Text("如: sqlite / github / fetch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("描述（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "传输协议类型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val isStdio = transport == McpTransportType.STDIO
                            val isSse = transport == McpTransportType.SSE

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { transport = McpTransportType.STDIO },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isStdio) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "Stdio",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isStdio) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isStdio) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "沙箱进程",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = if (isStdio) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { transport = McpTransportType.SSE },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSse) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "SSE",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSse) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "远程 HTTP",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = if (isSse) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }

                    if (transport == McpTransportType.STDIO) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("执行命令（如 npx / uvx / python3）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = argsStr,
                            onValueChange = { argsStr = it },
                            label = { Text("参数（空格分隔）") },
                            placeholder = { Text("-y @modelcontextprotocol/server-sqlite") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("HTTP / SSE 端点 URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        McpHeadersEditor(headersJson, { headersJson = it },
                            if (headerConflict) "自定义 Authorization 与 OAuth 不能同时启用" else parsedHeaders.exceptionOrNull()?.message)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("OAuth 2.0 + PKCE", style = MaterialTheme.typography.labelMedium)
                                Text("授权码模式；token 加密保存且不会导出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = oauthEnabled, onCheckedChange = { oauthEnabled = it })
                        }
                        if (oauthEnabled) {
                            OutlinedTextField(oauthClientId, { oauthClientId = it }, label = { Text("OAuth client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(oauthAuthorizationEndpoint, { oauthAuthorizationEndpoint = it }, label = { Text("Authorization endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(oauthTokenEndpoint, { oauthTokenEndpoint = it }, label = { Text("Token endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(oauthScope, { oauthScope = it }, label = { Text("Scope（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(oauthResource, { oauthResource = it }, label = { Text("Resource（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (editing && initial.env.isNotEmpty()) {
                        Text(
                            "环境变量（${initial.env.size} 项）与导入来源保持不变",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "直接粘贴 Claude Desktop / Cursor 标准配置：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        label = { Text("MCP JSON 配置") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )

                    if (parsedServers.isSuccess) {
                        val list = parsedServers.getOrThrow()
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "✅ 成功解析 ${list.size} 个服务: " + list.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        val errMsg = parsedServers.exceptionOrNull()?.message ?: "JSON 语法解析错误"
                        Text(
                            "❌ $errMsg",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
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
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.components.RuntimeAlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeFilledTextField
import top.tianyan.app.ui.components.RuntimeRadioButton as RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import top.tianyan.app.ui.settings.LocalizedText as Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpAuthState
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.harness.mcp.server.AgentMcpAccess
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
    catalogViewModel: McpCatalogViewModel? = previewOrNull { koinViewModel() },
    toolPolicyViewModel: McpToolPolicyViewModel? = previewOrNull { koinViewModel() },
) {
    val servers by viewModel?.mcpServers?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val toggleOverrides by viewModel?.mcpToggleOverrides?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val connectionStates by viewModel?.mcpConnectionStates?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpAuthStates by viewModel?.mcpAuthStates?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpToolCounts by viewModel?.mcpToolCounts?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpToolPolicies by toolPolicyViewModel?.policies?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val cachedMcpTools by toolPolicyViewModel?.cachedTools?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpEnabledCounts by toolPolicyViewModel?.enabledCounts?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val browserGates by viewModel?.browserGates?.collectAsStateWithLifecycle() ?: previewStateOf(BrowserGateState())
    // 未安装的按需能力：id → 待安装组件名。用于禁用开关并给出"先去安装"的直达提示。
    val mcpInstallBlocked by catalogViewModel?.installBlocked?.collectAsStateWithLifecycle() ?: previewStateOf(emptyMap())
    val mcpInstallNotice by catalogViewModel?.installNotice?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val screenScope = rememberCoroutineScope()
    val screenContext = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var showLocalDiscoveryDialog by remember { mutableStateOf(false) }
    var viewingDetailServer by remember { mutableStateOf<McpServerConfig?>(null) }
    // 待确认删除的服务 id（破坏性操作二次确认）；McpServerConfig 非 Parcelable，仅保存 id
    var pendingDeleteServerId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeleteServer = pendingDeleteServerId?.let { id -> servers.firstOrNull { it.id == id } }

    // 进入设置页时：对齐组件探针（决定哪些能力"还没装"）并探测一次已启用服务的连通性
    LaunchedEffect(Unit) {
        catalogViewModel?.refresh()
        viewModel?.refreshMcpConnections(probeSleeping = false)
    }

    // 详情打开时加载缓存工具清单（不启动进程），供逐工具开关与风险覆盖。
    LaunchedEffect(viewingDetailServer?.id) {
        viewingDetailServer?.let { toolPolicyViewModel?.loadTools(it.id) }
    }

    // 未安装能力的提示只展示一次，避免每次重组都弹
    LaunchedEffect(mcpInstallNotice) {
        mcpInstallNotice?.let { message ->
            Toast.makeText(screenContext, message, Toast.LENGTH_LONG).show()
            catalogViewModel?.consumeInstallNotice()
        }
    }

    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RuntimeTopBar(
                title = androidx.compose.ui.res.stringResource(top.tianyan.app.feature.settings.R.string.personal_mcp_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)),
            contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                McpClientSettingsSection()
            }

            item {
                McpManagementActions(
                    count = servers.size,
                    onRefresh = { viewModel?.refreshMcpConnections() },
                    onDiscover = { showLocalDiscoveryDialog = true; viewModel?.discoverLocalMcpServers() },
                    onAdd = { showAddDialog = true },
                )
            }

            val builtinServers = servers.filter { it.isBuiltin }
            val customServers = servers.filterNot { it.isBuiltin }

            if (builtinServers.isNotEmpty()) {
                item(key = "mcp_section_builtin") {
                    McpSectionHeader(
                        title = "系统核心 MCP",
                        subtitle = "天衍内置系统级能力，默认关闭；开启后常驻本会话，harness 随时可调用（不参与 @ 唤醒）。" +
                            "标注「需先安装」的能力来自上游包，请到「工具中心 → MCP 能力仓库」按需安装",
                        count = builtinServers.size,
                    )
                }
                items(builtinServers, key = { it.id }) { server ->
                    val authBadge = authBadgeFor(mcpAuthStates[server.id] ?: McpAuthState.Unsupported)
                    McpServerItemCard(
                        server = server,
                        connectionState = connectionStates[server.id] ?: McpConnectionState.UNKNOWN,
                        error = viewModel?.mcpLastError(server.id),
                        togglePending = server.id in toggleOverrides,
                        requiredComponentName = mcpInstallBlocked[server.id],
                        toolCount = mcpEnabledCounts[server.id] ?: mcpToolCounts[server.id],
                        authLabel = authBadge?.first,
                        authOk = authBadge?.second ?: false,
                        onToggle = { enabled ->
                            if (catalogViewModel?.onToggleRequested(server.id, enabled) == true) {
                                viewModel?.toggleMcpServer(server.id, enabled)
                            }
                        },
                        onClickDetail = { viewingDetailServer = server },
                    )
                }
            }

            if (customServers.isNotEmpty()) {
                item(key = "mcp_section_custom") {
                    McpSectionHeader(
                        title = "自定义与三方 MCP",
                        subtitle = "通过「添加服务」导入的外部 MCP 服务，可 @ 唤醒",
                        count = customServers.size,
                    )
                }
                items(customServers, key = { it.id }) { server ->
                    val authBadge = authBadgeFor(mcpAuthStates[server.id] ?: McpAuthState.Unsupported)
                    McpServerItemCard(
                        server = server,
                        connectionState = connectionStates[server.id] ?: McpConnectionState.UNKNOWN,
                        error = viewModel?.mcpLastError(server.id),
                        togglePending = server.id in toggleOverrides,
                        requiredComponentName = null,
                        toolCount = mcpEnabledCounts[server.id] ?: mcpToolCounts[server.id],
                        authLabel = authBadge?.first,
                        authOk = authBadge?.second ?: false,
                        onToggle = { enabled -> viewModel?.toggleMcpServer(server.id, enabled) },
                        onClickDetail = { viewingDetailServer = server },
                    )
                }
            }

            item(key = "mcp_section_browser_gates") {
                BrowserGatesCard(gates = browserGates, viewModel = viewModel)
            }

            item(key = "mcp_section_agent_server") {
                AgentServerCard(
                    state = (viewModel?.agentServerState?.collectAsStateWithLifecycle() ?: previewStateOf(AgentServerState())).value,
                    viewModel = viewModel,
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddDialog) {
        McpServerEditorDialog(
            onDismiss = { showAddDialog = false },
            onSave = { newServer ->
                viewModel?.saveMcpServer(newServer)
                showAddDialog = false
            },
            onSaveBatch = { serversList ->
                serversList.forEach { viewModel?.saveMcpServer(it) }
                showAddDialog = false
            },
        )
    }

    if (showEditorDialog && editingServer != null) {
        McpServerEditorDialog(
            initial = editingServer,
            onDismiss = { showEditorDialog = false; editingServer = null },
            onSave = { updated ->
                viewModel?.saveMcpServer(updated)
                showEditorDialog = false
                editingServer = null
            },
        )
    }

    if (showLocalDiscoveryDialog) {
        LocalMcpDiscoveryDialog(
            state = (viewModel?.localMcpDiscovery?.collectAsStateWithLifecycle() ?: previewStateOf(LocalMcpDiscoveryState.Idle)).value,
            onDismiss = { showLocalDiscoveryDialog = false },
            onRetry = { viewModel?.discoverLocalMcpServers() },
            onAdd = { arg0 -> viewModel?.saveMcpServer(arg0) },
        )
    }

    pendingDeleteServer?.let { server ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteServerId = null },
            title = { Text("删除 MCP 服务", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除「${server.name}」吗？删除后将停止向智枢 Agent 注入该服务的工具能力；系统核心 MCP 重新开启即可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel?.deleteMcpServer(server.id)
                        pendingDeleteServerId = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteServerId = null }) { Text("取消") }
            },
        )
    }

    viewingDetailServer?.let { server ->
        McpServerDetailDialog(
            server = server,
            onDismiss = { viewingDetailServer = null },
            onEdit = if (server.isBuiltin) null else ({
                editingServer = server
                viewingDetailServer = null
                showEditorDialog = true
            }),
            cachedTools = cachedMcpTools[server.id].orEmpty(),
            toolPolicies = mcpToolPolicies[server.id].orEmpty(),
            onSetToolEnabled = { tool, enabled -> toolPolicyViewModel?.setEnabled(server.id, tool, enabled) },
            onSetToolRisk = { tool, risk -> toolPolicyViewModel?.setRiskOverride(server.id, tool, risk) },
            onDelete = {
                pendingDeleteServerId = server.id
                viewingDetailServer = null
            },
            onTest = {
                val res = viewModel?.testMcpServer(server) ?: Result.success(emptyList())
                if (res.isSuccess) toolPolicyViewModel?.loadTools(server.id)
                viewModel?.refreshMcpConnections()
                res
            },
            authState = mcpAuthStates[server.id] ?: McpAuthState.Unsupported,
            onAuthorize = {
                screenScope.launch {
                    runCatching { viewModel?.beginMcpAuthorization(server) }
                        .onSuccess {
                            runCatching { screenContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                                .onFailure { error -> Toast.makeText(screenContext, "授权启动失败：${error.message}", Toast.LENGTH_SHORT).show() }
                        }
                        .onFailure { Toast.makeText(screenContext, "授权启动失败：${it.message}", Toast.LENGTH_SHORT).show() }
                }
            },
            onLogout = { viewModel?.logoutMcpAuthorization(server.id) },
        )
    }
}

/** 仅展示已通过 MCP 协议握手的 127.0.0.1 服务；用户确认后才写入配置。 */
@Composable
private fun LocalMcpDiscoveryDialog(
    state: LocalMcpDiscoveryState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (McpServerConfig) -> Unit,
) {
    var addedIds by remember { mutableStateOf(emptySet<String>()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("探测本机 MCP", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "正在检查 127.0.0.1 的监听端口，并用 MCP 协议验证。仅显示真正可用的服务，不会自动添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    LocalMcpDiscoveryState.Idle, LocalMcpDiscoveryState.Scanning -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("正在探测本机服务…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is LocalMcpDiscoveryState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    is LocalMcpDiscoveryState.Results -> {
                        if (state.servers.isEmpty()) {
                            Text(
                                "没有发现可用的本机 MCP 服务。请确认服务已启动，并监听在 127.0.0.1。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.servers.forEach { detected ->
                                RuntimeCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentPadding = PaddingValues(10.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                detected.serverUrl,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "已验证 · ${detected.toolCount} 个工具",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (detected.server.id in addedIds) {
                                            Text("已添加", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    onAdd(detected.server)
                                                    addedIds = addedIds + detected.server.id
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            ) { Text("添加", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                LocalMcpDiscoveryState.Scanning -> Unit
                else -> Button(onClick = onRetry) { Text("重新探测") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 浏览器 MCP 安全门禁卡片：内置浏览器服务的危险能力开关（最小权限，默认全关）。
 * 门禁为引擎池级/工具快照级配置，修改后需重启应用生效。
 */
@Composable
private fun BrowserGatesCard(gates: BrowserGateState, viewModel: SettingsViewModel?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "浏览器 MCP 安全门禁",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "内置浏览器服务的危险能力开关，默认全部关闭；修改后需重启应用生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrowserGateRow(
                title = "远程连接",
                description = "允许局域网设备连接浏览器 MCP 服务（绑定 0.0.0.0）；关闭时仅限本机回环，仍强制 Bearer 认证",
                checked = gates.allowRemoteConnect,
                onCheckedChange = { arg0 -> viewModel?.setBrowserAllowRemoteConnect(arg0) },
            )
            BrowserGateRow(
                title = "页面 JS 执行",
                description = "允许 Agent 在页面上下文执行任意 JavaScript（browser.evaluate）",
                checked = gates.allowEvalJs,
                onCheckedChange = { arg0 -> viewModel?.setBrowserAllowEvalJs(arg0) },
            )
            BrowserGateRow(
                title = "注入式 Hook",
                description = "允许注入脚本拦截/改写页面网络请求与 JS 函数（log/block/redirect/mock）",
                checked = gates.allowHooks,
                onCheckedChange = { arg0 -> viewModel?.setBrowserAllowHooks(arg0) },
            )
            BrowserGateRow(
                title = "CDP 调试（断点）",
                description = "允许通过 DevTools 协议 attach 页面：真断点、暂停时读写变量、Worker 级请求拦截；开启会暴露本进程 DevTools socket（同设备其他应用理论上可连），仅在受控环境使用",
                checked = gates.allowCdp,
                onCheckedChange = { arg0 -> viewModel?.setBrowserAllowCdp(arg0) },
            )
            BrowserGateRow(
                title = "页面调试面板（vConsole）",
                description = "在每个页面注入 vConsole 浮动面板（console / 网络 / 存储），供人工浏览时排查；与 Agent 工具门禁无关，重启后对新开 tab 生效",
                checked = gates.allowVConsole,
                onCheckedChange = { arg0 -> viewModel?.setBrowserAllowVConsole(arg0) },
            )
        }
    }
}

@Composable
private fun BrowserGateRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * MCP 被控端（服务端）卡片：让外部 AI 客户端（Claude Desktop / Cursor 等）通过 MCP 控制本 App。
 *
 * 与上面的浏览器门禁不同，被控端是**独立端点**（默认 8890）且使用**持久化令牌**，
 * 配置变更由 AgentMcpBootstrap 监听偏好后即时重启，无需重启应用。
 */
@Composable
private fun AgentServerCard(state: AgentServerState, viewModel: SettingsViewModel?) {
    val context = LocalContext.current
    val lanIp = remember { detectLanIp() }
    // 配置变更后 Bootstrap 异步重启，延迟一拍再读实际监听状态，避免刚切换时显示过期状态
    var runtimeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.enabled, state.port, state.allowRemote, state.token) {
        runtimeTick++
        delay(700)
        runtimeTick++
    }
    val running = remember(runtimeTick) { AgentMcpAccess.running }
    val boundPort = remember(runtimeTick) { AgentMcpAccess.port ?: state.port }

    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "服务端（被控方）",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "让外部 AI 客户端（Claude Desktop / Cursor 等）通过 MCP 控制本机：读写工作区文件、执行沙箱命令、操作宿主应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserGateRow(
                title = "启用被控端",
                description = "在本机启动独立的 MCP 服务端点；关闭后立即停止监听",
                checked = state.enabled,
                onCheckedChange = { arg0 -> viewModel?.setAgentServerEnabled(arg0) },
            )

            AnimatedVisibility(visible = state.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrowserGateRow(
                        title = "允许局域网连接",
                        description = "绑定 0.0.0.0，同网段设备可连接；关闭时仅限本机回环，两种模式均强制 Bearer 认证",
                        checked = state.allowRemote,
                        onCheckedChange = { arg0 -> viewModel?.setAgentServerAllowRemote(arg0) },
                    )
                    BrowserGateRow(
                        title = "允许写入与执行",
                        description = "开放 write/edit/base/process/download 及 host 的写操作；关闭时仅提供 read 与只读 host/memory 动作",
                        checked = state.allowWriteTools,
                        onCheckedChange = { arg0 -> viewModel?.setAgentServerAllowWriteTools(arg0) },
                    )

                    // 端口
                    RuntimeFilledTextField(
                        value = portText,
                        onValueChange = { input ->
                            portText = input.filter { it.isDigit() }.take(5)
                            portText.toIntOrNull()?.let({ arg0 -> viewModel?.setAgentServerPort(arg0) })
                        },
                        label = { Text("监听端口") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                        supportingText = { Text("默认 8890；被占用时自动顺延 10 个相邻端口", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 运行状态与连接地址
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (running) successStatusColor() else MaterialTheme.colorScheme.outline),
                            )
                            Text(
                                if (running) "运行中 · 监听 ${boundPort}" else "已停止",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        val loopbackUrl = "http://127.0.0.1:$boundPort/mcp"
                        val lanUrl = lanIp?.let { "http://$it:$boundPort/mcp" }
                        CopyableLine(label = "本机地址", value = loopbackUrl, context = context)
                        if (state.allowRemote && lanUrl != null) {
                            CopyableLine(label = "局域网地址", value = lanUrl, context = context)
                        }
                    }

                    // 持久化令牌
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "访问令牌（Bearer）",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = state.token.ifBlank { "启用后自动生成" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("mcp_agent_token", state.token))
                                        Toast.makeText(context, "令牌已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = state.token.isNotBlank(),
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Copy, Modifier.size(14.dp), MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "令牌持久保存，外部客户端可长期复用；重置后需同步更新客户端配置",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel?.resetAgentServerToken() }) {
                                Text("重置令牌", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Text(
                        "外部客户端配置示例：在 MCP 配置中填写上述地址，并在请求头带 Authorization: Bearer <令牌>。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "启用后会显示一条常驻通知用于后台保活，避免 App 退到后台时连接被系统掐断。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 一行「标签 + 等宽值 + 复制按钮」，用于展示连接地址。 */
@Composable
private fun CopyableLine(label: String, value: String, context: Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("mcp_agent_url", value))
                Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(24.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(14.dp), MaterialTheme.colorScheme.outline)
        }
    }
}

/** 探测本机局域网 IPv4（用于展示可被外部设备访问的连接地址）；失败返回 null。 */
private fun detectLanIp(): String? = try {
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { !it.isLoopback && it.isUp }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
        ?.takeIf { it.isNotBlank() && it != "127.0.0.1" }
} catch (_: Exception) {
    null
}

/** OAuth 列表徽章：非 OAuth 服务不展示；返回 文案 to 是否成功态。 */
private fun authBadgeFor(state: McpAuthState): Pair<String, Boolean>? = when (state) {
    McpAuthState.Unsupported -> null
    McpAuthState.Unauthenticated -> "未授权" to false
    McpAuthState.Authorizing -> null
    is McpAuthState.Authorized -> "已授权" to true
    is McpAuthState.Error -> "授权失败" to false
}

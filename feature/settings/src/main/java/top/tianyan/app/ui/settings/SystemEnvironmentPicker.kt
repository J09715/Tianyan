package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*

@Composable
internal fun SystemEnvironmentPicker(localCount: Int, onInstallLocal: () -> Unit, viewModel: RemoteServerViewModel? = previewOrNull { koinViewModel() }) {
    if (viewModel == null) {
        Text("运行后端", style = MaterialTheme.typography.titleMedium)
        return
    }
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val active by viewModel.activeId.collectAsStateWithLifecycle()
    val configurationError by viewModel.configurationError.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    var addRemote by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<RemoteServer?>(null) }
    var editing by remember { mutableStateOf<RemoteServer?>(null) }
    var chooseRemote by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var localNetworkMessage by remember { mutableStateOf<String?>(null) }
    val localNetworkDenied = stringResource(R.string.settings_local_network_permission_required)
    val withLocalNetwork = rememberLocalNetworkPermission { localNetworkMessage = localNetworkDenied }
    fun selectServer(server: RemoteServer) {
        withLocalNetwork(server.host) {
            localNetworkMessage = null
            viewModel.select(server.id)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        configurationError?.let { NoticeBanner(text = it, isError = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("运行后端", style = MaterialTheme.typography.titleMedium)
            RuntimeButton(onClick = { choose = true }) { Text("添加系统") }
        }
        NoticeBanner(text = "主系统决定新对话、首页指标和工坊项目使用的环境。已有对话保留原环境，服务器文件不会自动复制到手机。")
        BackendChoiceCard("本地 PRoot", "在手机上运行 Linux，使用本机工作区。", RuntimeIconName.Linux, active == null) { viewModel.select(null) }
        BackendChoiceCard("SSH 远程运行时", servers.firstOrNull { it.id == active }?.name ?: "使用服务器的算力、工具链和项目目录。",
            RuntimeIconName.Server, active != null) {
            when (servers.size) {
                0 -> { viewModel.clear(); addRemote = true }
                1 -> selectServer(servers.single())
                else -> chooseRemote = true
            }
        }
        connection.message?.let { Text(it, color = if (connection.tested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        localNetworkMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("连接档案 (${servers.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            RuntimeTextButton(onClick = { viewModel.clear(); addRemote = true }) { Text("新增连接") }
        }
        if (servers.isEmpty()) Text("添加 SSH 连接后，可选择它作为主系统。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        servers.forEach { server ->
            ConnectionProfileCard(server, server.id == active, onSelect = { selectServer(server) },
                onEdit = { viewModel.clear(); editing = server }, onRemove = { remove = server })
        }
    }
    if (choose) RuntimeAlertDialog(
        onDismissRequest = { choose = false }, title = { Text("选择系统来源") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { choose = false; onInstallLocal() }) { Text("安装本地 Linux") }
            RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { choose = false; viewModel.clear(); addRemote = true }) { Text("连接服务器") }
        } },
        confirmButton = { RuntimeTextButton(onClick = { choose = false }) { Text("取消") } },
    )
    if (addRemote) RemoteServerDialog(viewModel) { addRemote = false }
    editing?.let { target -> key(target.id) { RemoteServerDialog(viewModel, target) { editing = null } } }
    if (chooseRemote) RuntimeAlertDialog(onDismissRequest = { chooseRemote = false }, title = { Text("选择连接档案") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            servers.forEach { server -> RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { selectServer(server); chooseRemote = false }) {
                Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
        } }, confirmButton = { RuntimeTextButton(onClick = { chooseRemote = false }) { Text("取消") } })
    remove?.let { server -> RuntimeAlertDialog(
        onDismissRequest = { remove = null }, title = { Text("移除 ${server.name}？") },
        text = { Text("仅移除手机保存的连接与凭证，服务器文件保留。已有会话需结束后重新选择环境。") },
        confirmButton = { RuntimeTextButton(onClick = { viewModel.remove(server.id); remove = null }) { Text("移除") } },
        dismissButton = { RuntimeTextButton(onClick = { remove = null }) { Text("取消") } },
    ) }
}


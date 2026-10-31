package top.tianyan.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.runtime.remote.RemoteSystemService
import top.tianyan.app.runtime.remote.RemoteSystemInspection
import top.tianyan.app.ui.components.*
import java.util.Locale

@Composable
fun HomeScreen(onNavigate: (MainDestination) -> Unit, onOpenTerminal: () -> Unit,
    onOpenToolCenter: () -> Unit = {}, viewModel: HomeViewModel? = null, onBack: () -> Unit = { onNavigate(MainDestination.Agent) }) {
    val repository = koinInject<RemoteServerRepository>()
    val active by repository.activeId.collectAsStateWithLifecycle()
    val servers by repository.servers.collectAsStateWithLifecycle()
    if (active == null) LocalHomeScreen(onNavigate, onOpenTerminal, onOpenToolCenter, viewModel ?: org.koin.compose.viewmodel.koinViewModel(), onBack)
    else key(active, servers.firstOrNull { it.id == active }) { RemoteHomeScreen(checkNotNull(active), onNavigate, onBack = onBack) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteHomeScreen(serverId: String, onNavigate: (MainDestination) -> Unit, service: RemoteSystemService? = previewOrNull { koinInject() }, onBack: () -> Unit = { onNavigate(MainDestination.Agent) }) {
    var refresh by remember { mutableIntStateOf(0) }
    val target = service?.let { runCatching { it.server(serverId) }.getOrNull() }
    val state by remember(serverId, refresh, service) {
        service?.observe(serverId, target) ?: kotlinx.coroutines.flow.flowOf(RemoteSystemInspection(loading = false))
    }.collectAsStateWithLifecycle(RemoteSystemInspection(loading = service != null))
    val metrics = state.metrics
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surface,
        topBar = { RuntimeTopBar(title = androidx.compose.ui.res.stringResource(top.tianyan.app.feature.home.R.string.home_dashboard_title), onBack = onBack, scrollBehavior = scrollBehavior, actions = {
            RuntimeIconButton(onClick = { refresh++ }, contentDescription = "刷新") { RuntimeIcon(RuntimeIconName.Refresh) }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)).verticalScroll(rememberScrollState()).padding(bottom = padding.calculateBottomPadding()).padding(runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 16.dp))
            .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("主系统概览", style = MaterialTheme.typography.titleMedium)
            SystemOverviewCard(target?.name ?: "服务器", target?.let { "${it.username}@${it.host}:${it.port}" }.orEmpty(),
                target?.workspace.orEmpty(), state.loading, state.error, metrics?.let { listOf(
                    "发行版" to it.distribution, "架构" to it.architecture,
                    "内存" to "${it.memoryUsedMb} / ${it.memoryTotalMb} MB",
                    "存储" to String.format(Locale.ROOT, "%.1f / %.1f GB", it.diskUsedGb, it.diskTotalGb),
                    "负载 · 1/5/15 分钟" to it.load, "进程" to it.processCount.toString(),
                    "运行时间" to "${it.uptimeSeconds / 3600} 小时", "内核" to it.kernel,
                ) }.orEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeButton(modifier = Modifier.weight(1f), onClick = { onNavigate(MainDestination.Agent) }) { Text("打开智枢") }
                RuntimeFilledTonalButton(modifier = Modifier.weight(1f), onClick = { onNavigate(MainDestination.Workspace) }) { Text("打开工坊") }
            }
            Text("指标采集自当前服务器，每 15 秒刷新。工作区文件与服务器保持一致。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

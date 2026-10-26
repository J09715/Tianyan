package top.tianyan.app.ui.workspace

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.ui.components.MainDestination

@Composable
fun WorkspaceScreen(onNavigate: (MainDestination) -> Unit, onOpenExplorer: (String) -> Unit,
    onOpenTerminal: (String) -> Unit, onOpenToolCenter: () -> Unit = {}, onOpenWorkshopSettings: () -> Unit = {},
    onOpenWorkflows: (String) -> Unit = {}, viewModel: WorkspaceViewModel? = null, onBack: () -> Unit = { onNavigate(MainDestination.Agent) }) {
    val repository = koinInject<RemoteServerRepository>()
    val active by repository.activeId.collectAsStateWithLifecycle()
    val servers by repository.servers.collectAsStateWithLifecycle()
    if (active == null) LocalWorkspaceScreen(onNavigate, onOpenExplorer, onOpenTerminal, onOpenToolCenter,
        onOpenWorkshopSettings, onOpenWorkflows, viewModel ?: org.koin.compose.viewmodel.koinViewModel(), onBack)
    else key(active, servers.firstOrNull { it.id == active }) { RemoteWorkspaceScreen(checkNotNull(active), onNavigate, onBack = onBack) }
}

package top.tianyan.app.ui.workspace

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import top.tianyan.app.core.tools.LocalPluginImportState
import top.tianyan.app.core.tools.PluginDebugPackager
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTextButton

internal class WorkspacePluginDebugAction(
    private val scope: CoroutineScope,
    private val checkAvailable: () -> Unit,
    private val beginImport: (File) -> Unit,
    private val pack: (File, () -> Unit) -> File = PluginDebugPackager::pack,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(manager: ToolManager, scope: CoroutineScope) : this(
        scope = scope,
        checkAvailable = {
            val state = manager.localPluginImportState.value
            check(state !is LocalPluginImportState.Reading && state !is LocalPluginImportState.Importing &&
                state !is LocalPluginImportState.PendingConfirmation) { "请先在插件中心完成或取消当前导入" }
        },
        beginImport = { manager.startLocalPluginImport(Uri.fromFile(it), it.name, requireAvailable = true) },
    )
    private var job: Job? = null
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false)

    fun start(project: WorkspaceProject) {
        if (busy) return
        busy = true
        job = scope.launch {
            var archive: File? = null
            var handedOff = false
            try {
                checkAvailable()
                withContext(dispatcher) {
                    val operation = currentCoroutineContext()
                    // Publish ownership inside the worker: prompt cancellation can discard a
                    // withContext return value before the caller can take ownership of it.
                    archive = pack(File(project.path)) { operation.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                beginImport(requireNotNull(archive))
                handedOff = true
                ready = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "插件打包失败"
            } finally {
                if (!handedOff) archive?.delete()
            }
        }.also { operation -> operation.invokeOnCompletion { busy = false } }
    }

    fun cancel() { job?.cancel() }

}

@Composable
internal fun rememberWorkspacePluginDebugAction(onOpenToolCenter: () -> Unit): WorkspacePluginDebugAction {
    val manager = koinInject<ToolManager>()
    val scope = rememberCoroutineScope()
    val action = remember(manager, scope) { WorkspacePluginDebugAction(manager, scope) }
    LaunchedEffect(action.ready) {
        if (action.ready) {
            action.ready = false
            onOpenToolCenter()
        }
    }
    if (action.busy) {
        RuntimeAlertDialog(
            onDismissRequest = action::cancel,
            title = { Text(stringResource(R.string.workspace_plugin_debug_import)) },
            text = { Text(stringResource(R.string.workspace_plugin_debug_preparing)) },
            confirmButton = {
                RuntimeTextButton(onClick = action::cancel) {
                    Text(stringResource(R.string.workspace_plugin_debug_cancel))
                }
            },
        )
    }
    action.error?.let { message ->
        RuntimeAlertDialog(
            onDismissRequest = { action.error = null },
            title = { Text(stringResource(R.string.workspace_plugin_debug_failed)) },
            text = { Text(message) },
            confirmButton = {
                RuntimeTextButton(onClick = { action.error = null }) {
                    Text(stringResource(R.string.workspace_plugin_debug_dismiss))
                }
            },
        )
    }
    return action
}

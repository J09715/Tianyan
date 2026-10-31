package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.ui.components.*

/** Gate before constructing local ViewModels, including navigation/search/deep-link entry points. */
@Composable
internal fun LocalSettingsGate(title: String, onBack: () -> Unit, unavailableContent: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val repository = koinInject<RemoteServerRepository>()
    val active by repository.activeId.collectAsStateWithLifecycle()
    if (active == null) content()
    else Scaffold(topBar = { RuntimeTopBar(title = title, onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NoticeBanner(text = "此功能管理手机上的 Linux、插件和配置，当前主系统无法使用。请先在「Linux 系统管理」中切回本地系统。")
            Text("服务器工具链可通过智枢命令管理；手机目录不会自动挂载或同步到服务器。", style = MaterialTheme.typography.bodyMedium)
            unavailableContent()
            RuntimeTextButton(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
fun LocalLlmScreen(onBack: () -> Unit, onOpenEngine: () -> Unit, viewModel: LocalLlmViewModel? = null) =
    LocalSettingsGate("本地 LLM", onBack) { LocalLlmContent(onBack, onOpenEngine, viewModel ?: koinViewModel()) }

@Composable
fun ToolDetailScreen(toolId: String, onBack: () -> Unit, onLaunchTerminal: (String) -> Unit,
    onStartAiHealing: (String, String, List<String>) -> Unit = { _, _, _ -> },
    onOpenPluginPage: (String) -> Unit = {}, viewModel: ToolDetailViewModel? = null) =
    LocalSettingsGate("本机插件", onBack) { LocalToolDetailContent(toolId, onBack, onLaunchTerminal, onStartAiHealing, onOpenPluginPage, viewModel ?: koinViewModel()) }

@Composable
fun CcSwitchScreen(onBack: () -> Unit, onLaunchTerminal: (String) -> Unit, onOpenBrowser: (String) -> Unit, viewModel: CcSwitchViewModel? = null) =
    LocalSettingsGate("本机智能体中枢", onBack) { LocalCcSwitchContent(onBack, onLaunchTerminal, onOpenBrowser, viewModel ?: koinViewModel()) }

@Composable
fun ToolCenterScreen(onBack: () -> Unit, onLaunchPty: (String) -> Unit, onOpenToolDetail: (String) -> Unit = {},
    onStartAiHealing: (String, String, List<String>) -> Unit = { _, _, _ -> }, viewModel: ToolCenterViewModel? = null,
    onOpenLibrary: () -> Unit = {}, onOpenBuiltinFeatures: () -> Unit = {}, onOpenWebPlugins: () -> Unit = {}) =
    LocalSettingsGate("插件中心", onBack, unavailableContent = {
        RuntimeTextButton(onClick = onOpenBuiltinFeatures) { Text(androidx.compose.ui.res.stringResource(top.tianyan.app.feature.settings.R.string.plugin_center_builtin)) }
    }) { LocalToolCenterContent(onBack, onLaunchPty, onOpenToolDetail, onStartAiHealing, viewModel ?: koinViewModel(), onOpenLibrary, onOpenBuiltinFeatures, onOpenWebPlugins) }

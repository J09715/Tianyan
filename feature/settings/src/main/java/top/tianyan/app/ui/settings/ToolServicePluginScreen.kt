package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.util.concurrent.atomic.AtomicReference
import org.koin.compose.koinInject
import top.tianyan.app.feature.settings.R
import top.tianyan.app.plugin.ui.web.WebPagePlugin
import top.tianyan.app.plugin.ui.web.WebPluginAssetLease
import top.tianyan.app.plugin.ui.web.WebPluginHost
import top.tianyan.app.plugin.ui.web.WebPluginServiceCapabilities

/**
 * Read-only plugin page over an installed tool service. The page owns only a bounded pinned
 * snapshot plus two bounded read-only actions over that same service: it never starts or stops the
 * tool service, never sees the loopback address or any credential, and grants no other capability.
 */
@Composable
fun ToolServicePluginScreen(toolId: String, onBack: () -> Unit) {
    val sources = koinInject<PackagedWebPluginSources>()
    val lease = remember(toolId, sources) { AtomicReference<WebPluginAssetLease?>(null) }
    val plugin = remember(toolId, sources, lease) {
        WebPagePlugin(
            id = HOST_PLUGIN_ID, version = HOST_PLUGIN_VERSION,
            title = { toolId },
            description = { stringResource(R.string.web_tool_service_scope) },
            capabilities = listOf(
                WebPluginServiceCapabilities.request { lease.get() },
                WebPluginServiceCapabilities.stream { lease.get() },
            ),
            openSource = { _ -> sources.openToolService(toolId).also { lease.set(it) } },
            loadAssets = { error("工具服务页面只接受运行时来源") },
        )
    }
    WebPluginHost(plugin, onBack)
}

private const val HOST_PLUGIN_ID = "tianyan.tool-service"
private const val HOST_PLUGIN_VERSION = "1.0.0"

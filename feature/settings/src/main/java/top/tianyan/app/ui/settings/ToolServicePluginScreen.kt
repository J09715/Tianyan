package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.core.tools.ToolManager
import androidx.compose.ui.res.stringResource
import java.util.concurrent.atomic.AtomicReference
import org.koin.compose.koinInject
import top.tianyan.app.feature.settings.R
import top.tianyan.app.plugin.ui.web.WebPagePlugin
import top.tianyan.app.plugin.ui.web.WebPluginAssetLease
import top.tianyan.app.plugin.ui.web.WebPluginHost
import top.tianyan.app.plugin.ui.web.WebPluginServiceCapabilities

/**
 * Page over the installed service. Authenticated backends may offer individually confirmed writes;
 * legacy services retain the existing read-only path. Closing a page never stops a managed service.
 */
@Composable
fun ToolServicePluginScreen(toolId: String, onBack: () -> Unit) {
    val sources = koinInject<PackagedWebPluginSources>()
    val tools = koinInject<ToolManager>()
    val writable by produceState<Boolean?>(null, toolId, tools) {
        value = runCatching { tools.serviceSpec(toolId)?.authenticated == true }.getOrDefault(false)
    }
    if (writable == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RuntimeCircularProgressIndicator() }
        return
    }
    val writes = writable == true
    val lease = remember(toolId, sources) { AtomicReference<WebPluginAssetLease?>(null) }
    val plugin = remember(toolId, sources, lease, writes) {
        WebPagePlugin(
            id = HOST_PLUGIN_ID, version = HOST_PLUGIN_VERSION,
            title = { toolId },
            description = { stringResource(if (writes) R.string.service_panel_auth else R.string.web_tool_service_scope) },
            capabilities = listOf(
                WebPluginServiceCapabilities.request { lease.get() },
                WebPluginServiceCapabilities.stream { lease.get() },
            ) + if (writes) listOf(WebPluginServiceCapabilities.write { lease.get() }) else emptyList(),
            openSource = { _ -> sources.openService(toolId, allowStart = !writes, requireWrites = writes).also { lease.set(it) } },
            loadAssets = { error("工具服务页面只接受运行时来源") },
        )
    }
    WebPluginHost(plugin, onBack)
}

private const val HOST_PLUGIN_ID = "tianyan.tool-service"
private const val HOST_PLUGIN_VERSION = "1.0.0"

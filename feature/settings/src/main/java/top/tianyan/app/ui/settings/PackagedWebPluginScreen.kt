package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.settings.R
import top.tianyan.app.plugin.api.PluginApiRange
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.ui.web.WebPagePlugin
import top.tianyan.app.plugin.ui.web.WebPluginCapability
import top.tianyan.app.plugin.ui.web.WebPluginHost
import top.tianyan.app.plugin.ui.web.WebPluginAssetLease
import top.tianyan.app.plugin.ui.web.WebPluginServiceCapabilities
import java.util.concurrent.atomic.AtomicReference
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.runtime.bridge.HostBridge
import top.tianyan.app.runtime.bridge.PluginHostScope

/** Restored routes are checked against the currently selected package identity and exact version. */
@Composable
fun PackagedWebPluginScreen(toolId: String, version: String, pluginId: String, onBack: () -> Unit, model: WebPluginsViewModel = koinViewModel()) {
    val bridge = koinInject<HostBridge>()
    val sources = koinInject<PackagedWebPluginSources>()
    val packages by model.packages.collectAsStateWithLifecycle()
    val expected = packages.find { it.id == toolId && it.version == version && it.plugin?.id == pluginId }
    if (expected == null) {
        Scaffold(topBar = { RuntimeTopBar(stringResource(R.string.web_packages_title), onBack) }) { padding ->
            Text(stringResource(R.string.web_packages_unavailable), Modifier.padding(padding).padding(16.dp))
        }
    } else {
        val plugin = remember(expected, model.repository, bridge, sources) {
            val spec = expected.plugin!!
            val proot = spec.webSource == "PROOT_STATIC"
            val service = spec.serviceToolId
            val lease = AtomicReference<WebPluginAssetLease?>(null)
            WebPagePlugin(
                id = spec.id, version = expected.version,
                title = { expected.name }, description = {
                    when {
                        proot -> expected.description + "\n" + stringResource(R.string.web_packages_proot_source)
                        service != null -> expected.description + "\n" +
                            stringResource(R.string.web_packages_service_source, service)
                        else -> expected.description
                    }
                },
                capabilities = spec.capabilities.filter { it.name != "SERVICE_WRITE" }.map { declared ->
                    val host = requireNotNull(PACKAGE_HOST_ACTIONS.find { it.capability == declared.name && it.target == declared.target }) {
                        "不支持的 Web 插件能力"
                    }
                    WebPluginCapability(PluginCapability(host.action, host.target),
                        title = { stringResource(host.title) },
                        description = { stringResource(host.detail) },
                    ) { _, identity, call ->
                        val caller = currentCoroutineContext()
                        val scope = PluginHostScope(identity.pluginId, identity.version, identity.generation,
                            identity.origin, identity.environment)
                        val available = { caller.isActive && model.repository.isCurrent(expected) }
                        when (host.action) {
                            "host.app-info" -> {
                                val info = bridge.readPluginAppInfo(scope, available)
                                JsonObject(Json.parseToJsonElement(info).jsonObject +
                                    ("pluginApi" to JsonPrimitive(PluginApiRange.CURRENT))).toString()
                            }
                            "host.clipboard-write" -> bridge.writePluginClipboard(scope, requireNotNull(call.text), available)
                            "host.install-apk" -> bridge.installPluginApk(scope, requireNotNull(call.text), available)
                            else -> bridge.readPluginLogcat(scope, available)
                        }
                    }
                } + if (service != null) listOf(
                    WebPluginServiceCapabilities.request { lease.get() },
                    WebPluginServiceCapabilities.stream { lease.get() },
                ) + if (spec.capabilities.any { it.name == "SERVICE_WRITE" }) {
                    listOf(WebPluginServiceCapabilities.write { lease.get() })
                } else emptyList() else emptyList(),
                isAvailable = { model.repository.isCurrent(expected) },
                openSource = when {
                    proot -> { { _ -> sources.openProot(expected) } }
                    service != null -> { { _ -> sources.openService(service, allowStart = spec.webSource == "PROOT_SERVICE",
                        requireWrites = spec.capabilities.any { it.name == "SERVICE_WRITE" }).also { lease.set(it) } } }
                    else -> null
                },
                loadAssets = { model.repository.snapshot(expected) },
            )
        }
        WebPluginHost(plugin, onBack)
    }
}

/**
 * Declared package capability → page action, the target it must name, and its confirmation copy.
 * Whether a grant may be cached is decided centrally by `PluginCapabilityInput.ONE_SHOT`, so the
 * write actions never silently gain a 5-minute grant by being listed here.
 */
private data class PackageHostAction(
    val capability: String, val action: String, val target: String, val title: Int, val detail: Int,
)

private val PACKAGE_HOST_ACTIONS = listOf(
    PackageHostAction("APP_INFO", "host.app-info", "application",
        R.string.web_packages_permission_title, R.string.web_packages_app_info),
    PackageHostAction("CLIPBOARD_WRITE", "host.clipboard-write", "clipboard",
        R.string.web_packages_clipboard_title, R.string.web_packages_clipboard_write),
    PackageHostAction("INSTALL_APK", "host.install-apk", "apk",
        R.string.web_packages_install_apk_title, R.string.web_packages_install_apk_detail),
    PackageHostAction("LOGCAT", "host.logcat", "logcat",
        R.string.web_packages_logcat_title, R.string.web_packages_logcat_detail),
)

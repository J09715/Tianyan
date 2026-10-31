package top.tianyan.app.plugin.ui.web

import android.content.Context
import androidx.compose.runtime.Composable
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.runtime.PluginCapabilityIdentity
import top.tianyan.app.plugin.runtime.PluginCapabilityCall
import top.tianyan.app.plugin.ui.*
import top.tianyan.app.ui.components.RuntimeIconName

/** Host-registered operation. An untrusted document cannot install an executor. */
class WebPluginCapability(
    val capability: PluginCapability,
    val title: @Composable () -> String,
    val description: @Composable () -> String,
    allowSession: Boolean = true,
    val stream: (suspend (Context, PluginCapabilityIdentity, PluginCapabilityCall, suspend (String) -> Boolean) -> Unit)? = null,
    val execute: (suspend (Context, PluginCapabilityIdentity, PluginCapabilityCall) -> String)? = null,
) {
    init {
        require(execute != null || stream != null) { "A capability needs a single-shot or stream executor" }
    }

    val allowSession = allowSession && capability !in top.tianyan.app.plugin.runtime.PluginCapabilityInput.ONE_SHOT
}

/** Trusted static Web adapter using the existing PAGE lifecycle, independent of .txplugin. */
class WebPagePlugin(
    val id: String,
    val version: String,
    val title: @Composable () -> String,
    val description: @Composable () -> String,
    capabilities: List<WebPluginCapability>,
    val isAvailable: () -> Boolean = { true },
    val openSource: (suspend (Context) -> WebPluginAssetLease)? = null,
    val loadAssets: suspend (Context) -> Map<String, ByteArray>,
) {
    val capabilities: List<WebPluginCapability> = java.util.Collections.unmodifiableList(capabilities.toList())
    init {
        require(this.capabilities.size <= 32)
        require(this.capabilities.map { it.capability }.distinct().size == this.capabilities.size)
        require(this.capabilities.all { it.capability.action.length in 1..128 && it.capability.target.length in 1..256 })
    }

    fun create() = NativePagePlugin(
        descriptor = PluginDescriptor(id, version, extensions = listOf(PluginExtension("main", PluginExtensionPoint.PAGE))),
        pages = listOf(NativePluginPage("main") { WebPluginPage(this, it) }),
        title = title,
        description = description,
        entries = listOf(NativePluginEntry("settings", "main", PluginEntryLocation.SETTINGS, title)),
        icon = RuntimeIconName.Globe,
    )
}

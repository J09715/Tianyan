package top.tianyan.app.plugin.ui.web

import android.content.Context
import android.os.Build
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.plugin.api.PluginApiRange
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.ui.R

/** Inert at registration; native confirmation permits the example's read operation. */
object WebPluginDemo {
    val page = PluginExtensionRef("tianyan.web-demo", "main")
    val managedPage = PluginExtensionRef("tianyan.web-service-demo", "main")
    val prootPage = PluginExtensionRef("tianyan.web-proot-demo", "main")
    fun create(proot: Boolean = false, openSource: (suspend (Context) -> WebPluginAssetLease)? = null) = WebPagePlugin(
        id = if (proot) prootPage.pluginId else if (openSource == null) page.pluginId else managedPage.pluginId, version = "1.0.0",
        title = { stringResource(if (proot) R.string.ui_web_proot_title else if (openSource == null) R.string.ui_web_plugin_title else R.string.ui_web_service_title) },
        description = { stringResource(if (proot) R.string.ui_web_proot_description else if (openSource == null) R.string.ui_web_plugin_description else R.string.ui_web_service_description) },
        capabilities = listOf(WebPluginCapability(
            PluginCapability("host.app-info", "application"),
            title = { stringResource(R.string.ui_web_plugin_permission) },
            description = { stringResource(R.string.ui_web_plugin_permission_detail) },
        ) { context, _, _ ->
            buildJsonObject {
                put("package", context.packageName)
                put("androidApi", Build.VERSION.SDK_INT)
                put("pluginApi", PluginApiRange.CURRENT)
            }.toString()
        }),
        openSource = openSource,
        loadAssets = { assets(it) },
    ).create()

    fun assets(context: Context): Map<String, ByteArray> = listOf("index.html", "plugin.js", "plugin.css").associateWith { name ->
        context.assets.open("tianyan-web-demo/$name").use { it.readBytes() }
    }
}

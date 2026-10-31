package top.tianyan.app.ui.navigation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.tianyan.app.core.datastore.PluginPreferences
import top.tianyan.app.plugin.runtime.PluginController
import top.tianyan.app.plugin.runtime.PluginRuntime
import top.tianyan.app.plugin.runtime.PluginSelectionStore
import top.tianyan.app.plugin.ui.NativePagePlugin
import top.tianyan.app.plugin.ui.NativePluginPageRegistry
import top.tianyan.app.plugin.ui.PluginPages
import top.tianyan.app.plugin.ui.web.WebPluginDemo
import top.tianyan.app.plugin.ui.web.WebPluginAssetLease
import top.tianyan.app.runtime.service.LoopbackWebAssetService
import top.tianyan.app.ui.browser.BrowserPlugin
import top.tianyan.app.ui.terminal.TerminalPlugin

/** Explicit host assembly; factories register descriptions and do not start PTY or WebView engines. */
class NativePluginPlatform(prootSource: suspend (android.content.Context) -> WebPluginAssetLease = { error("PRoot source not configured") }) {
    val plugins: List<NativePagePlugin> = listOf(TerminalPlugin.create(), BrowserPlugin.create(), WebPluginDemo.create(),
        WebPluginDemo.create { context ->
            val service = LoopbackWebAssetService.start(WebPluginDemo.assets(context))
            try {
                WebPluginAssetLease(service.source.snapshot(), service.source.isAvailable, service.source.release)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { service.close() }
                throw failure
            }
        },
        WebPluginDemo.create(proot = true, openSource = prootSource),
    )
    val pages: PluginPages = NativePluginPageRegistry(plugins)

    fun controller(preferences: PluginPreferences): PluginController = PluginRuntime(
        pages.catalog, plugins.associate { it.descriptor.id to it.activate },
        object : PluginSelectionStore {
            override suspend fun disabledPlugins() = preferences.disabledPlugins()
            override suspend fun setEnabled(id: String, enabled: Boolean) = preferences.setEnabled(id, enabled)
        },
    )
}

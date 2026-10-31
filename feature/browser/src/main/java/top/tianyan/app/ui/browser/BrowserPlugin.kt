package top.tianyan.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.browser.R
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.ui.NativePagePlugin
import top.tianyan.app.plugin.ui.NativePluginEntry
import top.tianyan.app.plugin.ui.NativePluginPage
import top.tianyan.app.plugin.ui.PluginEntryLocation

private val LocalBrowserPluginOwner = staticCompositionLocalOf<BrowserViewModel?> { null }

/** Keep the activity-owned browser model shared by the chat pane and standalone page. */
@Composable
fun BrowserPluginOwner(viewModel: BrowserViewModel, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBrowserPluginOwner provides viewModel, content = content)
}

object BrowserPlugin {
    val page = PluginExtensionRef("tianyan.browser", "main")
    const val SHOW_EXIT_ARGUMENT = "showExit"

    fun create() = NativePagePlugin(
        descriptor = PluginDescriptor(
            id = page.pluginId, version = "1.0.0",
            extensions = listOf(PluginExtension(page.extensionId, PluginExtensionPoint.PAGE)),
        ),
        title = { stringResource(R.string.fbrowser_plugin_title) },
        icon = top.tianyan.app.ui.components.RuntimeIconName.Globe,
        description = { stringResource(R.string.fbrowser_plugin_description) },
        entries = listOf(
            NativePluginEntry("open", "main", PluginEntryLocation.MENU) { stringResource(R.string.fbrowser_plugin_title) },
        ),
        pages = listOf(NativePluginPage(page.extensionId) { request ->
            val viewModel = LocalBrowserPluginOwner.current ?: koinViewModel<BrowserViewModel>()
            if (request.embedded) BrowserPane(viewModel = viewModel, onExit = request.onBack.takeIf { request.arguments[SHOW_EXIT_ARGUMENT] != "false" })
            else BrowserScreen(viewModel = viewModel, onBack = request.onBack)
        }),
    )
}

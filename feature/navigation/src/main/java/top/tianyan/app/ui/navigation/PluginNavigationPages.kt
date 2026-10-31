package top.tianyan.app.ui.navigation

import androidx.compose.runtime.Composable
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.ui.PluginPageHost
import top.tianyan.app.plugin.ui.PluginPages
import top.tianyan.app.ui.terminal.TerminalPlugin
import top.tianyan.app.ui.browser.BrowserPlugin
import top.tianyan.app.ui.browser.BrowserViewModel
import top.tianyan.app.plugin.runtime.PluginController

/** New plugins use this generic destination; existing saved terminal destinations remain valid. */
@Composable
internal fun RegisteredPluginPage(pages: PluginPages, destination: PluginPageDestination, onBack: () -> Unit) {
    if (destination.pageId == "main" && destination.arguments.keys == setOf("web.package.id", "web.package.version")) {
        top.tianyan.app.ui.settings.PackagedWebPluginScreen(
            destination.arguments.getValue("web.package.id"), destination.arguments.getValue("web.package.version"), destination.pluginId, onBack)
        return
    }
    if (destination.pageId == "main" && destination.arguments.keys == setOf("web.tool.id")) {
        top.tianyan.app.ui.settings.ToolServicePluginScreen(destination.arguments.getValue("web.tool.id"), onBack)
        return
    }
    PluginPageHost(
        pages = pages,
        reference = PluginExtensionRef(destination.pluginId, destination.pageId),
        arguments = destination.arguments,
        onBack = onBack,
    )
}

/** Compatibility adapter shared by the old terminal route and the chat's embedded pane. */
@Composable
internal fun TerminalPluginPage(pages: PluginPages, project: String, embedded: Boolean = false, onBack: () -> Unit) {
    PluginPageHost(
        pages = pages,
        reference = TerminalPlugin.page,
        arguments = mapOf(TerminalPlugin.PROJECT_ARGUMENT to project),
        embedded = embedded,
        onBack = onBack,
    )
}

@Composable
internal fun BrowserPluginPage(pages: PluginPages, embedded: Boolean = false, onBack: (() -> Unit)?) {
    PluginPageHost(
        pages = pages, reference = BrowserPlugin.page, embedded = embedded,
        arguments = mapOf(BrowserPlugin.SHOW_EXIT_ARGUMENT to (onBack != null).toString()), onBack = onBack ?: {},
    )
}

internal fun browserPluginBack(controller: PluginController, model: BrowserViewModel): Boolean =
    controller.acquire(BrowserPlugin.page.pluginId)?.use { model.handleBackImmediate() } ?: false

internal fun selectedPluginPage(destination: androidx.navigation3.runtime.NavKey?): PluginExtensionRef? = when (destination) {
    is PluginPageDestination -> PluginExtensionRef(destination.pluginId, destination.pageId)
    is TerminalDestination -> TerminalPlugin.page
    is BrowserDestination -> BrowserPlugin.page
    else -> null
}

@Composable
internal fun ManagedPluginsScreen(platform: NativePluginPlatform, controller: PluginController, onOpen: (PluginExtensionRef) -> Unit, onBack: () -> Unit) {
    top.tianyan.app.plugin.ui.PluginManagerScreen(platform.plugins, controller, onOpen, onBack)
}

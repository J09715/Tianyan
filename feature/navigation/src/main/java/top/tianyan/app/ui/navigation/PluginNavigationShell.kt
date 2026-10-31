package top.tianyan.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.runtime.PluginController
import top.tianyan.app.plugin.ui.LocalPluginController
import top.tianyan.app.plugin.ui.PluginEntryLinks
import top.tianyan.app.plugin.ui.PluginEntryLocation
import top.tianyan.app.ui.browser.BrowserPluginOwner
import top.tianyan.app.ui.browser.BrowserViewModel
import top.tianyan.app.ui.components.LocalAppNavigation
import top.tianyan.app.ui.components.LocalPluginMenuContent
import top.tianyan.app.ui.components.LocalOpenPluginManager
import top.tianyan.app.ui.components.LocalRuntimeWidthClass

@Composable
internal fun PluginNavigationShell(
    platform: NativePluginPlatform, controller: PluginController, browserViewModel: BrowserViewModel,
    onOpen: (PluginExtensionRef) -> Unit, onOpenManager: () -> Unit, onOpenNavigation: () -> Unit,
    selectedPage: PluginExtensionRef? = null, content: @Composable () -> Unit,
) {
    LaunchedEffect(controller) { controller.initialize() }
    CompositionLocalProvider(
        LocalPluginController provides controller,
        // Rail or docked sidebar already exposes navigation; top bars drop their menu button.
        LocalAppNavigation provides onOpenNavigation.takeUnless { LocalRuntimeWidthClass.current.hasPersistentNavigation },
        top.tianyan.app.ui.components.LocalPluginDestinationActive provides (selectedPage != null),
        LocalPluginMenuContent provides {
            PluginEntryLinks(platform.plugins, controller, PluginEntryLocation.MENU, onOpen, selectedPage)
        },
        LocalOpenPluginManager provides onOpenManager,
    ) { BrowserPluginOwner(browserViewModel, content) }
}

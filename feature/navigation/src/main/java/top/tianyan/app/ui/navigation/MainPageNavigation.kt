package top.tianyan.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import top.tianyan.app.ui.components.MainDestination

/** Covering pages own their horizontal gestures; only the chat root can swipe open navigation. */
internal fun List<NavKey>.canSwipeOpenNavigation(): Boolean = lastOrNull() == AgentDestination

/** All secondary root pages share the caller's history and enter/exit transitions. */
internal fun MutableList<NavKey>.openMainOverlay(destination: MainDestination): Boolean {
    val page = when (destination) {
        MainDestination.Workspace -> WorkspaceDestination
        MainDestination.Home -> HomeDestination
        MainDestination.Bots -> BotsDestination
        MainDestination.Settings -> SettingsDestination
        else -> return false
    }
    val existingSettings = if (page == SettingsDestination) indexOfLast { it == page } else -1
    if (existingSettings >= 0) while (lastIndex > existingSettings) removeAt(lastIndex)
    if (lastOrNull() != page) add(page)
    return true
}

/** Reverse mapping for the navigation drawer: only root overlay pages change the selected tab. */
internal fun NavKey?.toMainDestination(): MainDestination = when (this) {
    HomeDestination -> MainDestination.Home
    WorkspaceDestination -> MainDestination.Workspace
    BotsDestination -> MainDestination.Bots
    SettingsDestination -> MainDestination.Settings
    else -> MainDestination.Agent
}

/** Notification/deep-link settings entries use the same stack as in-app navigation. */
internal fun MutableList<NavKey>.openSettingsPath(vararg pages: NavKey) {
    if (pages.isNotEmpty() && lastOrNull() == pages.last()) return
    openMainOverlay(MainDestination.Settings)
    pages.forEach { if (lastOrNull() != it) add(it) }
}

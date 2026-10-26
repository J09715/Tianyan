package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R

/** Root pages open the same app drawer; secondary pages keep their back navigation. */
val LocalAppNavigation = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun AppNavigationSection(
    selected: MainDestination,
    onNavigate: (MainDestination) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pluginMenu = LocalPluginMenuContent.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuntimeNavigationTile(stringResource(R.string.components_drawer_tab_system), MainDestination.Home.icon,
                { onNavigate(MainDestination.Home) }, Modifier.weight(1f),
                active = selected == MainDestination.Home && !LocalPluginDestinationActive.current)
            RuntimeNavigationTile(stringResource(R.string.components_tools), RuntimeIconName.Wrench,
                onOpenTools, Modifier.weight(1.5f))
        }
        if (pluginMenu != null) pluginMenu() else {
            RuntimeNavigationTile(stringResource(R.string.components_local_terminal), RuntimeIconName.Terminal,
                onOpenTerminal, Modifier.fillMaxWidth())
        }
    }
}

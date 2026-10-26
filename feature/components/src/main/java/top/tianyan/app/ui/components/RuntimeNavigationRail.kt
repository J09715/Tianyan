package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val RuntimeNavigationRailWidth = 80.dp

data class RuntimeRailItem(
    val label: String,
    val icon: RuntimeIconName,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Icon rail for Medium/Expanded windows. Top items scroll on short (landscape) windows while the
 * footer stays pinned, so no destination becomes unreachable.
 */
@Composable
fun RuntimeNavigationRail(
    header: List<RuntimeRailItem>,
    items: List<RuntimeRailItem>,
    footer: List<RuntimeRailItem>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(RuntimeNavigationRailWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxHeight().windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                header.forEach { RailItem(it) }
                Spacer(Modifier.height(12.dp))
                items.forEach { RailItem(it) }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                footer.forEach { RailItem(it) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RailItem(item: RuntimeRailItem) {
    NavigationRailItem(
        selected = item.selected,
        onClick = item.onClick,
        icon = { RuntimeIcon(item.icon, Modifier.size(22.dp)) },
        label = {
            Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        alwaysShowLabel = true,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

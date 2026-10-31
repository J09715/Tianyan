package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSegmentedControl

@Composable
internal fun ChatCompanionPane(
    browserSelected: Boolean, onSelectBrowser: (Boolean) -> Unit,
    workspace: String, terminal: @Composable (String) -> Unit,
    browser: (@Composable ((() -> Unit)?) -> Unit)?, modifier: Modifier = Modifier
) {
    RuntimeCard(
        modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (browser != null) {
                RuntimeSegmentedControl(
                    items = listOf(
                        RuntimeIconName.Terminal to stringResource(R.string.chat_pane_terminal),
                        RuntimeIconName.Globe to stringResource(R.string.chat_pane_browser)
                    ),
                    selectedIndex = if (browserSelected) 1 else 0,
                    onSelect = { onSelectBrowser(it == 1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) { item, active ->
                    val tint =
                        if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        Modifier.semantics { selected = active },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RuntimeIcon(item.first, Modifier.size(18.dp), tint)
                        Text(
                            item.second, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(Modifier
                .weight(1f)
                .fillMaxWidth()) {
                if (browserSelected && browser != null) browser(null) else terminal(workspace)
            }
        }
    }
}

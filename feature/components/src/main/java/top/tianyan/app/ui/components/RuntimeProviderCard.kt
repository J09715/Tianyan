package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R

/** The whole preset is the selection target; a separate button must not squeeze its name. */
@Composable
fun RuntimeProviderCard(
    providerId: String,
    name: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.components_provider_preset),
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth(), onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProviderBadge(providerIdOrName = providerId, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(20.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

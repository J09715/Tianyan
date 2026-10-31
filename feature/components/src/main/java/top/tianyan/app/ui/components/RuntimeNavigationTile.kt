package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared destination: one touch target, stable icon/label alignment and selected state. */
@Composable
fun RuntimeNavigationTile(title: String, icon: RuntimeIconName, onClick: () -> Unit,
    modifier: Modifier = Modifier, active: Boolean = false, showChevron: Boolean = false) {
    val tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    RuntimeCard(
        modifier = modifier.heightIn(min = 48.dp).semantics { selected = active },
        onClick = onClick, shape = MaterialTheme.shapes.medium,
        containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuntimeIcon(icon, Modifier.size(20.dp), tint)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showChevron) RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp), tint)
        }
    }
}

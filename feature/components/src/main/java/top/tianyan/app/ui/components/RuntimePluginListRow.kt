package top.tianyan.app.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A quiet inventory row inside a shared card; the whole row opens its details. */
@Composable
fun RuntimePluginListRow(title: String, subtitle: String, icon: RuntimeIconName,
    modifier: Modifier = Modifier, detail: String = "", status: String = "", onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null) {
    val interaction = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(role = Role.Button, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else Modifier
    Row(modifier.heightIn(min = 76.dp)
        .then(interaction)
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            RuntimeIcon(icon, Modifier.padding(10.dp).size(20.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) Text(status, Modifier.widthIn(max = 76.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClick != null) RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

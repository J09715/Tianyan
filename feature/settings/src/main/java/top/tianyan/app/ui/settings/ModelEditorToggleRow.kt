package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.*

/** Secondary control inside a form card; the parent card owns the outer surface. */
@Composable
internal fun EditorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = SettingsDensity.rowMinHeight).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
                Text(title, style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = settingsSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RuntimeSwitch(checked, onCheckedChange = null,
                thumbContent = if (checked) {
                    { RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
                } else null)
        }
    }
}

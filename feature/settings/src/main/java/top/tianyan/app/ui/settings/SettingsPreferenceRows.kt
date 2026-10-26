package top.tianyan.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.settings.LocalizedText as Text

/** Shared density and color roles for settings pages, including legacy groups. */
@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingsRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    tonalIcon: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interaction = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        interaction.fillMaxWidth().heightIn(min = if (tonalIcon) 96.dp else SettingsDensity.rowMinHeight)
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        if (tonalIcon) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    RuntimeIcon(icon, Modifier.size(SettingsDensity.iconSize), MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        } else RuntimeIcon(icon, Modifier.size(SettingsDensity.iconSize), MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                value?.let {
                    Text(it, Modifier.widthIn(max = 104.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(subtitle, style = if (tonalIcon) MaterialTheme.typography.bodyMedium else settingsSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
internal fun ToggleRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
            .heightIn(min = SettingsDensity.rowMinHeight)
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        RuntimeIcon(icon, Modifier.size(SettingsDensity.iconSize),
            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Text(title, style = settingsTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            Text(subtitle, style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
        }
        RuntimeSwitch(checked, onCheckedChange = null, enabled = enabled,
            thumbContent = if (checked) {
                { RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
            } else null)
    }
}

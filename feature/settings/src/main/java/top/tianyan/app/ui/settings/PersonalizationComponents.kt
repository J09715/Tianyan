package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.settings.LocalizedText as Text

@Composable
internal fun PersonalizationScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) =
    RuntimeSettingsScaffold(title, onBack, itemSpacing = SettingsDensity.sectionGap, content = content)

@Composable
internal fun PersonalizationSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** Outer corners describe the group; small inner corners separate related choices. */
@Composable
internal fun PreferenceSurface(
    first: Boolean = true,
    last: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (first) outer.topStart else inner.topStart,
            topEnd = if (first) outer.topEnd else inner.topEnd,
            bottomStart = if (last) outer.bottomStart else inner.bottomStart,
            bottomEnd = if (last) outer.bottomEnd else inner.bottomEnd,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
        content = content,
    )
}

@Composable
internal fun PersonalizationToggle(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange,
        ).heightIn(min = SettingsDensity.rowMinHeight).padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        RuntimeIcon(icon, Modifier.size(SettingsDensity.iconSize), MaterialTheme.colorScheme.primary)
        PreferenceText(title, subtitle, Modifier.weight(1f))
        RuntimeSwitch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            thumbContent = if (checked) {
                { RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
            } else null,
        )
    }
}

@Composable
internal fun PreferenceText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
        Text(title, style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = settingsSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ThemeOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = SettingsDensity.rowMinHeight).padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        PreferenceText(title, subtitle, Modifier.weight(1f))
        RuntimeRadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun PreferenceSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(SettingsDensity.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = settingsTitleStyle())
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        RuntimeSlider(value = value, onValueChange = onValueChange, onValueChangeFinished = onFinished,
            valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
    }
}

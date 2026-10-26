package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.RuntimeIconName

/** Feature destinations share the same tonal, grouped rows as the settings home. */
@Composable
internal fun FeatureLinkRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    first: Boolean = true,
    last: Boolean = true,
    onClick: () -> Unit,
) {
    PreferenceSurface(first = first, last = last) {
        SettingsRow(icon, title, subtitle, onClick = onClick, tonalIcon = true)
    }
}

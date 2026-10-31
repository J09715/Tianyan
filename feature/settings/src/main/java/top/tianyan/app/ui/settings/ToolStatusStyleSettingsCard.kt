package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.AppearancePreferences
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf

@Composable
internal fun ToolStatusStyleSettingsCard() {
    val preferences = previewOrNull { koinInject<AppearancePreferences>() }
    val classicDots by preferences?.classicToolStatusDots?.collectAsStateWithLifecycle(initialValue = false) ?: previewStateOf(false)
    val scope = rememberCoroutineScope()
    PersonalizationSection(stringResource(R.string.settings_tool_status_style)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PreferenceSurface(last = false) {
                ThemeOptionRow(stringResource(R.string.settings_tool_status_icons),
                    stringResource(R.string.settings_tool_status_icons_description), !classicDots) {
                    scope.launch { preferences?.setClassicToolStatusDots(false) }
                }
            }
            PreferenceSurface(first = false) {
                ThemeOptionRow(stringResource(R.string.settings_tool_status_dots),
                    stringResource(R.string.settings_tool_status_dots_description), classicDots) {
                    scope.launch { preferences?.setClassicToolStatusDots(true) }
                }
            }
        }
    }
}

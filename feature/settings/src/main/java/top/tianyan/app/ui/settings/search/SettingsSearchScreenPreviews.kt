package top.tianyan.app.ui.settings.search

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun SettingsSearchScreenPreview() {
    RuntimePreview {
        SettingsSearchScreen(onBack = { }, onNavigateToTarget = { _ -> })
    }
}

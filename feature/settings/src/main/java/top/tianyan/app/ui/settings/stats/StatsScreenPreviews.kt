package top.tianyan.app.ui.settings.stats

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun StatsScreenPreview() {
    RuntimePreview {
        StatsScreen(onBack = { })
    }
}

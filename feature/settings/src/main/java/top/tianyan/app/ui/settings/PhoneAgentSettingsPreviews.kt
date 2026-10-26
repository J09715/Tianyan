package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun PhoneAgentSettingsScreenPreview() {
    RuntimePreview {
        PhoneAgentSettingsScreen(onBack = { })
    }
}

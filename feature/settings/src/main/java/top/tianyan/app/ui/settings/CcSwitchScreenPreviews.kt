package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun CcSwitchContentPreview() {
    RuntimePreview {
        LocalCcSwitchContent(onBack = { }, onLaunchTerminal = { _ -> }, onOpenBrowser = { _ -> })
    }
}

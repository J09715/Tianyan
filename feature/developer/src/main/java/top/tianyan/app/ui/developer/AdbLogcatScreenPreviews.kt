package top.tianyan.app.ui.developer

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun AdbLogcatScreenPreview() {
    RuntimePreview {
        AdbLogcatScreen(onBack = { })
    }
}

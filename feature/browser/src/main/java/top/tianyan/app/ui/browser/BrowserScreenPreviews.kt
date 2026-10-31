package top.tianyan.app.ui.browser

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun BrowserScreenPreview() {
    RuntimePreview {
        BrowserScreen(onBack = { })
    }
}

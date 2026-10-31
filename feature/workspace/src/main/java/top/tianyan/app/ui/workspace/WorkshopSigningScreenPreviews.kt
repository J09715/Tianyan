package top.tianyan.app.ui.workspace

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun WorkshopSigningScreenPreview() {
    RuntimePreview {
        WorkshopSigningScreen(onBack = { })
    }
}

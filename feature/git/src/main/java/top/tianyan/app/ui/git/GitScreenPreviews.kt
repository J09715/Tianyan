package top.tianyan.app.ui.git

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun GitScreenPreview() {
    RuntimePreview {
        GitScreen(projectName = "preview-project", onBack = { })
    }
}

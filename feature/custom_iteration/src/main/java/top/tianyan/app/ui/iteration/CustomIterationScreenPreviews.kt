package top.tianyan.app.ui.iteration

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun CustomIterationScreenPreview() {
    RuntimePreview {
        CustomIterationScreen(onBack = { }, onNavigateToChat = { _ -> })
    }
}

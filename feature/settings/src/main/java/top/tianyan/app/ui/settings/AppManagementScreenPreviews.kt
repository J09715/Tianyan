package top.tianyan.app.ui.settings

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@Preview(name = "Compact large text", widthDp = 320, heightDp = 760, fontScale = 1.3f, locale = "zh")
@Preview(name = "Dark", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@FoldablePreviews
@Composable
private fun AppManagementScreenPreview() {
    RuntimePreview {
        AppManagementScreen(onBack = { })
    }
}

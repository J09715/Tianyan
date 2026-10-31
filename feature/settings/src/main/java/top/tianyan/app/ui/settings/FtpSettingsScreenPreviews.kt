package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Preview(name = "深层设置 · 窄屏大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "Detail · Dark", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun FtpSettingsScreenPreview() {
    RuntimePreview {
        FtpSettingsScreen(onBack = { })
    }
}

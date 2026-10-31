package top.tianyan.app.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun WorkshopSettingsScreenPreview() {
    RuntimePreview {
        WorkshopSettingsScreen(onBack = { }, onOpenEnvironment = { })
    }
}

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun WorkshopEnvironmentSettingsScreenPreview() {
    RuntimePreview {
        WorkshopEnvironmentSettingsScreen(onBack = { })
    }
}

@FoldablePreviews
@Composable
private fun WorkshopScriptEditorScreenPreview() {
    RuntimePreview {
        WorkshopScriptEditorScreen(type = WorkshopScriptType.entries.first(), onBack = { })
    }
}

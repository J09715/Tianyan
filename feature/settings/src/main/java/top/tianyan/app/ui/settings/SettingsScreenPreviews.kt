package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview
import top.tianyan.app.ui.components.LocalOpenPluginManager

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 360, heightDp = 800, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun SettingsScreenPreview() {
    RuntimePreview {
        CompositionLocalProvider(LocalOpenPluginManager provides {}) {
        SettingsScreen(onNavigate = { _ -> }, onOpenAgentEco = { }, onOpenLinuxEnv = { }, onOpenAppearance = { }, onOpenSystemDev = { }, onOpenAboutCommunity = { })
        }
    }
}

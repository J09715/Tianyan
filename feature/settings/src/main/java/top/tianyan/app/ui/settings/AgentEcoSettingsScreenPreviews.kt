package top.tianyan.app.ui.settings

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 360, heightDp = 800, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun AgentEcoSettingsScreenPreview() {
    RuntimePreview {
        AgentEcoSettingsScreen(onBack = { }, onOpenModelProfiles = { }, onOpenLocalLlm = { }, onOpenAgentSettings = { }, onOpenSubagentSettings = { }, onOpenSkillSettings = { }, onOpenMcpSettings = { }, onOpenQuickPhrases = { }, onOpenStats = { }, onOpenPhoneAgent = { })
    }
}

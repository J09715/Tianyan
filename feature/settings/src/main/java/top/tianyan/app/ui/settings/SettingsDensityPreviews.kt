package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimePreview

/** Mirrors the four navigation entries in the user's density comparison, without a ViewModel. */
@Preview(name = "默认密度 · 360dp", widthDp = 360, heightDp = 760, locale = "zh")
@Preview(name = "小屏 · 320dp", widthDp = 320, heightDp = 760, locale = "zh")
@Preview(name = "大字体 · 360dp", widthDp = 360, heightDp = 760, fontScale = 1.3f, locale = "zh")
@Preview(name = "English", widthDp = 360, heightDp = 760, locale = "en")
@Composable
private fun SettingsDensityPreview() {
    RuntimePreview {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            PersonalizationSection("模型档案与提供商") {
                PreferenceSurface(last = false) {
                    SettingsRow(RuntimeIconName.Model, "模型档案管理",
                        stringResource(R.string.settings_nav_models_summary), value = "未配置", onClick = {})
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(RuntimeIconName.Cpu, "本地 LLM",
                        stringResource(R.string.settings_nav_local_summary), onClick = {})
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(RuntimeIconName.Chat, "快捷短语与常用指令",
                        stringResource(R.string.settings_nav_phrases_summary), value = "14 条已启用", onClick = {})
                }
                PreferenceSurface(first = false) {
                    SettingsRow(RuntimeIconName.Speed, "数据统计与用量分析",
                        stringResource(R.string.settings_nav_stats_summary), onClick = {})
                }
            }
        }
    }
}

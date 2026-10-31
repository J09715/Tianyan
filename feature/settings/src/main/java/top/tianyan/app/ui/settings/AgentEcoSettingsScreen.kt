package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.settings.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
/**
 * 二级子页 1：智能体与 AI 模型生态
 */
@Composable
fun AgentEcoSettingsScreen(
    onBack: () -> Unit,
    onOpenModelProfiles: () -> Unit,
    onOpenLocalLlm: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenSubagentSettings: () -> Unit,
    onOpenSkillSettings: () -> Unit,
    onOpenMcpSettings: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenPhoneAgent: () -> Unit,
    onOpenCcSwitch: () -> Unit = {},
    onOpenToolCenter: () -> Unit = {},
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val models by viewModel?.models?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val skills by viewModel?.allSkills?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val phrases by viewModel?.quickPhrases?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())

    PersonalizationScaffold(stringResource(R.string.personal_agents_title), onBack) {
        item {
            PersonalizationSection("模型与对话") {
                PreferenceSurface(first = true, last = false) {
                    SettingsRow(
                        icon = RuntimeIconName.Model,
                        title = "模型档案管理",
                        subtitle = stringResource(R.string.settings_nav_models_summary),
                        value = if (models.isEmpty()) "未配置" else "${models.size} 个模型",
                        onClick = onOpenModelProfiles, tonalIcon = true,
                    )
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(
                        icon = RuntimeIconName.Cpu,
                        title = "本地 LLM",
                        subtitle = stringResource(R.string.settings_nav_local_summary),
                        onClick = onOpenLocalLlm, tonalIcon = true,
                    )
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(
                        icon = RuntimeIconName.Chat,
                        title = "快捷短语与常用指令",
                        subtitle = stringResource(R.string.settings_nav_phrases_summary),
                        value = "${phrases.count { it.isEnabled }} 条已启用",
                        onClick = onOpenQuickPhrases, tonalIcon = true,
                    )
                }
                PreferenceSurface(first = false, last = true) {
                    SettingsRow(
                        icon = RuntimeIconName.Speed,
                        title = "数据统计与用量分析",
                        subtitle = stringResource(R.string.settings_nav_stats_summary),
                        onClick = onOpenStats, tonalIcon = true,
                    )
                }
            }
        }

        item {
            PersonalizationSection("虚拟屏") {
                PreferenceSurface(first = true, last = true) {
                    PhoneAgentSettingsEntry(onOpen = onOpenPhoneAgent)
                }
            }
        }

        item {
            PersonalizationSection("智能体能力") {
                PreferenceSurface(first = true, last = false) {
                    SettingsRow(
                        icon = RuntimeIconName.Sparkles,
                        title = "智能体中枢 (CC-Switch)",
                        subtitle = stringResource(R.string.settings_nav_cc_summary),
                        onClick = onOpenCcSwitch, tonalIcon = true,
                    )
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(icon = RuntimeIconName.Bot, title = "Agent 执行与上下文", subtitle = stringResource(R.string.settings_nav_execution_summary), onClick = onOpenAgentSettings, tonalIcon = true)
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(icon = RuntimeIconName.Bot, title = "子智能体角色", subtitle = stringResource(R.string.settings_nav_roles_summary), onClick = onOpenSubagentSettings, tonalIcon = true)
                }
                PreferenceSurface(first = false, last = false) {
                    SettingsRow(icon = RuntimeIconName.Sparkles, title = "Skills 与插件", subtitle = stringResource(R.string.settings_nav_skills_summary), value = "${skills.count { it.isEnabled }} 个技能", onClick = onOpenSkillSettings, tonalIcon = true)
                }
                PreferenceSurface(first = false, last = true) {
                    SettingsRow(
                        icon = RuntimeIconName.Network,
                        title = "MCP 协议生态与服务",
                        subtitle = stringResource(R.string.settings_nav_mcp_summary),
                        onClick = onOpenMcpSettings, tonalIcon = true,
                    )
                }
            }
        }
    }
}

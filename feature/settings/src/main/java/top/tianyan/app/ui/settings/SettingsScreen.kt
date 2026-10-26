package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import top.tianyan.app.ui.components.RuntimeIllustration
import top.tianyan.app.ui.components.RuntimeIllustrationScene
import top.tianyan.app.ui.components.RuntimeUsageNoticeDialog
import top.tianyan.app.feature.components.R as ComponentsR
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.settings.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.MainDestination
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.LocalOpenPluginManager

/**
 * 天衍 · 乾坤配置 (Tianyan Settings & Models)
 */
@Composable
fun SettingsScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenAgentEco: () -> Unit,
    onOpenLinuxEnv: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSystemDev: () -> Unit,
    onOpenAboutCommunity: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenFeatureHub: () -> Unit = {},
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() }, onBack: () -> Unit = { onNavigate(MainDestination.Agent) },
) {
    var showUsageNotice by rememberSaveable { mutableStateOf(false) }
    if (showUsageNotice) RuntimeUsageNoticeDialog(onDismiss = { showUsageNotice = false })
    val openPluginManager = LocalOpenPluginManager.current
    val models by viewModel?.models?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val developer by viewModel?.developerMode?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val themeMode by viewModel?.themeMode?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val skills by viewModel?.allSkills?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val effectiveExecutionMode by viewModel?.effectiveExecutionMode?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.ExecutionMode.PROOT)
    val installedDistros by viewModel?.installedDistros?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val terminalFontSize by viewModel?.terminalFontSize?.collectAsStateWithLifecycle() ?: previewStateOf(14)

    val themeLabel = when (themeMode) {
        "light" -> "浅色"
        "dark" -> "曜石"
        else -> "跟随系统"
    }

    val appVersionName = rememberAppVersion()


    PersonalizationScaffold(stringResource(R.string.personal_settings_title), onBack) {
        item("settings_illustration") {
            RuntimeIllustration(RuntimeIllustrationScene.Settings, Modifier.height(144.dp))
        }
        item("search") {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSearch,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RuntimeIcon(RuntimeIconName.Search, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.personal_search), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item("appearance") {
            SettingsCategoryCard(
                icon = RuntimeIconName.Palette,
                title = stringResource(R.string.personal_appearance_title),
                subtitle = stringResource(R.string.settings_nav_appearance_summary),
                value = "$themeLabel · ${terminalFontSize}sp",
                onClick = onOpenAppearance,
            )
        }
        item("environment") {
            PersonalizationSection(stringResource(R.string.personal_environment)) {
                SettingsCategoryCard(RuntimeIconName.Brain, stringResource(R.string.personal_agents_title),
                    stringResource(R.string.settings_nav_agents_summary),
                    if (models.isEmpty()) "未配置模型" else "${models.size} 个模型 · ${skills.count { it.isEnabled }} 技能",
                    first = true, last = false, onClick = onOpenAgentEco)
                SettingsCategoryCard(RuntimeIconName.Server, stringResource(R.string.personal_linux_title),
                    stringResource(R.string.settings_nav_linux_summary),
                    "${installedDistros.size} 套系统 · ${effectiveExecutionMode.shortLabel}",
                    first = false, last = true, onClick = onOpenLinuxEnv)
            }
        }
        item("features-and-data") {
            PersonalizationSection(stringResource(R.string.settings_material_features_data)) {
                SettingsCategoryCard(RuntimeIconName.Sparkles, stringResource(R.string.settings_feature_hub_title),
                    stringResource(R.string.settings_nav_features_summary),
                    "5 项功能", first = true, last = false, onClick = onOpenFeatureHub)
                if (openPluginManager != null) SettingsCategoryCard(RuntimeIconName.Extension,
                    stringResource(R.string.settings_material_plugins_title), stringResource(R.string.settings_material_plugins_summary),
                    value = null, first = false, last = false, onClick = openPluginManager)
                LocalBackupSettingsCard(first = false, last = true)
            }
        }
        item("application") {
            PersonalizationSection(stringResource(R.string.personal_application)) {
                SettingsCategoryCard(RuntimeIconName.Admin, stringResource(R.string.personal_diagnostics_title),
                    stringResource(R.string.settings_nav_diagnostics_summary),
                    if (developer) "诊断模式已开启" else "运行平稳",
                    first = true, last = false, onClick = onOpenSystemDev)
                SettingsCategoryCard(RuntimeIconName.Community, stringResource(R.string.settings_nav_about_title),
                    stringResource(R.string.settings_nav_about_summary),
                    if (appVersionName == "unknown") "版本号未知 · 稳定版" else "v$appVersionName 稳定版",
                    first = false, last = false, onClick = onOpenAboutCommunity)
                SettingsCategoryCard(RuntimeIconName.Shield, stringResource(ComponentsR.string.runtime_notice_title),
                    stringResource(ComponentsR.string.runtime_notice_summary), value = null,
                    first = false, last = true, onClick = { showUsageNotice = true })
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    value: String?,
    first: Boolean = true,
    last: Boolean = true,
    onClick: () -> Unit,
) {
    PreferenceSurface(first = first, last = last) {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, value = value, onClick = onClick, tonalIcon = true)
    }
}

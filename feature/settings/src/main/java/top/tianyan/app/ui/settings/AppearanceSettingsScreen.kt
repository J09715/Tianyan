package top.tianyan.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val paletteViewModel = previewOrNull { koinViewModel<ThemePaletteViewModel>() }
    val palette by paletteViewModel?.palette?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.ThemePalette())
    val themeMode by viewModel?.themeMode?.collectAsStateWithLifecycle() ?: previewStateOf("system")
    val dynamicColor by viewModel?.dynamicColorEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(true)
    val fontSize by viewModel?.terminalFontSize?.collectAsStateWithLifecycle() ?: previewStateOf(14)
    val terminalScheme by viewModel?.terminalColorScheme?.collectAsStateWithLifecycle() ?: previewStateOf("obsidian")
    val haptics by viewModel?.terminalHapticsEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(true)
    val fontScale by viewModel?.appFontScale?.collectAsStateWithLifecycle() ?: previewStateOf(1f)
    LocalConfiguration.current // Refresh after AppCompat changes locales.
    val locales = AppCompatDelegate.getApplicationLocales()
    val language = if (locales.isEmpty) "system" else locales[0]?.language.orEmpty()
    var terminalSize by rememberSaveable { mutableFloatStateOf(fontSize.toFloat()) }
    var pageScale by rememberSaveable { mutableFloatStateOf(fontScale) }
    LaunchedEffect(fontSize) { terminalSize = fontSize.toFloat() }
    LaunchedEffect(fontScale) { pageScale = fontScale }
    PersonalizationScaffold(stringResource(R.string.personal_appearance_title), onBack) {
        item("palette_illustration") { PersonalColorPreview() }
        item("palette") {
            ThemePalettePicker(
                palette = palette,
                dynamicColor = dynamicColor,
                themeMode = themeMode,
                onColor = { paletteViewModel?.selectColor(it) },
                onStyle = { paletteViewModel?.selectStyle(it) },
                onDynamicColor = { viewModel?.setDynamicColorEnabled(it) },
            )
        }
        item("theme") {
            PersonalizationSection(stringResource(R.string.settings_color_mode)) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val modes = listOf(
                        Triple("system", R.string.settings_color_system, R.string.settings_color_system_description),
                        Triple("light", R.string.settings_color_light, R.string.settings_color_light_description),
                        Triple("dark", R.string.settings_color_dark, R.string.settings_color_dark_description),
                    )
                    modes.forEachIndexed { index, (mode, title, description) ->
                        PreferenceSurface(first = index == 0, last = index == modes.lastIndex) {
                            ThemeOptionRow(stringResource(title), stringResource(description),
                                selected = themeMode == mode || (mode == "system" && themeMode.isBlank()),
                                onClick = { viewModel?.setThemeMode(mode) })
                        }
                    }
                }
            }
        }
        item("background") { ChatBackgroundSettingsCard() }
        item("tool_status_style") { ToolStatusStyleSettingsCard() }
        item("scale") {
            PersonalizationSection(stringResource(R.string.settings_interface_scale)) {
                PreferenceSurface {
                    PreferenceSlider(stringResource(R.string.settings_scale), "${(pageScale * 100).roundToInt()}%",
                        value = pageScale, range = .8f..1.3f, steps = 9,
                        onValueChange = { pageScale = ((it * 20).roundToInt() / 20f).coerceIn(.8f, 1.3f) },
                        onFinished = { viewModel?.setAppFontScale(pageScale) })
                }
            }
        }
        item("language") {
            PersonalizationSection(stringResource(R.string.settings_language_title)) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val languages = listOf(
                        Triple("system", R.string.settings_language_system, R.string.settings_language_system_description),
                        Triple("zh", R.string.settings_language_chinese, R.string.settings_language_chinese_description),
                        Triple("en", R.string.settings_language_english, R.string.settings_language_english_description),
                    )
                    languages.forEachIndexed { index, (code, title, description) ->
                        PreferenceSurface(first = index == 0, last = index == languages.lastIndex) {
                            ThemeOptionRow(stringResource(title), stringResource(description), language == code) {
                                AppCompatDelegate.setApplicationLocales(when (code) {
                                    "system" -> LocaleListCompat.getEmptyLocaleList()
                                    "zh" -> LocaleListCompat.forLanguageTags("zh-CN")
                                    else -> LocaleListCompat.forLanguageTags("en")
                                })
                            }
                        }
                    }
                }
            }
        }
        item("terminal_preview") {
            PersonalizationSection(stringResource(R.string.settings_terminal_preview)) {
                TerminalPreviewCard(terminalScheme, terminalSize.roundToInt())
            }
        }
        item("terminal") {
            PersonalizationSection(stringResource(R.string.settings_terminal_appearance)) {
                PreferenceSurface(last = false) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.settings_terminal_color_scheme), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TerminalThemeChip(stringResource(R.string.settings_terminal_theme_obsidian), Color(0xFF0F1117), Color(0xFFE2E2E9),
                                terminalScheme == "obsidian", { viewModel?.setTerminalColorScheme("obsidian") }, Modifier.weight(1f))
                            TerminalThemeChip(stringResource(R.string.settings_terminal_theme_matrix), Color(0xFF0A0F0D), Color(0xFF10B981),
                                terminalScheme == "matrix", { viewModel?.setTerminalColorScheme("matrix") }, Modifier.weight(1f))
                            TerminalThemeChip(stringResource(R.string.settings_terminal_theme_amber), Color(0xFF140F0A), Color(0xFFF59E0B),
                                terminalScheme == "amber", { viewModel?.setTerminalColorScheme("amber") }, Modifier.weight(1f))
                            TerminalThemeChip(stringResource(R.string.settings_terminal_theme_aurora), Color(0xFF0D1424), Color(0xFF38BDF8),
                                terminalScheme == "aurora", { viewModel?.setTerminalColorScheme("aurora") }, Modifier.weight(1f))
                        }
                    }
                }
                PreferenceSurface(first = false, last = false) {
                    PreferenceSlider(stringResource(R.string.settings_terminal_font_size), "${terminalSize.roundToInt()} sp",
                        terminalSize, 10f..24f, 13,
                        onValueChange = { terminalSize = it.roundToInt().toFloat() },
                        onFinished = { viewModel?.setTerminalFontSize(terminalSize.roundToInt()) })
                }
                PreferenceSurface(first = false) {
                    PersonalizationToggle(RuntimeIconName.Vibrate,
                        stringResource(R.string.settings_terminal_haptics), stringResource(R.string.settings_terminal_haptics_description),
                        checked = haptics, onCheckedChange = { viewModel?.setTerminalHapticsEnabled(it) })
                }
            }
        }
    }
}

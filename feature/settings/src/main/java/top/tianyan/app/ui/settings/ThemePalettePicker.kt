@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package top.tianyan.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.ThemePalette
import top.tianyan.app.core.model.ThemePaletteStyle
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.theme.ThemePaletteColors
import top.tianyan.app.ui.theme.themePaletteColorScheme

/** Manual palettes stay available while Monet is enabled; picking one switches to manual color. */
@Composable
internal fun ThemePalettePicker(
    palette: ThemePalette,
    dynamicColor: Boolean,
    themeMode: String,
    onColor: (String) -> Unit,
    onStyle: (ThemePaletteStyle) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    val colorNames = stringArrayResource(R.array.appearance_palette_names)
    val styleNames = stringArrayResource(R.array.appearance_palette_styles)
    val isDark = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val pager = rememberPagerState(
        initialPage = (ThemePaletteColors.indexOfFirst { it.id == palette.colorId }.coerceAtLeast(0) / 4),
        pageCount = { (ThemePaletteColors.size + 3) / 4 },
    )
    var styleMenu by remember { mutableStateOf(false) }
    LaunchedEffect(palette.colorId) {
        pager.scrollToPage(ThemePaletteColors.indexOfFirst { it.id == palette.colorId }.coerceAtLeast(0) / 4)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        HorizontalPager(state = pager, pageSpacing = 16.dp) { page ->
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePaletteColors.drop(page * 4).take(4).forEachIndexed { index, color ->
                    val scheme = remember(color, isDark, palette.style) { themePaletteColorScheme(ThemePalette(color.id, palette.style), isDark) }
                    val selected = !dynamicColor && color.id == palette.colorId
                    val label = colorNames[page * 4 + index]
                    RuntimeCard(
                        modifier = Modifier.weight(1f).aspectRatio(1f).selectable(selected, role = Role.RadioButton, onClick = { onColor(color.id) })
                            .semantics { contentDescription = label },
                        shape = MaterialTheme.shapes.extraLarge,
                        containerColor = MaterialTheme.colorScheme.surface,
                        borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        contentPadding = PaddingValues(10.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                                drawArc(scheme.primary, 180f, 180f, useCenter = true)
                                drawArc(scheme.secondaryContainer, 90f, 90f, useCenter = true)
                                drawArc(scheme.tertiary, 0f, 90f, useCenter = true)
                            }
                            if (selected) {
                                Surface(shape = CircleShape, color = scheme.primaryContainer) {
                                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                        RuntimeIcon(RuntimeIconName.Check, Modifier.size(18.dp), scheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            repeat(pager.pageCount) { index ->
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = if (index == pager.currentPage) MaterialShapes.Cookie4Sided.toShape() else CircleShape,
                    color = if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                ) {}
            }
        }
        Text(stringResource(if (dynamicColor) R.string.appearance_palette_wallpaper_hint else R.string.appearance_palette_manual_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RuntimeCard(
                shape = paletteGroupShape(first = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(0.dp),
            ) {
                PersonalizationToggle(
                    icon = RuntimeIconName.Palette,
                    title = stringResource(R.string.personal_wallpaper_color),
                    subtitle = stringResource(R.string.personal_wallpaper_color_description),
                    checked = dynamicColor,
                    onCheckedChange = onDynamicColor,
                )
            }
            Box {
                RuntimeCard(
                    shape = paletteGroupShape(first = false),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentPadding = PaddingValues(0.dp),
                    onClick = { styleMenu = true },
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = SettingsDensity.rowMinHeight)
                        .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap)) {
                        RuntimeIcon(RuntimeIconName.Palette, Modifier.size(SettingsDensity.iconSize), MaterialTheme.colorScheme.primary)
                        PreferenceText(stringResource(R.string.appearance_palette_style), styleNames[palette.style.ordinal], Modifier.weight(1f))
                    }
                }
                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    ThemePaletteStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(styleNames[style.ordinal]) },
                            leadingIcon = if (style == palette.style) { { RuntimeIcon(RuntimeIconName.Check, Modifier.size(24.dp)) } } else null,
                            onClick = { onStyle(style); styleMenu = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun paletteGroupShape(first: Boolean) = MaterialTheme.shapes.run {
    RoundedCornerShape(
        topStart = if (first) extraLarge.topStart else extraSmall.topStart,
        topEnd = if (first) extraLarge.topEnd else extraSmall.topEnd,
        bottomStart = if (first) extraSmall.bottomStart else extraLarge.bottomStart,
        bottomEnd = if (first) extraSmall.bottomEnd else extraLarge.bottomEnd,
    )
}

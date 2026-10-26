package top.tianyan.app.ui.theme

import androidx.compose.ui.graphics.Color
import top.tianyan.app.core.model.ThemePalette

data class ThemePaletteColor(val id: String, val seed: Color)

/** Stable preset IDs are persisted; actual visual tokens stay in the theme module. */
val ThemePaletteColors = listOf(
    ThemePaletteColor("default", Color(0xFF4259C3)),
    ThemePaletteColor("rose", Color(0xFFBA6475)),
    ThemePaletteColor("coral", Color(0xFFC67455)),
    ThemePaletteColor("amber", Color(0xFFAF832C)),
    ThemePaletteColor("leaf", Color(0xFF687D47)),
    ThemePaletteColor("jade", Color(0xFF368575)),
    ThemePaletteColor("lake", Color(0xFF3C8299)),
    ThemePaletteColor("blue", Color(0xFF4C72B0)),
    ThemePaletteColor("violet", Color(0xFF8666AE)),
    ThemePaletteColor("orchid", Color(0xFFAC638C)),
    ThemePaletteColor("sand", Color(0xFF96775A)),
    ThemePaletteColor("slate", Color(0xFF687980)),
)

fun themePaletteSeed(id: String): Color = ThemePaletteColors.firstOrNull { it.id == id }?.seed ?: ThemePaletteColors.first().seed

fun themePaletteColorScheme(palette: ThemePalette, darkTheme: Boolean) =
    if (palette == ThemePalette()) {
        if (darkTheme) XuantongDarkColors else XuantongLightColors
    } else generateM3ColorScheme(themePaletteSeed(palette.colorId), darkTheme, palette.style)

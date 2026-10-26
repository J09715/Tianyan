package top.tianyan.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.utils.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.ThemePaletteStyle
import top.tianyan.app.core.model.ThemePalette

class ThemePaletteColorsTest {
    @Test fun everyPresetAndStyleKeepsReadableTextInBothModes() {
        ThemePaletteColors.forEach { preset ->
            ThemePaletteStyle.entries.forEach { style ->
                listOf(false, true).forEach { dark ->
                    val scheme = generateM3ColorScheme(preset.seed, dark, style)
                    listOf(scheme.primary to scheme.onPrimary, scheme.surface to scheme.onSurface,
                        scheme.primaryContainer to scheme.onPrimaryContainer).forEach { (background, foreground) ->
                        assertTrue("${preset.id}, $style, dark=$dark", contrast(background, foreground) >= 4.5)
                    }
                }
            }
        }
    }

    @Test fun unknownPresetUsesTheDefaultSeed() {
        assertEquals(themePaletteSeed("default"), themePaletteSeed("future-preset"))
    }

    @Test fun defaultChoiceKeepsTheExistingPalette() {
        assertEquals(XuantongLightColors, themePaletteColorScheme(ThemePalette(), false))
        assertEquals(XuantongDarkColors, themePaletteColorScheme(ThemePalette(), true))
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = ColorUtils.lstarFromArgb(a.toArgb())
        val y = ColorUtils.lstarFromArgb(b.toArgb())
        val lumA = ColorUtils.yFromLstar(x) / 100
        val lumB = ColorUtils.yFromLstar(y) / 100
        return (maxOf(lumA, lumB) + 0.05) / (minOf(lumA, lumB) + 0.05)
    }
}

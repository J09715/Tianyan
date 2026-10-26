package top.tianyan.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCategoricalContrastTest {
    @Test
    fun storageCategoriesRemainDistinctAndVisibleAcrossThemeHues() {
        val primaries = listOf(Color(0xFFB3261E), Color(0xFF006A6A), Color(0xFF6750A4), Color(0xFF006E1C))
        for (base in listOf(XuantongLightColors, XuantongDarkColors)) {
            for (primary in primaries) {
                val scheme = base.copy(primary = primary)
                val palette = buildSemanticColors(scheme).categorical
                assertTrue("Twelve storage categories need distinct colors", palette.size >= 12)
                assertEquals(palette.size, palette.toSet().size)
                for (color in palette) {
                    val a = color.luminance().toDouble()
                    val b = scheme.surface.luminance().toDouble()
                    val contrast = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                    assertTrue("Graphical contrast $contrast for $color against ${scheme.surface}", contrast >= 3.0)
                }
            }
        }
    }
}

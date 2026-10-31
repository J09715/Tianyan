package top.tianyan.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HctThemeEngineTest {

    // ---------- harmonize 保色调 / 彩度 / 旋转幅度 ----------

    @Test
    fun harmonizePreservesToneAndHue() {
        val designArgb = 0xFF1B6B3A.toInt()
        val sourceArgb = 0xFF4259C3.toInt()
        val design = Hct.fromInt(designArgb)
        val result = Hct.fromInt(Blend.harmonize(designArgb, sourceArgb))

        assertEquals("tone should be preserved", design.tone, result.tone, 0.1)
        assertTrue("hue rotation should be <= 15 degrees", hueDelta(design.hue, result.hue) <= 15.01)
        // harmonize 会请求原始彩度，但 HCT→sRGB 转换把超出色域的彩度钳到边界：
        // 彩度只降不升，且仍应贴近原值（本用例实测约 90%）。
        assertTrue("chroma must not increase", result.chroma <= design.chroma + 0.5)
        assertTrue(
            "chroma should stay close to the original (gamut clamp only)",
            result.chroma >= design.chroma * 0.8,
        )
    }

    @Test
    fun harmonizeRotatesTowardsSource() {
        val design = Hct.fromInt(0xFF1B6B3A.toInt())
        val source = Hct.fromInt(0xFF4259C3.toInt())
        val result = Hct.fromInt(Blend.harmonize(design.toInt(), source.toInt()))

        assertTrue(
            "harmonized color should move closer to the source hue",
            hueDelta(result.hue, source.hue) < hueDelta(design.hue, source.hue),
        )
    }

    // ---------- 语义色谐调后 WCAG 对比度 ----------

    @Test
    fun harmonizedSemanticColorsKeepWcagContrast() {
        for (dark in listOf(false, true)) {
            val scheme = generateM3ColorScheme(Color(0xFF4259C3), dark)
            val s = buildSemanticColors(scheme)
            assertContrast(s.success, s.onSuccess)
            assertContrast(s.successContainer, s.onSuccessContainer)
            assertContrast(s.warning, s.onWarning)
            assertContrast(s.warningContainer, s.onWarningContainer)
            assertContrast(s.info, s.onInfo)
            assertContrast(s.infoContainer, s.onInfoContainer)
            assertContrast(s.agent, s.onAgent)
            assertContrast(s.agentContainer, s.onAgentContainer)
        }
    }

    // ---------- ROM 锁色判定 ----------

    @Test
    fun lockedSystemSchemeIsDetected() {
        // 系统主色为靛蓝（~230°），壁纸种子为红（~10°）：判定为锁死预置色
        assertTrue(isSystemSchemeColorLocked(Color(0xFF4259C3), Color(0xFFB3261E)))
    }

    @Test
    fun matchingSystemSchemeIsNotLocked() {
        // 系统主色与种子近似同色：正常 ROM 的动态色
        assertFalse(isSystemSchemeColorLocked(Color(0xFF4259C3), Color(0xFF3F5BC7)))
    }

    @Test
    fun graySeedNeverLocksSystemScheme() {
        // 灰阶壁纸种子彩度过低，无判定意义
        assertFalse(isSystemSchemeColorLocked(Color(0xFF4259C3), Color(0xFF808080)))
    }

    // ---------- HCT 色板冒烟 ----------

    @Test
    fun generatedSchemeFollowsSeedHue() {
        val seed = Color(0xFF4259C3)
        val seedHue = Hct.fromInt(seed.toArgb()).hue
        for (dark in listOf(false, true)) {
            val scheme = generateM3ColorScheme(seed, dark)
            val primaryHue = Hct.fromInt(scheme.primary.toArgb()).hue
            assertTrue(
                "primary hue $primaryHue should stay near seed hue $seedHue",
                hueDelta(seedHue, primaryHue) <= 10.0,
            )
        }
    }

    // ---------- helpers ----------

    private fun assertContrast(background: Color, foreground: Color) {
        val ratio = contrastRatio(background.toArgb(), foreground.toArgb())
        assertTrue("contrast $ratio should be >= 4.5", ratio >= 4.5)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }
}

package top.tianyan.app.ui.theme

import top.tianyan.app.core.model.ThemePaletteStyle

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import kotlin.math.abs

/** 种子色彩度门槛：低于此值（近黑 / 白 / 灰壁纸）不参与「ROM 锁色」判定 */
private const val SeedChromaGate = 10.0

/** 系统主色与壁纸种子的色相偏离阈值（度）：超过即视为 ROM 将动态色锁死为预置色板 */
private const val LockedHueDelta = 45.0

private val dynamicColors = MaterialDynamicColors()

/**
 * 用官方 HCT 算法（material-color-utilities）从壁纸种子色派生完整 M3 色板。
 *
 * 变体取 Monet 同款 [SchemeTonalSpot]，规范版本取 SPEC_2025（M3 Expressive 对应版本）。
 */
fun generateM3ColorScheme(
    seedColor: Color,
    darkTheme: Boolean,
    style: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
): ColorScheme {
    val source = listOf(Hct.fromInt(seedColor.toArgb()))
    val scheme = when (style) {
        ThemePaletteStyle.TONAL_SPOT -> SchemeTonalSpot(source, darkTheme, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
        ThemePaletteStyle.VIBRANT -> com.materialkolor.scheme.SchemeVibrant(source, darkTheme, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
        ThemePaletteStyle.EXPRESSIVE -> com.materialkolor.scheme.SchemeExpressive(source, darkTheme, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
        ThemePaletteStyle.NEUTRAL -> com.materialkolor.scheme.SchemeNeutral(source, darkTheme, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
    }
    return scheme.toComposeColorScheme()
}

/**
 * 判定系统色板是否为 ROM 锁死的预置色：系统主色与壁纸种子色相明显脱节即视为锁定。
 * 种子彩度过低（灰阶壁纸）无判定意义，返回 false 继续采用系统色。
 */
internal fun isSystemSchemeColorLocked(systemPrimary: Color, seedColor: Color): Boolean {
    val seed = Hct.fromInt(seedColor.toArgb())
    if (seed.chroma < SeedChromaGate) return false
    val system = Hct.fromInt(systemPrimary.toArgb())
    return hueDelta(system.hue, seed.hue) > LockedHueDelta
}

/** 色相角最短距离（0..180 度） */
internal fun hueDelta(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

private fun DynamicScheme.roleColor(dynamicColor: DynamicColor): Color = Color(getArgb(dynamicColor))

private fun DynamicScheme.toComposeColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = roleColor(dynamicColors.primary),
        onPrimary = roleColor(dynamicColors.onPrimary),
        primaryContainer = roleColor(dynamicColors.primaryContainer),
        onPrimaryContainer = roleColor(dynamicColors.onPrimaryContainer),
        inversePrimary = roleColor(dynamicColors.inversePrimary),
        secondary = roleColor(dynamicColors.secondary),
        onSecondary = roleColor(dynamicColors.onSecondary),
        secondaryContainer = roleColor(dynamicColors.secondaryContainer),
        onSecondaryContainer = roleColor(dynamicColors.onSecondaryContainer),
        tertiary = roleColor(dynamicColors.tertiary),
        onTertiary = roleColor(dynamicColors.onTertiary),
        tertiaryContainer = roleColor(dynamicColors.tertiaryContainer),
        onTertiaryContainer = roleColor(dynamicColors.onTertiaryContainer),
        background = roleColor(dynamicColors.background),
        onBackground = roleColor(dynamicColors.onBackground),
        surface = roleColor(dynamicColors.surface),
        onSurface = roleColor(dynamicColors.onSurface),
        surfaceVariant = roleColor(dynamicColors.surfaceVariant),
        onSurfaceVariant = roleColor(dynamicColors.onSurfaceVariant),
        surfaceTint = roleColor(dynamicColors.surfaceTint),
        inverseSurface = roleColor(dynamicColors.inverseSurface),
        inverseOnSurface = roleColor(dynamicColors.inverseOnSurface),
        error = roleColor(dynamicColors.error),
        onError = roleColor(dynamicColors.onError),
        errorContainer = roleColor(dynamicColors.errorContainer),
        onErrorContainer = roleColor(dynamicColors.onErrorContainer),
        outline = roleColor(dynamicColors.outline),
        outlineVariant = roleColor(dynamicColors.outlineVariant),
        scrim = roleColor(dynamicColors.scrim),
        surfaceBright = roleColor(dynamicColors.surfaceBright),
        surfaceDim = roleColor(dynamicColors.surfaceDim),
        surfaceContainer = roleColor(dynamicColors.surfaceContainer),
        surfaceContainerHigh = roleColor(dynamicColors.surfaceContainerHigh),
        surfaceContainerHighest = roleColor(dynamicColors.surfaceContainerHighest),
        surfaceContainerLow = roleColor(dynamicColors.surfaceContainerLow),
        surfaceContainerLowest = roleColor(dynamicColors.surfaceContainerLowest),
        primaryFixed = roleColor(dynamicColors.primaryFixed),
        primaryFixedDim = roleColor(dynamicColors.primaryFixedDim),
        onPrimaryFixed = roleColor(dynamicColors.onPrimaryFixed),
        onPrimaryFixedVariant = roleColor(dynamicColors.onPrimaryFixedVariant),
        secondaryFixed = roleColor(dynamicColors.secondaryFixed),
        secondaryFixedDim = roleColor(dynamicColors.secondaryFixedDim),
        onSecondaryFixed = roleColor(dynamicColors.onSecondaryFixed),
        onSecondaryFixedVariant = roleColor(dynamicColors.onSecondaryFixedVariant),
        tertiaryFixed = roleColor(dynamicColors.tertiaryFixed),
        tertiaryFixedDim = roleColor(dynamicColors.tertiaryFixedDim),
        onTertiaryFixed = roleColor(dynamicColors.onTertiaryFixed),
        onTertiaryFixedVariant = roleColor(dynamicColors.onTertiaryFixedVariant),
    )
}

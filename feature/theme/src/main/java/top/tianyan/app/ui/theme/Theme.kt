package top.tianyan.app.ui.theme

import top.tianyan.app.core.model.ThemePalette

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ======================= 「玄同 · Xuantong」= 默认 M3 Expressive 配色 =======================

val XuantongLightColors = lightColorScheme(
    primary = M3ExpLightPrimary,
    onPrimary = M3ExpLightOnPrimary,
    primaryContainer = M3ExpLightPrimaryContainer,
    onPrimaryContainer = M3ExpLightOnPrimaryContainer,
    secondary = M3ExpLightSecondary,
    onSecondary = M3ExpLightOnSecondary,
    secondaryContainer = M3ExpLightSecondaryContainer,
    onSecondaryContainer = M3ExpLightOnSecondaryContainer,
    tertiary = M3ExpLightTertiary,
    onTertiary = M3ExpLightOnTertiary,
    tertiaryContainer = M3ExpLightTertiaryContainer,
    onTertiaryContainer = M3ExpLightOnTertiaryContainer,
    error = M3ExpLightError,
    onError = Color.White,
    errorContainer = M3ExpLightErrorContainer,
    onErrorContainer = Color(0xFF410002),
    background = M3ExpLightBackground,
    onBackground = M3ExpLightOnBackground,
    surface = M3ExpLightSurface,
    onSurface = M3ExpLightOnSurface,
    surfaceVariant = M3ExpLightSurfaceVariant,
    onSurfaceVariant = M3ExpLightOnSurfaceVariant,
    surfaceContainerLowest = M3ExpLightSurfaceContainerLowest,
    surfaceContainerLow = M3ExpLightSurfaceContainerLow,
    surfaceContainer = M3ExpLightSurfaceContainer,
    surfaceContainerHigh = M3ExpLightSurfaceContainerHigh,
    surfaceContainerHighest = M3ExpLightSurfaceContainerHighest,
    outline = M3ExpLightOutline,
    outlineVariant = M3ExpLightOutlineVariant,
    inversePrimary = M3ExpLightInversePrimary,
    inverseSurface = M3ExpLightInverseSurface,
    inverseOnSurface = M3ExpLightInverseOnSurface,
    surfaceTint = M3ExpLightSurfaceTint,
    scrim = M3ExpScrim,
    surfaceBright = M3ExpLightSurfaceBright,
    surfaceDim = M3ExpLightSurfaceDim,
    primaryFixed = M3ExpPrimaryFixed,
    primaryFixedDim = M3ExpPrimaryFixedDim,
    onPrimaryFixed = M3ExpOnPrimaryFixed,
    onPrimaryFixedVariant = M3ExpOnPrimaryFixedVariant,
    secondaryFixed = M3ExpSecondaryFixed,
    secondaryFixedDim = M3ExpSecondaryFixedDim,
    onSecondaryFixed = M3ExpOnSecondaryFixed,
    onSecondaryFixedVariant = M3ExpOnSecondaryFixedVariant,
    tertiaryFixed = M3ExpTertiaryFixed,
    tertiaryFixedDim = M3ExpTertiaryFixedDim,
    onTertiaryFixed = M3ExpOnTertiaryFixed,
    onTertiaryFixedVariant = M3ExpOnTertiaryFixedVariant,
)

val XuantongDarkColors = darkColorScheme(
    primary = M3ExpDarkPrimary,
    onPrimary = M3ExpDarkOnPrimary,
    primaryContainer = M3ExpDarkPrimaryContainer,
    onPrimaryContainer = M3ExpDarkOnPrimaryContainer,
    secondary = M3ExpDarkSecondary,
    onSecondary = M3ExpDarkOnSecondary,
    secondaryContainer = M3ExpDarkSecondaryContainer,
    onSecondaryContainer = M3ExpDarkOnSecondaryContainer,
    tertiary = M3ExpDarkTertiary,
    onTertiary = M3ExpDarkOnTertiary,
    tertiaryContainer = M3ExpDarkTertiaryContainer,
    onTertiaryContainer = M3ExpDarkOnTertiaryContainer,
    error = M3ExpDarkError,
    onError = Color(0xFF690005),
    errorContainer = M3ExpDarkErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    background = M3ExpDarkBackground,
    onBackground = M3ExpDarkOnBackground,
    surface = M3ExpDarkSurface,
    onSurface = M3ExpDarkOnSurface,
    surfaceVariant = M3ExpDarkSurfaceVariant,
    onSurfaceVariant = M3ExpDarkOnSurfaceVariant,
    surfaceContainerLowest = M3ExpDarkSurfaceContainerLowest,
    surfaceContainerLow = M3ExpDarkSurfaceContainerLow,
    surfaceContainer = M3ExpDarkSurfaceContainer,
    surfaceContainerHigh = M3ExpDarkSurfaceContainerHigh,
    surfaceContainerHighest = M3ExpDarkSurfaceContainerHighest,
    outline = M3ExpDarkOutline,
    outlineVariant = M3ExpDarkOutlineVariant,
    inversePrimary = M3ExpDarkInversePrimary,
    inverseSurface = M3ExpDarkInverseSurface,
    inverseOnSurface = M3ExpDarkInverseOnSurface,
    surfaceTint = M3ExpDarkSurfaceTint,
    scrim = M3ExpScrim,
    surfaceBright = M3ExpDarkSurfaceBright,
    surfaceDim = M3ExpDarkSurfaceDim,
    primaryFixed = M3ExpPrimaryFixed,
    primaryFixedDim = M3ExpPrimaryFixedDim,
    onPrimaryFixed = M3ExpOnPrimaryFixed,
    onPrimaryFixedVariant = M3ExpOnPrimaryFixedVariant,
    secondaryFixed = M3ExpSecondaryFixed,
    secondaryFixedDim = M3ExpSecondaryFixedDim,
    onSecondaryFixed = M3ExpOnSecondaryFixed,
    onSecondaryFixedVariant = M3ExpOnSecondaryFixedVariant,
    tertiaryFixed = M3ExpTertiaryFixed,
    tertiaryFixedDim = M3ExpTertiaryFixedDim,
    onTertiaryFixed = M3ExpOnTertiaryFixed,
    onTertiaryFixedVariant = M3ExpOnTertiaryFixedVariant,
)

/**
 * Material 3 Expressive 形状体系 (Shape Scale)
 *
 * 标准五级（4/8/12/16/28）+ Expressive 扩展三级：largeIncreased 20dp、extraLargeIncreased 32dp、
 * extraExtraLarge 48dp，对应 M3 Expressive 的强调容器与英雄区域形态。
 * 原实现把 extraSmall 压到 4dp，导致 507 处调用点各自硬编码 4/6/8/10/12dp，
 * 形状 token 形同虚设。这里重新对齐到 M3 基线（4/8/12/16/28）并补齐扩展级。
 */
val TianyanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/**
 * 天衍主题：Material 3 Expressive，支持 Monet 动态壁纸取色（系统色板优先，HCT 兜底）
 * 与曜石/素白静态基调（玄同色板）。
 */
@Composable
fun TianyanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    palette: ThemePalette = ThemePalette(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = generateSequence(view.context) { context ->
                (context as? ContextWrapper)?.baseContext?.takeIf { it !== context }
            }.filterIsInstance<Activity>().firstOrNull()?.window
            if (window != null) {
                window.isNavigationBarContrastEnforced = false
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val context = LocalContext.current
    val staticFallback = remember(darkTheme, palette) {
        themePaletteColorScheme(palette, darkTheme)
    }
    val colorScheme = if (dynamicColor) {
        // Monet 优先：API 31+ 直接透传系统色板（含系统对比度偏好的「原汁原味」）
        val systemMonet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
        rememberWallpaperDynamicColorScheme(
            darkTheme = darkTheme,
            systemScheme = systemMonet,
            fallback = staticFallback,
            style = palette.style,
        )
    } else {
        staticFallback
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        shapes = TianyanShapes,
    ) {
        val semantics = remember(colorScheme) { buildSemanticColors(colorScheme) }
        CompositionLocalProvider(LocalSemanticColors provides semantics, content = content)
    }
}

package top.tianyan.app.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** Window width tiers following the Material 3 breakpoints (600dp / 840dp). */
enum class RuntimeWidthClass {
    Compact, Medium, Expanded;

    /** Medium and Expanded keep a navigation rail or docked sidebar on screen. */
    val hasPersistentNavigation: Boolean get() = this != Compact

    companion object {
        const val MEDIUM_MIN_WIDTH_DP = 600f
        const val EXPANDED_MIN_WIDTH_DP = 840f
    }
}

fun widthClassOf(widthDp: Float): RuntimeWidthClass = when {
    widthDp >= RuntimeWidthClass.EXPANDED_MIN_WIDTH_DP -> RuntimeWidthClass.Expanded
    widthDp >= RuntimeWidthClass.MEDIUM_MIN_WIDTH_DP -> RuntimeWidthClass.Medium
    else -> RuntimeWidthClass.Compact
}

val LocalRuntimeWidthClass = compositionLocalOf { RuntimeWidthClass.Compact }

/**
 * Measures the current window in the active [LocalDensity], so the in-app page scale also moves
 * the breakpoints. Falls back to the configuration width before the window has been measured.
 */
@Composable
fun rememberRuntimeWidthClass(): RuntimeWidthClass {
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = if (windowWidthPx > 0) {
        windowWidthPx / density.density
    } else {
        val configuration = LocalConfiguration.current
        configuration.screenWidthDp * (configuration.densityDpi / 160f) / density.density
    }
    return widthClassOf(widthDp)
}

@Composable
fun ProvideRuntimeWidthClass(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRuntimeWidthClass provides rememberRuntimeWidthClass(), content = content)
}

/** Shared motion for adaptive pane changes; matches the reveal drawer spring. */
object RuntimeAdaptiveMotion {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    val dp: FiniteAnimationSpec<Dp> = spec()
    val float: FiniteAnimationSpec<Float> = spec()
}

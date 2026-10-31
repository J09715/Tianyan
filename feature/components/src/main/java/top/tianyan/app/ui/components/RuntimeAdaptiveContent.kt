package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Readable line length for list/settings pages on wide windows. */
val RuntimeContentMaxWidth = 840.dp

/** Width of the page area beside the rail/sidebar; unspecified outside the adaptive scaffold. */
val LocalRuntimePageWidth = compositionLocalOf { Dp.Unspecified }

/**
 * Extra side gutter that keeps page content at most [maxContentWidth] wide and centered. It grows
 * continuously with the page width (including while the sidebar animates), so there is no jump.
 */
@Composable
fun runtimeContentGutter(maxContentWidth: Dp = RuntimeContentMaxWidth): Dp {
    val pageWidth = LocalRuntimePageWidth.current
    if (pageWidth == Dp.Unspecified) return 0.dp
    return ((pageWidth - maxContentWidth) / 2).coerceAtLeast(0.dp)
}

/**
 * Content padding for scrolling pages: [horizontal] plus the centering gutter. Use it as a list's
 * contentPadding (or inside verticalScroll) so scroll gestures still cover the full page width.
 */
@Composable
fun runtimeContentPadding(horizontal: Dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues {
    val side = horizontal + runtimeContentGutter()
    return PaddingValues(start = side, end = side, top = top, bottom = bottom)
}

/** Grid item spanning the full row, for headers and banners in adaptive card grids. */
fun LazyGridScope.fullWidthItem(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
}

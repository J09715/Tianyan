package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

/** Apply top/side scaffold protection without cutting off scrolling at the navigation bar. */
@Composable
fun runtimeScrollViewport(scaffoldPadding: PaddingValues): Modifier {
    val direction = LocalLayoutDirection.current
    return Modifier.padding(
        start = scaffoldPadding.calculateStartPadding(direction),
        top = scaffoldPadding.calculateTopPadding(),
        end = scaffoldPadding.calculateEndPadding(direction),
    ).consumeWindowInsets(scaffoldPadding)
}

/** Keep the last item reachable while letting preceding items scroll behind the gesture bar. */
@Composable
fun runtimeScrollContentPadding(scaffoldPadding: PaddingValues, contentPadding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(direction),
        bottom = contentPadding.calculateBottomPadding() + scaffoldPadding.calculateBottomPadding(),
    )
}

package top.tianyan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Scoped to the conversation; terminal, browser and settings keep their own surfaces. */
val LocalChatBackgroundActive = staticCompositionLocalOf { false }

@Composable
fun chatBackdropSurface(base: Color = MaterialTheme.colorScheme.surfaceContainerLow): Color =
    if (LocalChatBackgroundActive.current) base.copy(alpha = 0.92f) else base

@Composable
fun chatBackdropChrome(): Color =
    if (LocalChatBackgroundActive.current) MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    else MaterialTheme.colorScheme.surface

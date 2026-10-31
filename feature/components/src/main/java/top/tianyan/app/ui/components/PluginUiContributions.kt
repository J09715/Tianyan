package top.tianyan.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Host-owned slots keep shared components and settings independent of plugin implementations. */
val LocalPluginMenuContent = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
val LocalOpenPluginManager = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalPluginDestinationActive = staticCompositionLocalOf { false }

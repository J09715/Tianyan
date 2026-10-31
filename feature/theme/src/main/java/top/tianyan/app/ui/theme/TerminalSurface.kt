package top.tianyan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Terminal canvas presets stay independent of the Material You controls around them. */
@Composable
fun terminalSurfaceColor(scheme: String): Color = when (scheme) {
    "obsidian" -> Color(0xFF0F1117)
    "matrix" -> Color(0xFF0A0F0D)
    "amber" -> Color(0xFF140F0A)
    "aurora" -> Color(0xFF0D1424)
    else -> MaterialTheme.colorScheme.surfaceContainerLowest
}

@Composable
fun terminalContentColor(scheme: String): Color = when (scheme) {
    "obsidian" -> Color(0xFFE2E2E9)
    "matrix" -> Color(0xFF10B981)
    "amber" -> Color(0xFFF59E0B)
    "aurora" -> Color(0xFF38BDF8)
    else -> MaterialTheme.colorScheme.onSurface
}

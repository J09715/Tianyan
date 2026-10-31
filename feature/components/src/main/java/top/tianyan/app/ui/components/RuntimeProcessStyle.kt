package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Process rows share geometry and neutral ink; status remains in text and accessibility semantics. */
@Composable
fun runtimeProcessColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun RuntimeProcessContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides runtimeProcessColor(), content = content)
}

/** Original 24-unit outline set: 18-unit optical bounds, 1.65-unit rounded strokes, no fills. */
@Composable
fun RuntimeProcessIcon(name: RuntimeIconName, running: Boolean = false) {
    val tint = runtimeProcessColor()
    Box(Modifier.size(18.dp).then(runtimeProcessPulse(running)), contentAlignment = Alignment.Center) {
        if (running) {
            RuntimeCircularProgressIndicator(Modifier.size(18.dp), color = tint)
        } else {
            val vector = remember(name) {
                ImageVector.Builder("Process$name", 18.dp, 18.dp, 24f, 24f).apply {
                    addPath(PathParser().parsePathString(processIconPath(name)).toNodes(),
                        stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
                }.build()
            }
            Icon(vector, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        }
    }
}

private const val DOCUMENT = "M14 3H5V21H19V8L14 3 M14 3V8H19"
private const val BOOK = "M12 5C9 3 6 3 3 4V20C6 19 9 19 12 21C15 19 18 19 21 20V4C18 3 15 3 12 5V21"

private fun processIconPath(name: RuntimeIconName): String = when (name) {
    RuntimeIconName.Brain -> "M12 5C10 2 6 3 6 6C3 6 2 10 4 12C2 15 4 18 7 18C7 22 11 22 12 19C13 22 17 22 17 18C20 18 22 15 20 12C22 10 21 6 18 6C18 3 14 2 12 5V19 M7 9L9 11 M17 9L15 11 M7 15H9 M17 15H15"
    RuntimeIconName.Terminal -> "M3 4H21V20H3Z M7 9L10 12L7 15 M13 15H17"
    RuntimeIconName.Document -> BOOK
    RuntimeIconName.BookOpen -> BOOK
    RuntimeIconName.WriteDocument -> "$DOCUMENT M8 12H15 M8 16H13"
    RuntimeIconName.Edit -> "M4 16L16 4L20 8L8 20L3 21Z M13 7L17 11"
    RuntimeIconName.Play -> "M7 3L21 12L7 21Z"
    RuntimeIconName.Phone -> "M6 3H18V21H6Z M10 17H14"
    RuntimeIconName.Download -> "M12 3V15 M7 10L12 15L17 10 M3 16V21H21V16"
    RuntimeIconName.Bookmark -> "M5 3H19V21L12 17L5 21Z"
    RuntimeIconName.Checklist -> "M3 6L5 8L8 4 M11 6H21 M3 14L5 16L8 12 M11 14H21 M11 20H21"
    RuntimeIconName.StickyNote -> "M3 3H21V15L15 21H3Z M15 21V15H21 M7 8H17 M7 12H12"
    RuntimeIconName.Search -> "M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10 M15 15L21 21"
    RuntimeIconName.History -> "M3 10A9 9 0 1 1 5 18 M3 4V10H9 M12 7V12L16 14"
    RuntimeIconName.Hammer -> "M4 7L8 3L15 3L12 6L21 15L17 19L8 10L6 12L3 9Z"
    RuntimeIconName.Bot -> "M4 7H20V21H4Z M12 3V7 M8 12H8.1 M16 12H16.1 M8 17H16 M1 11V17 M23 11V17"
    RuntimeIconName.Extension -> "M3 3H9C9 9 15 9 15 3H21V9C15 9 15 15 21 15V21H15C15 15 9 15 9 21H3Z"
    RuntimeIconName.Compress -> "M3 3L9 9 M9 4V9H4 M21 3L15 9 M15 4V9H20 M3 21L9 15 M4 15H9V20 M21 21L15 15 M15 20V15H20"
    RuntimeIconName.Chat -> "M3 3H21V17H9L3 21Z M7 8H17 M7 12H13"
    RuntimeIconName.Palette -> "M12 3C6 3 3 7 3 12C3 17 7 21 12 21C16 21 12 16 16 16H18C23 16 22 3 12 3Z M7 10H7.1 M10 7H10.1 M15 7H15.1 M18 10H18.1"
    else -> DOCUMENT
}

package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

internal val LocalClassicToolStatusDots = staticCompositionLocalOf { false }

@Composable
internal fun ToolStatusIndicator(icon: RuntimeIconName, color: Color, description: String?) {
    val semantics = Modifier.semantics { description?.let { contentDescription = it } }
    if (LocalClassicToolStatusDots.current) {
        Box(Modifier.size(7.dp).background(color, CircleShape).then(semantics))
    } else {
        RuntimeIcon(icon, Modifier.size(18.dp).then(semantics), color)
    }
}

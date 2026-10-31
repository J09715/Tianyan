package top.tianyan.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Draw-only breathing emphasis; finished or paused steps have no repeating animation. */
@Composable
fun runtimeProcessPulse(active: Boolean): Modifier {
    if (!active) return Modifier
    val transition = rememberInfiniteTransition(label = "processPulse")
    val opacity = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "processPulseOpacity",
    )
    // Read animation state in the layer to avoid recomposing text or the message body each frame.
    return Modifier.graphicsLayer { alpha = opacity.value }
}

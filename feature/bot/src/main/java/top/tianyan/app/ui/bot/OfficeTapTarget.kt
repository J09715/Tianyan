package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Invisible room-space hit target for the cat and the coffee machine easter eggs. */
@Composable
internal fun OfficeTapTarget(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    originX: Float,
    originY: Float,
    scale: Float,
    tag: String,
    label: String,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val targetW = roomDp(w, scale, density)
    val targetH = roomDp(h, scale, density)
    val hitW = maxOf(targetW, 48.dp)
    val hitH = maxOf(targetH, 48.dp)
    val extraW = with(density) { (hitW - targetW).toPx() / 2f }.roundToInt()
    val extraH = with(density) { (hitH - targetH).toPx() }.roundToInt()
    Box(
        Modifier
            .offset {
                IntOffset(
                    (originX + x * scale).roundToInt() - extraW,
                    (originY + PixelOfficeScene.y(y) * scale).roundToInt() - extraH,
                )
            }
            .size(hitW, hitH)
            .testTag(tag)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
    )
}

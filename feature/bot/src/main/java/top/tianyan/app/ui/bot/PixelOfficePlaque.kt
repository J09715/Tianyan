package top.tianyan.app.ui.bot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.feature.bot.R
import kotlin.math.roundToInt

/** Room pixels to dp, so a label stays the same size as the floor under it. */
internal fun roomDp(roomPx: Float, scale: Float, density: Density): Dp =
    (roomPx * scale / density.density).dp

/** Room pixels to sp. Dividing by font scale keeps pinch-zoom from fighting system text size. */
internal fun roomSp(roomPx: Float, scale: Float, density: Density): TextUnit {
    val px = roomPx * scale
    val sp = px / (density.density * density.fontScale.coerceAtLeast(0.5f))
    return sp.coerceAtLeast(0.5f).sp
}

internal fun officePlaqueColors(state: BotAvatarState, waiting: Boolean): Pair<Color, Color> {
    if (waiting) return Color(0xFF3A2A1E) to Color(0xFFF3D36B)
    return when (state) {
        BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> Color(0xFF16324A) to Color(0xFF7EC8E3)
        BotAvatarState.THINKING -> Color(0xFF5C4314) to Color(0xFFF3D36B)
        BotAvatarState.BLOCKED, BotAvatarState.FAILED -> Color(0xFF5C2420) to Color(0xFFFFAB9A)
        BotAvatarState.SUCCESS -> Color(0xFF1E4A32) to Color(0xFF8FD18A)
        BotAvatarState.IDLE -> Color(0xFFF6E7C8) to Color(0xFF5C4030)
    }
}

@Composable
internal fun PixelOfficeChip(
    name: String,
    text: String?,
    seconds: Int?,
    state: BotAvatarState,
    waiting: Boolean,
    bubbleTag: String?,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val (paper, ink) = officePlaqueColors(state, waiting)
    val nameSize = roomSp(3.2f, scale, density)
    val bodySize = roomSp(2.8f, scale, density)
    val wide = !text.isNullOrBlank() || seconds != null
    Surface(
        modifier = modifier
            .heightIn(max = roomDp(if (wide) 14f else 7f, scale, density))
            .clipToBounds()
            .then(if (bubbleTag != null) Modifier.testTag(bubbleTag) else Modifier),
        shape = RectangleShape,
        color = paper,
        border = BorderStroke(roomDp(0.7f, scale, density), Color(PixelOfficeRoom.TRIM)),
    ) {
        Column(
            Modifier
                .padding(
                    horizontal = roomDp(1.2f, scale, density),
                    vertical = roomDp(0.5f, scale, density),
                )
                .widthIn(max = roomDp(if (wide) 42f else 26f, scale, density)),
        ) {
            Text(
                text = name,
                color = ink,
                fontSize = nameSize,
                lineHeight = nameSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = when {
                seconds != null && !text.isNullOrBlank() -> "$text · $seconds"
                seconds != null -> seconds.toString()
                else -> text
            }
            if (!line.isNullOrBlank() && line != name) {
                Text(
                    text = line,
                    color = ink,
                    fontSize = bodySize,
                    lineHeight = bodySize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun OfficeObjectiveBoard(
    objective: String?,
    panX: Float,
    panY: Float,
    scale: Float,
    board: OfficeBoardSpec = OfficeSceneSpec.Default.boards.objective,
) {
    val density = LocalDensity.current
    Column(
        Modifier.offset {
            IntOffset(
                (panX + board.bounds.x * scale).roundToInt(),
                (panY + PixelOfficeScene.y(board.bounds.y.toFloat()) * scale).roundToInt(),
            )
        }.size(
            roomDp(board.bounds.width.toFloat(), scale, density),
            roomDp(board.bounds.height.toFloat(), scale, density),
        )
            .testTag("office-objective-board"),
    ) {
        Text(
            stringResource(R.string.fbot_pixel_office_banner),
            color = Color(0xFFE6B674),
            fontSize = roomSp(4.2f, scale, density),
            lineHeight = roomSp(5f, scale, density),
            maxLines = 1,
        )
        Text(
            stringResource(R.string.fbot_office_objective),
            color = Color(0xFFF8F0DC).copy(alpha = 0.62f),
            fontSize = roomSp(2.8f, scale, density),
            lineHeight = roomSp(3.4f, scale, density),
            maxLines = 1,
        )
        if (!objective.isNullOrBlank()) {
            Text(
                objective,
                color = Color(0xFFF8F0DC),
                fontSize = roomSp(3.5f, scale, density),
                lineHeight = roomSp(4.5f, scale, density),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
internal fun OfficeScreenLabel(
    text: String,
    roomX: Int,
    roomY: Int,
    originX: Int,
    originY: Int,
    scale: Float,
) {
    val density = LocalDensity.current
    Text(
        text = text.take(6),
        color = Color(0xFF102030),
        fontSize = roomSp(3f, scale, density),
        lineHeight = roomSp(3f, scale, density),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .offset {
                IntOffset(
                    originX + ((roomX) * scale).roundToInt(),
                    originY + ((roomY) * scale).roundToInt(),
                )
            }
            .widthIn(max = roomDp(16f, scale, density)),
    )
}

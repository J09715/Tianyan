package top.tianyan.app.ui.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import top.tianyan.app.feature.bot.R
import kotlin.math.roundToInt

private val OfficeZoneKind.label: Int get() = when (this) {
    OfficeZoneKind.THINK -> R.string.fbot_pixel_zone_think
    OfficeZoneKind.MEET -> R.string.fbot_pixel_zone_meet
    OfficeZoneKind.WORK -> R.string.fbot_pixel_zone_work
    OfficeZoneKind.COFFEE -> R.string.fbot_pixel_zone_coffee
    OfficeZoneKind.REST -> R.string.fbot_pixel_zone_rest
}

private const val ZONE_WIDTH = 38f
private const val ZONE_HEIGHT = 10.5f
private val ZoneInk = Color(0xFF3A2A1E)
private val ZoneWood = Color(0xFF5C4030)
private val ZoneHighlight = Color(0xFF8A6242)
private val ZoneShadow = Color(0xFF2A1C14)
private val ZoneScrew = Color(0xFFD8C7A6)
private val ZoneText = Color(0xFFF8F0DC)
private val ZoneLed = Color(0xFFFFD54F)

/** Wooden zone plaques mounted on the walls; they pan and scale with the room pixels. */
@Composable
internal fun OfficeZoneSigns(originX: Float, originY: Float, scale: Float, scene: OfficeSceneSpec = OfficeSceneSpec.Default) {
    val density = LocalDensity.current
    scene.zones.forEach { zone ->
        val label = stringResource(zone.kind.label)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (originX + zone.anchor.x * scale).roundToInt(),
                        (originY + PixelOfficeScene.y(zone.anchor.y.toFloat()) * scale).roundToInt(),
                    )
                }
                .size(roomDp(ZONE_WIDTH, scale, density), roomDp(ZONE_HEIGHT, scale, density))
                .drawBehind { plaque() }
                .testTag("office-zone-${zone.id}"),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = roomDp(1.6f, scale, density)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoneIcon(zone.kind, Modifier.size(roomDp(6.4f, scale, density)))
                Spacer(Modifier.width(roomDp(1.1f, scale, density)))
                Text(
                    text = label,
                    color = ZoneText,
                    fontSize = roomSp(4.4f, scale, density),
                    lineHeight = roomSp(4.4f, scale, density),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(roomDp(1.3f, scale, density))
                        .background(ZoneLed),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.plaque() {
    val unit = size.width / ZONE_WIDTH
    drawRect(ZoneInk)
    drawRect(ZoneWood, Offset(unit * 0.7f, unit * 0.7f), Size(size.width - unit * 1.4f, size.height - unit * 1.4f))
    drawRect(ZoneHighlight, Offset(unit * 0.7f, unit * 0.7f), Size(size.width - unit * 1.4f, unit * 0.55f))
    drawRect(
        ZoneShadow,
        Offset(unit * 0.7f, size.height - unit * 1.25f),
        Size(size.width - unit * 1.4f, unit * 0.55f),
    )
    for (x in listOf(unit * 1.1f, size.width - unit * 1.9f)) {
        for (y in listOf(unit * 1.1f, size.height - unit * 1.9f)) {
            drawRect(ZoneScrew, Offset(x, y), Size(unit * 0.7f, unit * 0.7f))
        }
    }
}

@Composable
private fun ZoneIcon(icon: OfficeZoneKind, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / 8f
        fun block(x: Float, y: Float, w: Float, h: Float, color: Color) {
            drawRect(color, Offset(x * cell, y * cell), Size(w * cell, h * cell))
        }
        when (icon) {
            OfficeZoneKind.THINK -> {
                block(3f, 1f, 3f, 3.5f, Color(0xFFFFD54F))
                block(3.7f, 1.5f, 1.6f, 2f, Color(0xFFFFF3C4))
                block(3.2f, 4.5f, 2.6f, 1f, Color(0xFF8D6E63))
                block(3.8f, 5.5f, 1.4f, 1f, Color(0xFF8D6E63))
                block(2f, 2f, 0.7f, 0.7f, Color(0xFFFFD54F))
                block(6.2f, 2.4f, 0.7f, 0.7f, Color(0xFFFFD54F))
                block(1.2f, 3.6f, 0.7f, 0.7f, Color(0xFFFFD54F))
            }
            OfficeZoneKind.MEET -> {
                block(1.6f, 1.6f, 2f, 2f, Color(0xFFFFE0B2))
                block(4.5f, 1.2f, 2f, 2f, Color(0xFFFFCC80))
                block(1f, 4.2f, 3.2f, 2.3f, Color(0xFF7EC8E3))
                block(3.8f, 4.2f, 3.2f, 2.3f, Color(0xFF8FD18A))
            }
            OfficeZoneKind.WORK -> {
                block(1f, 1.2f, 6f, 4.8f, Color(0xFF2A2438))
                block(1.7f, 1.8f, 4.6f, 3.2f, Color(0xFF7EC8E3))
                block(2.2f, 2.4f, 2f, 0.5f, Color(0xFFE6F7FF))
                block(3.4f, 6f, 1.2f, 1f, Color(0xFF2A2438))
                block(2.4f, 7f, 3.2f, 0.6f, Color(0xFF2A2438))
            }
            OfficeZoneKind.COFFEE -> {
                block(2f, 3.2f, 4f, 3f, Color(0xFFF6F0E4))
                block(2.6f, 3.8f, 2.8f, 1.6f, Color(0xFF8B5A2B))
                block(6f, 3.8f, 1f, 1.5f, Color(0xFFF6F0E4))
                block(1.5f, 6.2f, 5f, 0.6f, Color(0xFFB0BEC5))
                block(3.2f, 1.2f, 0.6f, 1.2f, Color(0xFFE6F7FF))
                block(4.4f, 0.8f, 0.6f, 1.4f, Color(0xFFE6F7FF))
            }
            OfficeZoneKind.REST -> {
                block(1f, 1.2f, 6f, 2.2f, Color(0xFFC46A28))
                block(0.8f, 3.2f, 6.4f, 2.2f, Color(0xFFE8873A))
                block(0.8f, 2.6f, 1f, 3.2f, Color(0xFFC46A28))
                block(6.2f, 2.6f, 1f, 3.2f, Color(0xFFC46A28))
                block(2.2f, 3.4f, 2.4f, 0.6f, Color(0xFFF6C07A))
                block(1.4f, 5.4f, 1.1f, 1f, Color(0xFF8B5A2B))
                block(5.5f, 5.4f, 1.1f, 1f, Color(0xFF8B5A2B))
            }
        }
    }
}

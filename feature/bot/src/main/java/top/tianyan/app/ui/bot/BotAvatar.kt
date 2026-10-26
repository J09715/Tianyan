package top.tianyan.app.ui.bot

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarRecipe
import top.tianyan.app.core.model.bot.PixelAvatarArt

/** 由名称种子生成的对称像素头像：同一 Bot 在任何设备上都得到同一张脸，并随运行态切换动作与微动效。 */
@Composable
fun BotAvatar(
    seed: String,
    colorHex: String,
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    state: BotAvatarState = if (active) BotAvatarState.ACTIVE else BotAvatarState.IDLE,
) {
    val fallback = MaterialTheme.colorScheme.primary
    val base = remember(colorHex) { parseHexColor(colorHex) ?: fallback }
    val pattern = remember(seed) { pixelPattern(seed) }
    val recipe = remember(seed) { PixelAvatarRecipe.decode(seed) }

    val isDynamic = state != BotAvatarState.IDLE
    val frame = if (isDynamic) {
        val transition = rememberInfiniteTransition(label = "bot_avatar_frame")
        val frameFloat by transition.animateFloat(
            initialValue = 0f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "bot_avatar_frame_float",
        )
        frameFloat.toInt().coerceIn(0, 1)
    } else {
        0
    }

    val pixels = remember(recipe, state, frame) { recipe?.let { PixelAvatarArt.cells(it, state, frame) } }
    val image by produceState<android.graphics.Bitmap?>(null, seed) {
        value = null
        value = withContext(Dispatchers.Default) { BotAvatarImage.decode(seed) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(base.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (pixels != null) Canvas(Modifier.fillMaxSize().testTag("bot-pixel-character")) {
            val cell = this.size.minDimension / PixelAvatarArt.EDGE
            val left = (this.size.width - cell * PixelAvatarArt.EDGE) / 2
            val top = (this.size.height - cell * PixelAvatarArt.EDGE) / 2
            pixels.forEachIndexed { index, color ->
                drawRect(Color(color), Offset(left + index % PixelAvatarArt.EDGE * cell,
                    top + index / PixelAvatarArt.EDGE * cell), ComposeSize(cell, cell))
            }
        }
        else if (image != null) Image(image!!.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("bot-custom-avatar"), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize().padding(size * 0.13f).testTag("bot-generated-avatar")) {
            val cell = this.size.minDimension / GRID
            val originX = (this.size.width - cell * GRID) / 2f
            val originY = (this.size.height - cell * GRID) / 2f
            for (index in 0 until GRID * GRID) {
                if (!pattern[index]) continue
                val column = index % GRID
                val row = index / GRID
                drawRoundRect(
                    color = if ((row + column) % 3 == 0) base else base.copy(alpha = 0.82f),
                    topLeft = Offset(originX + column * cell + cell * 0.06f, originY + row * cell + cell * 0.06f),
                    size = ComposeSize(cell * 0.88f, cell * 0.88f),
                    cornerRadius = CornerRadius(cell * 0.20f),
                )
            }
        }
        if (active || state != BotAvatarState.IDLE) {
            val pulseColor = when (state) {
                BotAvatarState.THINKING -> Color(0xFFFFB74D)
                BotAvatarState.EXECUTING -> MaterialTheme.colorScheme.primary
                BotAvatarState.BLOCKED -> MaterialTheme.colorScheme.error
                BotAvatarState.SUCCESS -> Color(0xFF4CAF50)
                BotAvatarState.FAILED -> MaterialTheme.colorScheme.error
                else -> base
            }
            val transition = rememberInfiniteTransition(label = "bot_avatar_pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bot_avatar_alpha",
            )
            Box(
                Modifier
                    .matchParentSize()
                    .border(2.dp, pulseColor.copy(alpha = alpha), RoundedCornerShape(size * 0.30f)),
            )
        }
    }
}

@Composable
fun BotAvatar(
    bot: Bot,
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    state: BotAvatarState = if (active) BotAvatarState.ACTIVE else BotAvatarState.IDLE,
) = BotAvatar(
    seed = bot.avatarSeed,
    colorHex = bot.colorHex,
    size = size,
    modifier = modifier,
    active = active,
    state = state,
)

private fun parseHexColor(hex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

private fun pixelPattern(seed: String): BooleanArray {
    var state = seed.fold(0x811C9DC5.toInt()) { acc, char -> acc xor char.code * 16777619 }
    state = state or 1
    val cells = BooleanArray(GRID * GRID)
    for (row in 0 until GRID) {
        for (column in 0..GRID / 2) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val lit = (state ushr 8) % 100 < 46
            cells[row * GRID + column] = lit
            cells[row * GRID + (GRID - 1 - column)] = lit
        }
    }
    return cells
}

private const val GRID = 5

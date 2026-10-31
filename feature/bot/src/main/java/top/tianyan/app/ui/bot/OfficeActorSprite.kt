package top.tianyan.app.ui.bot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarRecipe
import top.tianyan.app.feature.bot.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun OfficeActorSprite(
    actor: OfficeActor,
    recipe: PixelAvatarRecipe,
    frame: Int,
    yawnSlot: Int,
    originX: Int,
    originY: Int,
    scale: Float,
    sceneWidth: Int,
    sceneHeight: Int,
    scene: OfficeSceneSpec,
    walkGraph: OfficeWalkGraph,
    bubble: String?,
    screen: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val target = remember(actor.x, actor.y) { OfficePoint(actor.x, actor.y) }
    val position = remember { mutableStateOf(OfficePoint(actor.x, actor.y)) }
    LaunchedEffect(target, scene, walkGraph) {
        val path = officeWalkPath(scene, walkGraph, position.value, target)
        if (path.size <= 1) {
            position.value = target
            return@LaunchedEffect
        }
        for (index in 1 until path.size) {
            val from = path[index - 1]
            val to = path[index]
            val distance = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()
            if (distance <= 0.01f) continue
            val duration = (distance * 3f).toInt().coerceIn(120, 600)
            animate(0f, 1f, animationSpec = tween(duration, easing = LinearEasing)) { value, _ ->
                position.value = OfficePoint(
                    (from.x + (to.x - from.x) * value).roundToInt(),
                    (from.y + (to.y - from.y) * value).roundToInt(),
                )
            }
        }
        position.value = target
    }
    val x = position.value.x
    val y = position.value.y
    val walking = position.value != target
    val displayPose = if (walking) OfficePose.STAND else actor.pose
    val displayState = if (walking) BotAvatarState.ACTIVE else actor.state
    val displayIdle = if (walking) OfficeIdleAction.NONE else actor.idleAction
    val offset = officeFrameOffset(actor.botId)
    val localFrame = (frame + offset) % 3
    val yawn = !walking && actor.state == BotAvatarState.IDLE &&
        ((yawnSlot + offset) % 8 == 7 || actor.idleAction == OfficeIdleAction.DOZE)
    val waiting = actor.waitSeconds != null
    val image = PixelOfficeFrames.bitmap(
        recipe, displayPose, displayState, localFrame, waiting, yawn, displayIdle,
    )
    val mood = PixelOfficeMood.image(actor.state, localFrame, waiting, yawn)
    val density = LocalDensity.current
    val stateLabel = officeStateLabel(actor.state)
    val selectLabel = stringResource(R.string.fbot_pixel_office_select_actor, actor.name)
    val wait = actor.waitSeconds
    val actorLabel = if (wait != null) {
        stringResource(R.string.fbot_pixel_office_actor_waiting, actor.name, stateLabel, wait)
    } else {
        stringResource(R.string.fbot_pixel_office_actor_description, actor.name, stateLabel)
    }
    val left = originX + ((x - PixelOfficeSprite.WORLD_WIDTH / 2f) * scale).roundToInt()
    val top = originY + ((y - PixelOfficeSprite.WORLD_HEIGHT) * scale).roundToInt()
    val sw = (PixelOfficeSprite.WORLD_WIDTH * scale).roundToInt().coerceAtLeast(1)
    val sh = (PixelOfficeSprite.WORLD_HEIGHT * scale).roundToInt().coerceAtLeast(1)
    val minHit = with(density) { 48.dp.toPx() }
    val hitWidth = max(sw.toFloat(), minHit)
    val hitHeight = max(sh.toFloat(), minHit)
    val hitLeft = left - ((hitWidth - sw) / 2f).roundToInt()
    val hitTop = top - (hitHeight - sh).roundToInt()
    val spriteLeft = ((hitWidth - sw) / 2f).roundToInt()
    val spriteTop = (hitHeight - sh).roundToInt()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Canvas(
        Modifier
            .offset { IntOffset(hitLeft, hitTop) }
            .size(with(density) { hitWidth.toDp() }, with(density) { hitHeight.toDp() })
            .testTag("studio-actor-${actor.botId}")
            .semantics { contentDescription = actorLabel }
            .clickable(role = Role.Button, onClickLabel = selectLabel, onClick = onSelect),
    ) {
        val spriteW = sw.toFloat()
        val spriteH = sh.toFloat()
        val drawX = spriteLeft.toFloat()
        val drawY = spriteTop.toFloat()
        drawOval(
            Color(0x332A1E14),
            topLeft = Offset(drawX + spriteW * 0.18f, drawY + spriteH * 0.93f),
            size = Size(spriteW * 0.64f, spriteH * 0.07f),
        )
        if (selected) {
            drawOval(
                highlight,
                topLeft = Offset(drawX + spriteW * 0.05f, drawY + spriteH * 0.82f),
                size = Size(spriteW * 0.9f, spriteH * 0.16f),
            )
        }
        drawImage(
            image,
            dstOffset = IntOffset(spriteLeft, spriteTop),
            dstSize = IntSize(sw, sh),
            filterQuality = FilterQuality.None,
        )
    }
    if (mood != null) {
        val moodSize = (PixelOfficeMood.EDGE * scale).roundToInt().coerceAtLeast(1)
        Canvas(
            Modifier
                .offset { IntOffset(left + (sw - moodSize) / 2, top - moodSize) }
                .size(with(density) { moodSize.toDp() }),
        ) {
            drawImage(
                mood,
                dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None,
            )
        }
    }
    if (!walking && actor.pose == OfficePose.TYPE && !screen.isNullOrBlank()) {
        OfficeScreenLabel(
            text = screen,
            roomX = x - 8,
            roomY = y - 32,
            originX = originX,
            originY = originY,
            scale = scale,
        )
    }
    val visible = left < sceneWidth && left + sw > 0 && top < sceneHeight && top + sh > 0
    val speaking = visible && !bubble.isNullOrBlank()
    if (!speaking && selected) {
        val nameLift = ((if (mood != null) PixelOfficeMood.EDGE else 0) + 8f) * scale
        PixelOfficeChip(
            name = actor.name,
            text = null,
            seconds = null,
            state = actor.state,
            waiting = waiting,
            bubbleTag = null,
            scale = scale,
            modifier = Modifier.offset { IntOffset(left, (top - nameLift).roundToInt()) }
                .clickable(onClick = onSelect),
        )
    }
    if (speaking) {
        val bubbleW = (42f * scale).roundToInt()
        val bubbleH = ((if (mood != null) PixelOfficeMood.EDGE else 0) + 16f) * scale
        val bubbleLeft = (left + sw / 2 - bubbleW / 2).coerceIn(0, (sceneWidth - bubbleW).coerceAtLeast(0))
        val bubbleTop = (top - bubbleH).roundToInt().coerceAtLeast(0)
        PixelOfficeChip(
            name = actor.name,
            text = bubble,
            seconds = actor.waitSeconds,
            state = actor.state,
            waiting = waiting,
            bubbleTag = "studio-bubble-${actor.botId}",
            scale = scale,
            modifier = Modifier.offset { IntOffset(bubbleLeft, bubbleTop) }
                .clickable(onClick = onSelect),
        )
    }
}

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val scope = rememberCoroutineScope()
    val initial = remember(scope) {
        (scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    }
    var enabled by remember { mutableStateOf(initial) }
    LaunchedEffect(scope) {
        val scale = currentCoroutineContext()[MotionDurationScale] ?: return@LaunchedEffect
        snapshotFlow { scale.scaleFactor }.collect { enabled = it > 0f }
    }
    return enabled
}

internal data class OfficeIdleAnimation(val frame: Int, val yawnSlot: Int)

@Composable
internal fun rememberOfficeIdleAnimation(): OfficeIdleAnimation {
    val transition = rememberInfiniteTransition(label = "pixel-office")
    val frameFloat = transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "pixel-office-frame",
    )
    val yawnFloat = transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing), RepeatMode.Restart),
        label = "pixel-office-yawn",
    )
    val frame by remember { derivedStateOf { frameFloat.value.toInt().coerceIn(0, 2) } }
    val yawnSlot by remember { derivedStateOf { yawnFloat.value.toInt().coerceIn(0, 7) } }
    return OfficeIdleAnimation(frame, yawnSlot)
}

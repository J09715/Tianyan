package top.tianyan.app.ui.bot

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** One small animated pixel rectangle in room coordinates. */
internal data class OfficeAmbientDot(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Int,
)

/**
 * Ambient life over the baked room: steam, screen glints, window lights, lamp pools,
 * cat tails and celebration confetti. Pure in time so it is cheap to test.
 */
internal object PixelOfficeAmbient {
    private const val TAU = (Math.PI * 2).toFloat()

    private const val WHITE = 0xFFF8EE
    private const val CREAM = 0xF3E2C0
    private const val GREEN = 0x8FD18A
    private const val CYAN = 0xE6F7FF
    private const val PINK = 0xFF7A90
    private const val AMBER = 0xF4DB9A
    private const val LAMP_WARM = 0xFFE2A0
    private const val COOL = 0x8FC8F0

    fun dots(
        nowMs: Long,
        actors: List<OfficeActor>,
        celebrating: Set<String> = emptySet(),
        interactionAt: Map<String, Long> = emptyMap(),
        scene: OfficeSceneSpec = OfficeSceneSpec.Default,
    ): List<OfficeAmbientDot> {
        val out = Dots()
        val t = nowMs / 1000f
        scene.coffeeMachines.forEach { steam(out, t, nowMs, interactionAt[it.id] ?: 0L, it) }
        waterCooler(out, t, scene.ambient.waterCooler)
        monitors(out, t, nowMs, scene.desks)
        meetingScreen(out, t, nowMs, scene.ambient.meetingScreen)
        serverLeds(out, nowMs, scene.coffeeMachines, scene.ambient.serverRack)
        window(out, t, scene.ambient)
        lamps(out, t, scene.ambient.lamps)
        scene.cats.forEach { catLife(out, t, it) }
        scene.cats.forEach { cat ->
            val at = interactionAt[cat.id] ?: 0L
            if (at > 0L) hearts(out, cat.heart.x.toFloat(), cat.heart.y.toFloat(), nowMs - at)
        }
        confetti(out, t, actors, celebrating)
        return out.list
    }

    private class Dots {
        val list = ArrayList<OfficeAmbientDot>(72)

        fun add(x: Float, y: Float, w: Float, h: Float, alpha: Float, rgb: Int) {
            if (alpha <= 0.02f) return
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24
            list += OfficeAmbientDot(x, y, w, h, a or (rgb and 0xFFFFFF))
        }
    }

    private fun phase(t: Float, period: Float, offset: Float): Float =
        (((t / period + offset) % 1f) + 1f) % 1f

    private fun steam(d: Dots, t: Float, nowMs: Long, brewAt: Long, machine: OfficeCoffeeMachineSpec) {
        for (i in 0 until 3) {
            val p = phase(t, 1.7f, i / 3f)
            val x = machine.steam.x.toFloat() + sin(p * TAU + i * 2.1f) * 2.2f
            val y = machine.steam.y.toFloat() - p * 9f
            d.add(x, y, 0.7f, 0.7f, 0.5f * (1f - p), WHITE)
            d.add(x + 0.6f, y + 0.4f, 0.4f, 0.4f, 0.32f * (1f - p), CREAM)
        }
        val wand = phase(t, 2.2f, 0.4f)
        d.add(machine.wand.x.toFloat() + sin(wand * TAU) * 1.1f, machine.wand.y.toFloat() - wand * 8f, 0.5f, 0.5f, 0.32f * (1f - wand), WHITE)
        val age = nowMs - brewAt
        if (brewAt > 0L && age in 0L..1600L) {
            val p = age / 1600f
            for (i in 0 until 5) {
                val x = machine.x.toFloat() + i * 3.2f + sin(p * 6f + i) * 1.1f
                val y = machine.steam.y.toFloat() - p * 12f - i * 0.8f
                d.add(x, y, 0.8f, 0.8f, (1f - p) * 0.75f, WHITE)
            }
        }
    }

    private fun waterCooler(d: Dots, t: Float, cooler: OfficeBounds) {
        val x = cooler.x + 5f
        val y = cooler.y + 14f
        for (i in 0 until 2) {
            val p = phase(t, 2.6f, i * 0.46f + 0.1f)
            d.add(x + i * 4f + sin(p * TAU + i) * 0.7f, y - p * 10f, 0.5f, 0.5f, 0.55f * (1f - p * 0.4f), CYAN)
        }
    }

    private fun monitors(d: Dots, t: Float, nowMs: Long, desks: List<OfficeDeskSpec>) {
        desks.sortedBy { it.animationPhase }.forEach { desk ->
            val i = desk.animationPhase
            val sx = desk.screen.x.toFloat()
            val sy = desk.screen.y.toFloat()
            val p = phase(t, 2.8f, i * 0.27f)
            val glow = 0.24f + 0.12f * ((sin(t * 3f + i) + 1f) / 2f)
            d.add(sx + 2f, sy + 1.5f + p * 7f, 9f, 0.35f, glow, GREEN)
            if ((nowMs / 480L + i) % 2L == 0L) {
                d.add(sx + 1.2f, sy + 8.6f, 1.1f, 0.5f, 0.7f, CYAN)
            }
        }
    }

    private fun meetingScreen(d: Dots, t: Float, nowMs: Long, screen: OfficeBounds) {
        val p = phase(t, 6f, 0f)
        val x = screen.x + 3f
        val y = screen.y + 3f
        d.add(x + p * (screen.width - 9f), y, 0.35f, 12f, 0.10f, 0xFFFFFF)
        if ((nowMs / 1000L) % 2L == 0L) d.add(screen.right - 4f, y, 0.6f, 0.6f, 0.8f, 0xE85D4C)
    }

    private fun serverLeds(
        d: Dots,
        nowMs: Long,
        machines: List<OfficeCoffeeMachineSpec>,
        rack: OfficeBounds,
    ) {
        val colors = intArrayOf(0x8FD18A, 0x7EC8E3, 0xF3D36B, 0xE85D4C)
        val x = rack.x + 10f
        val y = rack.y + 8f
        for (i in 0 until 5) {
            if ((nowMs / 380L + i) % 3L != 0L) continue
            d.add(x, y + i * 4f, 0.6f, 0.6f, 0.9f, colors[i % colors.size])
        }
        if ((nowMs / 700L) % 2L == 0L) machines.forEach { machine ->
            d.add(machine.indicator.x.toFloat(), machine.indicator.y.toFloat(), 0.5f, 0.5f, 0.9f, 0xE85D4C)
        }
    }

    private fun window(d: Dots, t: Float, ambient: OfficeAmbientSpec) {
        val bounds = ambient.window
        ambient.windowLights.forEachIndexed { i, light ->
            val k = (sin(t * 1.6f + i * 1.3f) + 1f) / 2f
            if (k > 0.46f) {
                d.add(bounds.x + light.dx.toFloat(), bounds.y + light.dy.toFloat(), 1.2f, 1.2f, 0.3f + 0.5f * k, AMBER)
            }
        }
        cloud(d, bounds.x + 3f + phase(t, 19f, 0.05f) * 27f, bounds.y + 3f, bounds.x + 33f)
        cloud(d, bounds.x + 37f + phase(t, 25f, 0.4f) * 27f, bounds.y + 5f, bounds.x + 66f)
    }

    private fun cloud(d: Dots, x: Float, y: Float, limit: Float) {
        if (x + 10f > limit) return
        d.add(x + 2f, y, 6f, 1.1f, 0.55f, WHITE)
        d.add(x, y + 1.1f, 10f, 1.1f, 0.7f, WHITE)
        d.add(x + 3f, y + 2.2f, 6f, 0.9f, 0.5f, WHITE)
    }

    private fun lamps(d: Dots, t: Float, lamps: List<OfficeLampSpec>) {
        lamps.forEach { lamp ->
            val x = lamp.anchor.x.toFloat()
            val y = lamp.anchor.y.toFloat()
            val offset = lamp.phase.toFloat()
            val pulse = (sin(t * 1.3f + offset) + 1f) / 2f
            val alpha = 0.045f + 0.035f * pulse
            d.add(x - 5f, y, 10f, 2.3f, alpha, LAMP_WARM)
            d.add(x - 3f, y + 2.3f, 6f, 1.4f, alpha * 0.65f, LAMP_WARM)
            d.add(x - 0.5f, y - 1f, 1.2f, 0.8f, 0.42f, 0xFFF3C4)
        }
        lamps.firstOrNull()?.let { lamp ->
            d.add(
                lamp.anchor.x + cos(t * 2.4f) * 3.2f,
                lamp.anchor.y + sin(t * 4.8f) * 1.4f,
                0.6f, 0.6f, 0.85f, WHITE,
            )
        }
    }

    private fun catLife(d: Dots, t: Float, cat: OfficeCatSpec) {
        val x = cat.x.toFloat()
        val y = cat.y.toFloat()
        when (cat.style) {
            OfficeCatStyle.SLEEPING -> {
                d.add(x + 13.5f, y + 2.5f + sin(t * 1.8f) * 0.5f, 0.8f, 0.8f, 0.9f, 0xD99A52)
                d.add(x + 14.3f, y + 2.5f + sin(t * 1.8f) * 0.5f, 0.5f, 0.5f, 0.8f, 0x8D5A36)
                val z = t % 12f
                if (z < 2.4f) {
                    val rise = z / 2.4f
                    d.add(x + 15f, y - 2f - rise * 3f, 2f, 0.35f, 0.7f, COOL)
                    d.add(x + 15.6f, y - 3.2f - rise * 3f, 0.35f, 0.7f, 0.7f, COOL)
                    d.add(x + 15f, y - 4.4f - rise * 3f, 2f, 0.35f, 0.7f, COOL)
                }
            }
            OfficeCatStyle.PERCHED -> {
                d.add(x + 18.5f, y + 14f + sin(t * 1.5f + 1f) * 0.5f, 0.8f, 0.8f, 0.9f, 0xD99A52)
                d.add(x + 19.4f, y + 13.3f + sin(t * 1.5f + 1f) * 0.5f, 0.6f, 0.6f, 0.8f, 0xC9873E)
            }
        }
    }

    private fun hearts(d: Dots, x: Float, y: Float, age: Long) {
        if (age < 0L || age > 2600L) return
        val p = age / 2600f
        for (i in 0 until 3) {
            val hx = x + (i - 1) * 3.2f + sin(p * 4f + i) * 0.6f
            val hy = y - 3f - p * 12f - i * 1.1f
            val alpha = (1f - p) * 0.9f
            d.add(hx, hy, 0.7f, 0.7f, alpha, PINK)
            d.add(hx + 0.9f, hy, 0.7f, 0.7f, alpha, PINK)
            d.add(hx - 0.3f, hy + 0.7f, 2.3f, 0.9f, alpha, PINK)
            d.add(hx + 0.3f, hy + 1.6f, 1.1f, 0.6f, alpha, PINK)
        }
    }

    private fun confetti(d: Dots, t: Float, actors: List<OfficeActor>, celebrating: Set<String>) {
        if (celebrating.isEmpty()) return
        val palette = intArrayOf(0xF3D36B, 0xF0A3B5, 0x8FD18A, 0x7EC8E3, 0xFFAB9A)
        actors.forEach { actor ->
            if (actor.botId !in celebrating) return@forEach
            val seed = actor.botId.hashCode()
            for (i in 0 until 7) {
                val p = phase(t, 1.9f, ((seed ushr (i * 3)) and 7) / 8f + i * 0.13f)
                val x = actor.x + (((seed ushr (i * 2)) and 7) - 3.5f) * 0.9f + sin(t * 3f + i) * 0.8f
                val y = actor.y - 33f + p * 13f
                d.add(x, y, 0.7f, 0.7f, 0.95f - p * 0.45f, palette[i % palette.size])
            }
        }
    }
}

/**
 * Draws the ambient dots and the wall clock hands over the room. The layer redraws at
 * 8 fps, so it stays cheap while still feeling alive.
 */
@Composable
internal fun OfficeAmbientLayer(
    actors: List<OfficeActor>,
    celebrating: Set<String>,
    interactionAt: Map<String, Long>,
    originX: Int,
    originY: Int,
    scale: Float,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    scene: OfficeSceneSpec = OfficeSceneSpec.Default,
) {
    val slot = if (motionEnabled) rememberOfficeAmbientSlot() else 0
    val now = if (motionEnabled) remember(slot) { SystemClock.uptimeMillis() } else SystemClock.uptimeMillis()
    val wall = if (motionEnabled) remember(slot) { System.currentTimeMillis() } else System.currentTimeMillis()
    Canvas(modifier) {
        PixelOfficeAmbient.dots(now, actors, celebrating, interactionAt, scene).forEach { dot ->
            drawRect(
                Color(dot.color),
                Offset(originX + dot.x * scale, originY + PixelOfficeScene.y(dot.y) * scale),
                Size(max(1f, dot.w * scale), max(1f, dot.h * scale)),
            )
        }
        drawClockHands(wall, originX, originY, scale, scene.ambient.clock)
    }
}

@Composable
private fun rememberOfficeAmbientSlot(): Int {
    val transition = rememberInfiniteTransition(label = "office-ambient")
    val tick = transition.animateFloat(
        initialValue = 0f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "office-ambient-tick",
    )
    val slot by remember { derivedStateOf { tick.value.toInt().coerceIn(0, 63) } }
    return slot
}

private fun DrawScope.drawClockHands(
    wallMs: Long,
    originX: Int,
    originY: Int,
    scale: Float,
    clock: OfficeAnchor,
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = wallMs }
    val second = calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000f
    val minute = calendar.get(Calendar.MINUTE) + second / 60f
    val hour = (calendar.get(Calendar.HOUR) % 12) + minute / 60f
    val cx = originX + clock.x * scale
    val cy = originY + PixelOfficeScene.y(clock.y.toFloat()) * scale
    val ink = Color(0xFF3A2A1E)
    fun hand(angle: Float, length: Float, color: Color, width: Float) {
        val radians = Math.toRadians((angle - 90f).toDouble())
        drawLine(
            color,
            Offset(cx, cy),
            Offset(cx + cos(radians).toFloat() * length * scale, cy + sin(radians).toFloat() * length * scale),
            strokeWidth = max(1f, width * scale),
            cap = StrokeCap.Square,
        )
    }
    hand(hour / 12f * 360f, 2.5f, ink, 0.55f)
    hand(minute / 60f * 360f, 3.9f, ink, 0.4f)
    hand(second / 60f * 360f, 4.2f, Color(0xFFE85D4C), 0.28f)
    drawCircle(ink, radius = max(1f, 0.55f * scale), center = Offset(cx, cy))
}

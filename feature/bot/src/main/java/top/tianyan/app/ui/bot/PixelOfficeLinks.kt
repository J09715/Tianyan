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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Worker → report recipient edge. The report recipient is persisted on BotAssignment. */
internal data class OfficeReportLink(
    val taskId: String,
    val fromBotId: String,
    val toBotId: String,
    val active: Boolean,
    val completedAt: Long?,
    val failed: Boolean = false,
    val dependency: Boolean = false,
)

/** Prefer true depends_on edges; keep reportToBotId edges only where no dependency edge exists. */
internal fun officeCollaborationLinks(
    assignments: List<BotAssignment>,
    members: List<Bot>,
    now: Long,
): List<OfficeReportLink> {
    val dependencyLinks = officeDependencyLinks(assignments, members, now)
    val reportLinks = officeReportLinks(assignments, members, now)
    val dependencyPairs = dependencyLinks.map { it.fromBotId to it.toBotId }.toSet()
    return (dependencyLinks + reportLinks.filter { (it.fromBotId to it.toBotId) !in dependencyPairs })
        .take(5)
}

/** Completed prerequisite → dependent task edge; the link id belongs to the dependent task. */
internal fun officeDependencyLinks(
    assignments: List<BotAssignment>,
    members: List<Bot>,
    now: Long,
): List<OfficeReportLink> {
    val memberIds = members.map { it.id }.toSet()
    val byId = assignments.associateBy { it.id }
    return assignments.asSequence()
        .sortedByDescending { it.startedAt }
        .mapNotNull { task ->
            val fromTask = task.dependsOn.firstNotNullOfOrNull { byId[it] } ?: return@mapNotNull null
            val from = fromTask.botId
            val to = task.botId
            if (from == to || from !in memberIds || to !in memberIds) return@mapNotNull null
            val active = task.status == BotAssignmentStatus.RUNNING || task.status == BotAssignmentStatus.QUEUED
            val recent = fromTask.status == BotAssignmentStatus.COMPLETED &&
                fromTask.finishedAt?.let { now - it in 0..8_000L } == true
            if (!active && !recent) return@mapNotNull null
            OfficeReportLink(
                taskId = task.id,
                fromBotId = from,
                toBotId = to,
                active = active,
                completedAt = fromTask.finishedAt,
                failed = fromTask.status == BotAssignmentStatus.FAILED,
                dependency = true,
            )
        }
        .take(5)
        .toList()
}

internal fun officeReportLinks(
    assignments: List<BotAssignment>,
    members: List<Bot>,
    now: Long,
): List<OfficeReportLink> {
    val memberIds = members.map { it.id }.toSet()
    return assignments.asSequence()
        .sortedByDescending { it.startedAt }
        .mapNotNull { task ->
            val target = task.reportToBotId ?: return@mapNotNull null
            if (task.botId == target || task.botId !in memberIds || target !in memberIds) {
                return@mapNotNull null
            }
            val active = task.status == BotAssignmentStatus.RUNNING ||
                task.status == BotAssignmentStatus.BLOCKED ||
                task.status == BotAssignmentStatus.INTERRUPTED
            val recent = task.finishedAt?.let { now - it in 0..8_000L } == true &&
                (task.status == BotAssignmentStatus.COMPLETED || task.status == BotAssignmentStatus.FAILED)
            if (!active && !recent) return@mapNotNull null
            OfficeReportLink(
                taskId = task.id,
                fromBotId = task.botId,
                toBotId = target,
                active = active,
                completedAt = task.finishedAt,
                failed = task.status == BotAssignmentStatus.FAILED,
            )
        }
        .take(5)
        .toList()
}

/**
 * Draws dotted collaboration links between desks. Active links carry a data packet;
 * recently completed tasks play a one-shot folder handoff toward the report recipient.
 */
@Composable
internal fun OfficeReportLinksLayer(
    links: List<OfficeReportLink>,
    actors: List<OfficeActor>,
    walkingTaskIds: Set<String> = emptySet(),
    originX: Int,
    originY: Int,
    scale: Float,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val slot = if (motionEnabled) rememberOfficeLinksSlot() else 0
    val now = if (motionEnabled) remember(slot) { System.currentTimeMillis() } else System.currentTimeMillis()
    Canvas(modifier.testTag("office-report-links")) {
        val byId = actors.associateBy { it.botId }
        links.forEach { link ->
            val from = byId[link.fromBotId] ?: return@forEach
            val to = byId[link.toBotId] ?: return@forEach
            if (link.fromBotId == link.toBotId) return@forEach
            drawReportLink(link, from, to, now, originX, originY, scale, walkingTaskIds, frozen = !motionEnabled)
        }
    }
}

@Composable
private fun rememberOfficeLinksSlot(): Int {
    val transition = rememberInfiniteTransition(label = "office-links")
    val tick = transition.animateFloat(
        initialValue = 0f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "office-links-tick",
    )
    val slot by remember { derivedStateOf { tick.value.toInt().coerceIn(0, 63) } }
    return slot
}

private fun DrawScope.drawReportLink(
    link: OfficeReportLink,
    from: OfficeActor,
    to: OfficeActor,
    now: Long,
    originX: Int,
    originY: Int,
    scale: Float,
    walkingTaskIds: Set<String>,
    frozen: Boolean,
) {
    val sx = originX + from.x * scale
    val sy = originY + (from.y - 14f) * scale
    val tx = originX + to.x * scale
    val ty = originY + (to.y - 14f) * scale
    val ctrlX = (sx + tx) / 2f
    val ctrlY = min(sy, ty) - 18f * scale
    val age = link.completedAt?.let { now - it } ?: 0L
    if (!link.active && (frozen || age < 0L || age > 8_000L)) return
    val accent = when {
        link.failed -> Color(0xFFE85D4C)
        link.dependency -> Color(0xFFC9A6E8)
        link.active -> Color(0xFF7EC8E3)
        else -> Color(0xFF8FD18A)
    }
    val fade = if (link.active) 1f else (1f - age / 8_000f).coerceIn(0f, 1f)
    val line = Path().apply {
        moveTo(sx, sy)
        quadraticBezierTo(ctrlX, ctrlY, tx, ty)
    }
    drawPath(
        line,
        accent.copy(alpha = 0.3f * fade),
        style = Stroke(
            width = max(1f, 0.42f * scale),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.9f * scale, 0.7f * scale)),
        ),
    )
    val dot = max(1f, 0.6f * scale)
    for (i in 1 until 18) {
        val t = i / 18f
        val point = quadratic(sx, sy, ctrlX, ctrlY, tx, ty, t)
        val pulse = if (frozen) 1f else 0.72f + 0.28f * ((sin(now / 520.0 + i * 0.6).toFloat() + 1f) / 2f)
        drawRect(
            accent.copy(alpha = 0.62f * fade * pulse),
            Offset(point.x - dot / 2f, point.y - dot / 2f),
            Size(dot, dot),
        )
    }
    if (link.active && !frozen) {
        val phase = (((now / 1500.0 + (link.taskId.hashCode() and 255) / 255.0) % 1.0) + 1.0) % 1.0
        val point = quadratic(sx, sy, ctrlX, ctrlY, tx, ty, phase.toFloat())
        drawRect(
            accent.copy(alpha = 0.22f),
            Offset(point.x - 2.2f * scale, point.y - 2.2f * scale),
            Size(4.4f * scale, 4.4f * scale),
        )
        drawRect(accent, Offset(point.x - 1f * scale, point.y - 1f * scale), Size(2f * scale, 2f * scale))
    } else if (link.taskId !in walkingTaskIds && age <= 1_400L) {
        val t = (age / 1_400f).coerceIn(0f, 1f)
        val point = quadratic(sx, sy, ctrlX, ctrlY, tx, ty, t)
        drawFolder(point.x, point.y, scale, accent)
    } else if (link.taskId !in walkingTaskIds && age <= 2_600L) {
        val alpha = (1f - (age - 1_400f) / 1_200f).coerceIn(0f, 1f)
        drawSparkle(tx, ty - 3f * scale, scale, accent.copy(alpha = alpha))
    }
}

private fun quadratic(
    x0: Float,
    y0: Float,
    cx: Float,
    cy: Float,
    x1: Float,
    y1: Float,
    t: Float,
): Offset {
    val u = 1f - t
    return Offset(
        u * u * x0 + 2f * u * t * cx + t * t * x1,
        u * u * y0 + 2f * u * t * cy + t * t * y1,
    )
}

private fun DrawScope.drawFolder(x: Float, y: Float, scale: Float, accent: Color) {
    val w = 3.4f * scale
    val h = 2.4f * scale
    drawRect(Color(0xFF2A2119), Offset(x - w / 2f, y - h / 2f), Size(w, h))
    drawRect(Color(0xFFF6E7C8), Offset(x - w / 2f + 1f, y - h / 2f + 1f), Size(w - 2f, h - 2f))
    drawRect(accent, Offset(x - w / 2f + 1f, y - h / 2f + 1f), Size(w - 2f, h * 0.38f))
    drawRect(Color(0xFF2A2119), Offset(x - w / 2f + 1f, y - h / 2f + h * 0.38f), Size(w - 2f, 1f))
}

private fun DrawScope.drawSparkle(x: Float, y: Float, scale: Float, color: Color) {
    val dot = max(1f, 0.7f * scale)
    val arm = 2.2f * scale
    drawRect(color, Offset(x - dot / 2f, y - arm), Size(dot, arm * 2f))
    drawRect(color, Offset(x - arm, y - dot / 2f), Size(arm * 2f, dot))
    val corner = 1.1f * scale
    drawRect(color.copy(alpha = color.alpha * 0.7f), Offset(x + arm * 0.6f, y - arm * 0.9f), Size(corner, corner))
}

package top.tianyan.app.ui.bot

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal const val OFFICE_MAX_ZOOM = 3f
private const val OFFICE_ABSOLUTE_MIN_ZOOM = 0.05f

/** Zoom relative to the cover scale that shows the whole room. */
internal fun officeFitZoom(viewW: Int, viewH: Int, baseScale: Float): Float {
    if (viewW <= 0 || viewH <= 0 || baseScale <= 0f) return 1f
    val fitScale = min(
        viewW / PixelOfficeRoom.WIDTH.toFloat(),
        viewH / PixelOfficeScene.HEIGHT.toFloat(),
    )
    return (fitScale / baseScale).coerceIn(OFFICE_ABSOLUTE_MIN_ZOOM, 1f)
}

/** A request to smoothly bring one office actor into view. */
internal data class OfficeFocusRequest(val botId: String, val nonce: Long)

/** Screen camera for the pixel office: one finger pans, two fingers pinch around the midpoint. */
internal class OfficeMetrics {
    var viewW = 0
    var viewH = 0
    var baseScale = 1f
    lateinit var panX: MutableFloatState
    lateinit var panY: MutableFloatState
    lateinit var zoom: MutableFloatState

    val scale: Float get() = baseScale * zoom.floatValue
    var fitZoom by mutableFloatStateOf(1f)
        private set
    val minZoom: Float get() = fitZoom
    val isFitted: Boolean
        get() = this::zoom.isInitialized && abs(zoom.floatValue - fitZoom) <= 0.01f

    fun updateViewport(viewW: Int, viewH: Int, baseScale: Float) {
        this.viewW = viewW
        this.viewH = viewH
        this.baseScale = baseScale
        val next = officeFitZoom(viewW, viewH, baseScale)
        if (next != fitZoom) fitZoom = next
    }

    fun panBy(delta: Offset) {
        if (viewW <= 0) return
        val next = clampOfficeCamera(
            panX.floatValue + delta.x,
            panY.floatValue + delta.y,
            scale,
            viewW,
            viewH,
        )
        panX.floatValue = next.first
        panY.floatValue = next.second
    }

    fun zoomAbout(factor: Float, focal: Offset) {
        if (viewW <= 0 || factor == 1f) return
        val oldScale = scale
        if (oldScale <= 0f) return
        val nextZoom = (zoom.floatValue * factor).coerceIn(minZoom, OFFICE_MAX_ZOOM)
        if (nextZoom == zoom.floatValue) return
        val newScale = baseScale * nextZoom
        val worldX = (focal.x - panX.floatValue) / oldScale
        val worldY = (focal.y - panY.floatValue) / oldScale
        zoom.floatValue = nextZoom
        val next = clampOfficeCamera(focal.x - worldX * newScale, focal.y - worldY * newScale, newScale, viewW, viewH)
        panX.floatValue = next.first
        panY.floatValue = next.second
    }

    fun zoomBy(factor: Float) {
        if (viewW <= 0 || viewH <= 0) return
        zoomAbout(factor, Offset(viewW / 2f, viewH / 2f))
    }

    fun fitToView() {
        if (viewW <= 0 || viewH <= 0) return
        zoom.floatValue = fitZoom
        val next = clampOfficeCamera(0f, 0f, scale, viewW, viewH)
        panX.floatValue = next.first
        panY.floatValue = next.second
    }

    fun recenter() {
        if (viewW <= 0) return
        zoom.floatValue = 1f
        val home = openingOfficeCamera(viewW, viewH, baseScale)
        panX.floatValue = home.first
        panY.floatValue = home.second
    }
}

internal fun openingOfficeCamera(viewW: Int, viewH: Int, scale: Float): Pair<Float, Float> {
    val mapW = PixelOfficeRoom.WIDTH * scale
    val mapH = PixelOfficeScene.HEIGHT * scale
    return clampOfficeCamera((viewW - mapW) / 2f, (viewH - mapH) / 2f, scale, viewW, viewH)
}

internal fun clampOfficeCamera(x: Float, y: Float, scale: Float, viewW: Int, viewH: Int): Pair<Float, Float> {
    val mapW = PixelOfficeRoom.WIDTH * scale
    val mapH = PixelOfficeScene.HEIGHT * scale
    val clampedX = if (mapW <= viewW) (viewW - mapW) / 2f else x.coerceIn(viewW - mapW, 0f)
    val clampedY = if (mapH <= viewH) (viewH - mapH) / 2f else y.coerceIn(viewH - mapH, 0f)
    return clampedX to clampedY
}

@Composable
internal fun OfficeCameraFocusEffect(
    focus: OfficeFocusRequest?,
    actors: List<OfficeActor>,
    metrics: OfficeMetrics,
    viewW: Int,
    viewH: Int,
) {
    LaunchedEffect(focus?.nonce) {
        val request = focus ?: return@LaunchedEffect
        if (viewW <= 0 || viewH <= 0 || metrics.baseScale <= 0f) return@LaunchedEffect
        val actor = actors.firstOrNull { it.botId == request.botId } ?: return@LaunchedEffect
        val targetZoom = 1.7f.coerceIn(metrics.minZoom, OFFICE_MAX_ZOOM)
        val targetScale = metrics.baseScale * targetZoom
        val (targetX, targetY) = clampOfficeCamera(
            viewW / 2f - actor.x * targetScale,
            viewH / 2f - (actor.y - 10f) * targetScale,
            targetScale,
            viewW,
            viewH,
        )
        val startX = metrics.panX.floatValue
        val startY = metrics.panY.floatValue
        val startZoom = metrics.zoom.floatValue
        animate(0f, 1f, animationSpec = tween(380)) { value, _ ->
            metrics.zoom.floatValue = startZoom + (targetZoom - startZoom) * value
            metrics.panX.floatValue = startX + (targetX - startX) * value
            metrics.panY.floatValue = startY + (targetY - startY) * value
        }
    }
}

internal fun Modifier.officeGestures(metrics: OfficeMetrics): Modifier = pointerInput(metrics) {
    val slop = viewConfiguration.touchSlop
    var lastTap = 0L
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        var total = Offset.Zero
        var panning = false
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                pinching = true
                val a = pressed[0]
                val b = pressed[1]
                val prev = hypot(
                    (a.previousPosition.x - b.previousPosition.x).toDouble(),
                    (a.previousPosition.y - b.previousPosition.y).toDouble(),
                ).toFloat()
                val dist = hypot(
                    (a.position.x - b.position.x).toDouble(),
                    (a.position.y - b.position.y).toDouble(),
                ).toFloat()
                val focal = Offset((a.position.x + b.position.x) / 2f, (a.position.y + b.position.y) / 2f)
                if (prev.isFinite() && dist.isFinite() && prev > slop && dist > slop) {
                    metrics.zoomAbout(dist / prev, focal)
                }
                val prevMid = Offset(
                    (a.previousPosition.x + b.previousPosition.x) / 2f,
                    (a.previousPosition.y + b.previousPosition.y) / 2f,
                )
                if (prevMid.x.isFinite() && prevMid.y.isFinite()) metrics.panBy(focal - prevMid)
                pressed.forEach { it.consume() }
            } else if (!pinching) {
                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                total += change.positionChange()
                if (!panning && hypot(total.x.toDouble(), total.y.toDouble()) > slop) panning = true
                if (panning) {
                    metrics.panBy(change.position - change.previousPosition)
                    change.consume()
                }
            }
        }
        if (!panning && !pinching) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap in 1..299) {
                metrics.recenter()
                lastTap = 0L
            } else {
                lastTap = now
            }
        } else {
            lastTap = 0L
        }
    }
}

private val OfficeCameraFloatSaver = Saver<MutableFloatState, Float>(
    save = { it.floatValue },
    restore = { mutableFloatStateOf(it) },
)

@Composable
internal fun rememberOfficeCameraFloat(initial: Float): MutableFloatState =
    rememberSaveable(saver = OfficeCameraFloatSaver) { mutableFloatStateOf(initial) }

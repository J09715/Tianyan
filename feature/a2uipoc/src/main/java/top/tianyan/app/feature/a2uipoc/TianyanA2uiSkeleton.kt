package top.tianyan.app.feature.a2uipoc

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 天衍自带 A2UI 骨架屏（shimmer）。
 *
 * 背景：A2UI 1.0.0-alpha02 的官方 Material3 Catalog 在 Column/Row/List/Card 等容器里
 * 给「数据尚未到达」的子组件画呼吸 + 径向扫光的骨架块，但实现（MaterialA2uiDefaults /
 * ShimmerIndicator）是 `internal`，本模块的官方组件覆写（List / Image / Video）用不到，
 * 只能自己实现同一套视觉语言：
 *
 * - 底色取主题 surfaceContainerHighest，高光向白色插值，浅色/深色主题分别给不同幅度；
 * - 呼吸脉动按子项序号错峰（每项 0.15s），同一组内共享一条径向扫光；
 * - 扫光圆心对齐容器左上角（[TianyanA2uiSkeletonGroup] 的 rootCoordinates），
 *   所以同组多个骨架块看起来是被同一圈光扫过，而不是各自原地闪烁。
 *
 * 仅使用稳定的公开 Compose API（不依赖官方 internal 符号），A2UI 版本升级不受影响。
 */
@Stable
internal class TianyanA2uiSkeletonGroupState {
    /** 组容器（List/Row/Column 等）的坐标，扫光以此为圆心。 */
    var rootCoordinates: LayoutCoordinates? = null

    var sweepProgress by mutableFloatStateOf(0f)

    var highlightAlpha by mutableFloatStateOf(0f)

    var breathingPhase by mutableFloatStateOf(0f)

    var activeIndicators by mutableIntStateOf(0)
        private set

    fun onIndicatorAttached() {
        activeIndicators++
    }

    fun onIndicatorDetached() {
        activeIndicators = (activeIndicators - 1).coerceAtLeast(0)
    }

    fun reset() {
        sweepProgress = 0f
        highlightAlpha = 0f
        breathingPhase = 0f
    }
}

internal val LocalTianyanA2uiSkeletonGroup =
    compositionLocalOf<TianyanA2uiSkeletonGroupState?> { null }

/**
 * 把一组骨架块的动画驱动集中到容器上：没有骨架块时不跑动画，有任何一个挂载后
 * 三条无限动画才会启动（与官方 attach/detach 计数语义一致）。
 */
@Composable
internal fun TianyanA2uiSkeletonGroup(content: @Composable (Modifier) -> Unit) {
    val groupState = remember { TianyanA2uiSkeletonGroupState() }
    val rootModifier = remember(groupState) {
        Modifier.onPlaced { groupState.rootCoordinates = it }
    }
    val active = groupState.activeIndicators > 0
    LaunchedEffect(groupState, active) {
        if (!active) {
            groupState.reset()
            return@LaunchedEffect
        }
        groupState.runShimmerAnimations()
    }
    CompositionLocalProvider(LocalTianyanA2uiSkeletonGroup provides groupState) {
        content(rootModifier)
    }
}

/**
 * 单个骨架块。放在 [TianyanA2uiSkeletonGroup] 内时与同组共享扫光/呼吸相位；
 * 单独使用（图片、视频封面）时内部自建一份动画状态。
 */
@Composable
internal fun TianyanA2uiSkeleton(
    modifier: Modifier = Modifier,
    index: Int = 0,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val providedGroup = LocalTianyanA2uiSkeletonGroup.current
    val ownGroup = remember { TianyanA2uiSkeletonGroupState() }
    val group = providedGroup ?: ownGroup
    if (providedGroup == null) {
        LaunchedEffect(ownGroup) { ownGroup.runShimmerAnimations() }
    }

    DisposableEffect(group) {
        group.onIndicatorAttached()
        onDispose { group.onIndicatorDetached() }
    }

    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlightColor = if (isLight) {
        lerp(baseColor, Color.White, 0.55f)
    } else {
        lerp(baseColor, Color.White, 0.18f)
    }
    val transparentHighlight = highlightColor.copy(alpha = 0f)

    Box(
        modifier
            .clip(shape)
            // 坐标每次放置都要写回：滚动时节点相对容器位置变化，扫光圆心要跟着重绘才不会随列表漂移。
            .onPlaced { coordinates.value = it }
            .drawBehind {
                // 呼吸相位/扫光进度都在 draw 阶段读取：只触发重绘，不触发重组。
                val breathingAlpha = skeletonBreathingAlpha(group.breathingPhase, index)
                drawRect(color = baseColor, alpha = breathingAlpha)

                val highlightAlpha = group.highlightAlpha.coerceIn(0f, 1f)
                if (highlightAlpha <= 0f) return@drawBehind

                val root = group.rootCoordinates
                val node = coordinates.value
                var offsetX = 0f
                var offsetY = 0f
                var maxDimension = max(size.width, size.height)
                if (
                    root != null &&
                        node != null &&
                        root.isAttached &&
                        node.isAttached &&
                        root.findRootCoordinates() === node.findRootCoordinates()
                ) {
                    val offset = root.localPositionOf(node, Offset.Zero)
                    offsetX = offset.x
                    offsetY = offset.y
                    maxDimension = max(
                        maxDimension,
                        max(root.size.width.toFloat(), root.size.height.toFloat()),
                    )
                }
                val radius = skeletonHighlightRadius(group.sweepProgress, maxDimension)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(transparentHighlight, highlightColor, transparentHighlight),
                        center = Offset(-offsetX, -offsetY),
                        radius = radius,
                    ),
                    alpha = highlightAlpha,
                )
            },
    )
}

/** 组内共享的径向扫光 + 高光透明度 + 呼吸相位，三条无限循环动画。 */
private suspend fun TianyanA2uiSkeletonGroupState.runShimmerAnimations() = coroutineScope {
    launch {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = SweepAnimationSpec,
        ) { value, _ -> sweepProgress = value }
    }
    launch {
        // 以 0f 起止、中间经 keyframes 打到 1f：扫光经过时才有高光。
        animate(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = HighlightAlphaAnimationSpec,
        ) { value, _ -> highlightAlpha = value }
    }
    launch {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = BreathingAnimationSpec,
        ) { value, _ -> breathingPhase = value }
    }
}

/**
 * 呼吸脉动透明度：每项按 [index] 错峰，组内看起来是依次呼吸而不是整块同时亮暗。
 * 与官方 `calculateBreathingAlpha` 同一条曲线。
 */
internal fun skeletonBreathingAlpha(breathingPhase: Float, index: Int): Float {
    val staggerSeconds = index * SHIMMER_CHILD_STAGGER_STEP_SECONDS
    val staggerPhaseOffset =
        (staggerSeconds * 1000f * 2f) / SHIMMER_BREATHING_DURATION_MILLIS.toFloat()
    val phase = ((breathingPhase + staggerPhaseOffset) % 1f + 1f) % 1f
    val midAlpha = (SHIMMER_BREATHING_MAX_ALPHA + SHIMMER_BREATHING_MIN_ALPHA) / 2f
    val amplitude = (SHIMMER_BREATHING_MAX_ALPHA - SHIMMER_BREATHING_MIN_ALPHA) / 2f
    return (midAlpha + amplitude * cos(phase * 2f * PI.toFloat())).coerceIn(
        SHIMMER_BREATHING_MIN_ALPHA,
        SHIMMER_BREATHING_MAX_ALPHA,
    )
}

/** 扫光半径：从容器左上角向外扩张，进度 1 时覆盖整块区域。 */
internal fun skeletonHighlightRadius(sweepProgress: Float, maxDimension: Float): Float =
    (sweepProgress.coerceIn(0f, 1f) * maxDimension * 2f).coerceAtLeast(1f)

private val SweepAnimationSpec: InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(
        animation = tween(
            durationMillis = SHIMMER_SWEEP_DURATION_MILLIS,
            delayMillis = SHIMMER_DELAY_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        repeatMode = RepeatMode.Restart,
    )

private val HighlightAlphaAnimationSpec: InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(
        animation = keyframes<Float> {
            durationMillis = SHIMMER_SWEEP_DURATION_MILLIS
            delayMillis = SHIMMER_DELAY_MILLIS
            0f at 0 using LinearEasing
            1f at SHIMMER_HIGHLIGHT_PEAK_MILLIS using LinearEasing
            0f at SHIMMER_SWEEP_DURATION_MILLIS using LinearEasing
        },
        repeatMode = RepeatMode.Restart,
    )

private val BreathingAnimationSpec: InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(
        animation = tween(
            durationMillis = SHIMMER_BREATHING_DURATION_MILLIS,
            easing = LinearEasing,
        ),
        repeatMode = RepeatMode.Restart,
    )

private const val SHIMMER_DELAY_MILLIS = 100
private const val SHIMMER_SWEEP_DURATION_MILLIS = 1200
private const val SHIMMER_HIGHLIGHT_PEAK_MILLIS = 720
private const val SHIMMER_BREATHING_DURATION_MILLIS = 2400
private const val SHIMMER_BREATHING_MIN_ALPHA = 0.86f
private const val SHIMMER_BREATHING_MAX_ALPHA = 1.0f
private const val SHIMMER_CHILD_STAGGER_STEP_SECONDS = 0.15f

/** 根级骨架块（surface 还没有任何组件数据时）的默认高度。 */
internal val TianyanA2uiRootSkeletonHeight = 64.dp
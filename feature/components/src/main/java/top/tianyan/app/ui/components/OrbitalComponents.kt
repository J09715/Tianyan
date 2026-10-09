package top.tianyan.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 「星轨 Orbital」共享动效与页签组件。
 *
 * 三个面板（Git 仓库驾驶舱 / RedTeam 作战指挥室 / 未来的工作台）共用同一套
 * 视觉母题：轨道滑块页签指示器 + spring 物理动效 + 等宽数字。分散在各页
 * 各写一套的后果是「每个页签条长得都像，但滑动手感都不一样」——统一到
 * components 模块后，动效规格改一处全生效。
 */

/**
 * 全局动效规格（星轨体系）。
 *
 * 数值不是拍脑袋：入场 320ms 是 Material 3 emphasized-decelerate 的推荐区间；
 * 微交互 180ms 更快，让按压反馈「跟手」而不是「迟半拍」；页签滑块用中低刚度
 * spring，位移时有轻微过冲，像有引力的小球，而不是 tween 的匀速平移。
 */
object OrbitalMotion {
    /** 页签滑块 / 状态切换：中等刚度 + 轻微阻尼，有过冲但不弹跳。 */
    val slide = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = 0.001f,
    )

    /** 微交互（按压缩放、徽章弹入）：快进快出。 */
    val micro = tween<Float>(180, easing = EaseOutCubic)

    /** 入场浮现（透明度 + 位移）：减速曲线收尾。 */
    val enter = tween<Float>(320, easing = EaseOutCubic)

    /** 列表交错入场的步进间隔：40ms × index，首屏最多 6 项参与，长列表不参与。 */
    const val STAGGER_STEP_MS = 40L

    /** 交错入场参与上限：再多就变成「等动画」而不是「看内容」。 */
    const val STAGGER_MAX_ITEMS = 6
}

/**
 * 等宽数字（tabular figures）。
 *
 * 计数、评分、diff 行数这类「会对齐比较」的数字必须等宽：比例数字的 1 和 8
 * 宽度不同，列表里上下两行数字右缘参差，一眼看去像没对齐。系统字体（Roboto/
 * MiSans）都带 tnum 特性，`fontFeatureSettings = "tnum"` 零成本启用，
 * 不需要单独打包 JetBrains Mono（那是 P3 的事）。
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * 轨道页签条：胶囊滑块在等宽页签下方滑动。
 *
 * 为什么不用 SecondaryTabRow：它的指示器是「贴底横线 + 瞬移」，切页签时指示器
 * 直接跳到目标位置，没有运动过程。星轨体系的母题是「轨道」——滑块要像沿轨道
 * 运行的星体，spring 过冲后落位。等宽布局（weight(1f)）让滑块位移可以直接按
 * `index × tabWidth` 计算，不需要 SubcomposeLayout 测量每个页签的实际宽度，
 * 溢出场景（label 过长）交给 ellipsis。
 *
 * @param specs 页签描述（label 必填；count > 0 时右侧渲染徽章胶囊）
 * @param selectedIndex 当前选中下标
 * @param onSelect 点击页签（下标）
 */
@Composable
fun OrbitalTabRow(
    specs: List<OrbitalTabSpec>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tabCount = specs.size.coerceAtLeast(1)
        // 滑块宽度 = 单页签宽度的 70%：留出左右呼吸感，视觉上「滑块在轨道里滑」
        // 而不是「整块底色在跳」。
        val tabWidthDp: Dp = maxWidth / tabCount
        val indicatorWidth = tabWidthDp * 0.7f
        val indicatorOffset by androidx.compose.animation.core.animateFloatAsState(
            targetValue = selectedIndex * tabWidthDp.value + (tabWidthDp.value - indicatorWidth.value) / 2f,
            animationSpec = OrbitalMotion.slide,
            label = "orbitalIndicatorOffset",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            // 轨道槽：整条浅色圆角容器
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(containerColor, RoundedCornerShape(percent = 50)),
            )
            // 滑块：spring 滑动的胶囊
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(indicatorOffset.toInt(), 0) }
                    .width(indicatorWidth)
                    .height(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(percent = 50),
                    ),
            )
            // 页签项
            Row(modifier = Modifier.matchParentSize()) {
                specs.forEachIndexed { index, spec ->
                    val selected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false),
                            ) { onSelect(index) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            spec.label,
                            style = MaterialTheme.typography.labelLarge
                                .copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                .tabular(),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (spec.count > 0) {
                            CountBadge(
                                count = spec.count,
                                highlighted = selected,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单个页签的描述。label 建议两字（等宽布局下三字会挤压徽章）。 */
data class OrbitalTabSpec(
    val label: String,
    /** 条数徽章：0 或负数不渲染。红队页签「有没有数据」一眼可辨。 */
    val count: Int = 0,
)

/**
 * 条数徽章胶囊。
 *
 * 页签上的数字不做成纯文本而是胶囊：选中态与未选中态都要在滑块/轨道两种
 * 底色上可读，胶囊自带底色就不依赖外层。数字用 tnum，条数变化（比如红队
 * 新写回一条漏洞）时宽度不跳。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .height(16.dp)
            .animateContentSize(animationSpec = tween(160, easing = EaseOutCubic))
            .background(container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.coerceAtMost(999).toString(),
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 呼吸状态点。
 *
 * 「干净/脏」「在线/离线」这类二态用呼吸点而不是纯色圆点：静态圆点在玻璃
 * 卡片上和装饰元素无法区分，缓慢的呼吸（1.6s 循环）让用户一眼识别出「这是
 * 状态指示」。动画无限循环但只有 alpha 一个属性，GPU 开销可忽略。
 *
 * @param pulsing true 时呼吸（活跃）；false 时静默低透明度
 */
@Composable
fun PulseDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = true,
    size: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulseDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseDotAlpha",
    )
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (pulsing) alpha else 0.5f)
            .background(color, CircleShape),
    )
}

/**
 * 交错入场：列表/卡片依次浮现。
 *
 * 用 graphicsLayer（alpha + translationY）而不是 AnimatedVisibility：后者会
 * 参与 layout，交错时后面的项会先「占位再弹出」，列表高度抖动；graphicsLayer
 * 只动绘制层，布局一次成型，滚动中的列表不会跳。
 *
 * 只给前 [OrbitalMotion.STAGGER_MAX_ITEMS] 项编排：第 7 项之后立即显示，
 * 长列表滚动到中部时新出现的 item 不再各自动画（那才是真正的卡顿来源）。
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, contentKey: Any? = null): Modifier {
    if (index >= OrbitalMotion.STAGGER_MAX_ITEMS) return this
    var entered by remember(contentKey) { mutableStateOf(false) }
    LaunchedEffect(contentKey) {
        delay(index * OrbitalMotion.STAGGER_STEP_MS)
        entered = true
    }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = OrbitalMotion.enter,
        label = "staggerEntrance",
    )
    return graphicsLayer {
        alpha = 0.35f + 0.65f * progress
        translationY = (1f - progress) * 14.dp.toPx()
    }
}

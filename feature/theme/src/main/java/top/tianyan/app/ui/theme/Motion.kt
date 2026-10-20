package top.tianyan.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp

/**
 * 「天衍 · Tianyan」动效令牌（Design Tokens · 时间层）。
 *
 * ## 为什么需要这个文件
 *
 * 引入前全仓的动效参数是各写各的：`tween(220)` 4 次、`tween(180)` 3 次、
 * `spring(1f, 1000f, 0.001f)` 6 次、`spring(0.7f, 250f, 0.001f)` 3 次，
 * 另有 800/850/900/1200/2800ms 这类明显是「调到看着顺眼为止」的值。
 *
 * 问题不在于单个值好不好看，而在于**同一类交互在不同屏里的节奏不同**：
 * 点一个开关，在设置页是 180ms、在工具中心是 220ms；侧栏滑出在一处是
 * `spring(0.7f, 250f)`、另一处是 `Spring.StiffnessMediumLow`。用户不会
 * 说出哪里不对，只会觉得「这个 app 有点飘」。
 *
 * ## 三档时间尺度
 *
 * - [Motion.fast] 150ms —— 即时反馈：按下态、开关、勾选、涟漪。
 *   必须快到「感觉不到延迟」，超过 200ms 就会显得迟钝。
 * - [Motion.medium] 250ms —— 状态过渡：展开/折叠、内容切换、颜色渐变。
 * - [Motion.slow] 350ms —— 空间位移：页面进出、侧栏、底部弹层。
 *
 * ## 缓动曲线
 *
 * M3 的三条标准曲线：进入用减速（快起慢停）、退出用加速（慢起快走）、
 * 双向用标准曲线。用错方向的典型症状是「关掉的动画比打开的还慢」。
 */
object Motion {

    // ==================== 时长（毫秒） ====================

    /** 150ms — 即时反馈。 */
    const val fastMillis: Int = 150

    /** 250ms — 状态过渡。 */
    const val mediumMillis: Int = 250

    /** 350ms — 空间位移。 */
    const val slowMillis: Int = 350

    /** 600ms — 强调性的一次性动效（如完成庆祝），慎用。 */
    const val emphasisMillis: Int = 600

    // ==================== 缓动曲线 ====================

    /** 进入：快起慢停。 */
    val enterEasing: Easing = LinearOutSlowInEasing

    /** 退出：慢起快走。 */
    val exitEasing: Easing = FastOutLinearInEasing

    /** 双向：标准曲线，本仓既有的 `FastOutSlowInEasing` 用法统一收敛到这里。 */
    val standardEasing: Easing = FastOutSlowInEasing

    /** 匀速：仅用于进度条与旋转指示，不要用于位移。 */
    val linearEasing: Easing = LinearEasing

    /**
     * M3 Expressive 的强调曲线：起步略带回弹再收住。
     * 用于「刚出现、需要被注意到」的元素（新消息、任务完成徽章）。
     */
    val expressiveEasing: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    // ==================== 动画 spec ====================

    /** 即时反馈用的 tween。 */
    fun <T> fast(): FiniteAnimationSpec<T> = tween(fastMillis, easing = standardEasing)

    /** 状态过渡用的 tween。 */
    fun <T> medium(): FiniteAnimationSpec<T> = tween(mediumMillis, easing = standardEasing)

    /** 空间位移用的 tween（进入）。 */
    fun <T> slowEnter(): FiniteAnimationSpec<T> = tween(slowMillis, easing = enterEasing)

    /** 空间位移用的 tween（退出）。 */
    fun <T> slowExit(): FiniteAnimationSpec<T> = tween(slowMillis, easing = exitEasing)

    // ==================== 弹簧 ====================

    /**
     * 轻弹：用于手指跟随类交互（拖拽松手回位）。
     * 阻尼 0.7 略带一点过冲，是「有弹性但不晃」的常用档位。
     */
    fun <T> snappySpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

    /**
     * 稳弹：用于容器尺寸/位置变化。
     * 无过冲（damping 1.0），避免内容在动画中被裁切。
     */
    fun <T> gentleSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /**
     * 强调弹：出现类元素，允许轻微过冲。
     */
    fun <T> bouncySpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)

    // ==================== 常用组合（类型固定，便于直接传参） ====================

    /** 颜色过渡（开关、图标着色）。 */
    val colorSpec: FiniteAnimationSpec<androidx.compose.ui.graphics.Color> = fast()

    /** 尺寸过渡（卡片展开）。 */
    val sizeSpec: FiniteAnimationSpec<IntSize> = gentleSpring()

    /** 位移过渡（侧栏、指示器）。 */
    val offsetSpec: FiniteAnimationSpec<IntOffset> = snappySpring()

    /** 透明度过渡。 */
    val alphaSpec: FiniteAnimationSpec<Float> = medium()

    /** 圆角过渡（按压态形变）。 */
    val cornerSpec: FiniteAnimationSpec<Dp> = fast()
}

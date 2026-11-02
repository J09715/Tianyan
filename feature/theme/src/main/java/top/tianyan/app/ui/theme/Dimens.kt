package top.tianyan.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「天衍 · Tianyan」尺度令牌（Design Tokens · 尺度层）。
 *
 * ## 为什么需要这个文件
 *
 * 在引入本文件之前，全仓 `feature` 各模块里有 3644 处硬编码 dp。它们的分布说明问题不在
 * 「数值不对」而在「没有栅格」：8dp 出现 663 次、10dp 484 次、6dp 438 次、4dp 440 次、
 * 12dp 404 次，另有 14dp 213 次、18dp 104 次、15dp 40 次、7dp 46 次这类「看着差不多」
 * 的中间值。同一个视觉意图在不同屏里落在 8/10/12 三个不同值上，拼在一起就是歪的。
 *
 * 本文件的职责是把这些意图收成有限的几档。**它不追求穷举**——真正需要精细控制的
 * 地方（如图标光学对齐）仍然可以写裸 dp，但那种地方应该少到能数清。
 *
 * ## 使用约定
 *
 * - 间距只允许从 [Spacing] 取六档。写 `16.dp` 的地方改成 `Spacing.md`。
 * - 圆角只允许从 [Radius] 取。它与 `TianyanShapes`（M3 Shapes）同源，
 *   组件内部走 Shapes，组件外部的局部圆角走 [Radius]。
 */
object Spacing {
    /** 4dp — 图标与文字之间、标签内部。 */
    val xs: Dp = 4.dp

    /** 8dp — 紧邻元素的间距，如标题与副标题。 */
    val sm: Dp = 8.dp

    /** 12dp — 卡片内元素间距。 */
    val md: Dp = 12.dp

    /** 16dp — 卡片之间、屏幕左右边距。 */
    val lg: Dp = 16.dp

    /** 24dp — 区块之间。 */
    val xl: Dp = 24.dp

    /** 32dp — 大区块之间、页面顶部留白。 */
    val xxl: Dp = 32.dp
}

/**
 * 圆角令牌。
 *
 * 与 [TianyanShapes] 的 M3 Shapes 保持同源：`small=12` / `medium=16` / `large=24`
 * 一一对应，新增的 [xs] 与 [xl] 补齐小控件与浮层两端。
 */
object Radius {
    /** 8dp — 小标签、徽章。 */
    val xs: Dp = 8.dp

    /** 12dp — 输入框、小按钮。 */
    val sm: Dp = 12.dp

    /** 16dp — 标准卡片。 */
    val md: Dp = 16.dp

    /** 24dp — 大卡片、底部弹层。 */
    val lg: Dp = 24.dp

    /** 32dp — 全屏浮层、对话框。 */
    val xl: Dp = 32.dp

    val xsShape = RoundedCornerShape(xs)
    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
}

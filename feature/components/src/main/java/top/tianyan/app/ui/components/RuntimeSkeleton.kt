// language: Kotlin, module: feature/components, ui: Compose Material 3
package top.tianyan.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R
import top.tianyan.app.ui.theme.Motion
import top.tianyan.app.ui.theme.Radius
import top.tianyan.app.ui.theme.Spacing

/**
 * 骨架屏（Skeleton）单一入口。
 *
 * ## 这个文件解决什么
 *
 * 引入前全仓有 2 处各自私有的 shimmer 实现（`WorkspaceScreen` / `ChatMessageList`），
 * 高光速度、颜色、圆角都不一样，切换页面时能看出「两种灰在呼吸」。这里把扫光做成唯一
 * 实现，其余屏只描述**形状**，不描述**动画**。
 *
 * ## 用法
 *
 * 只在**首帧数据未到**时用骨架；已有数据后的增量刷新用进度条或行内 spinner，不要
 * 把内容整块换成骨架——那会让已有信息闪一下消失。
 *
 * ```kotlin
 * if (state.isLoading && state.items.isEmpty()) {
 *     SkeletonList(itemCount = 6)
 * } else {
 *     RealList(state.items)
 * }
 * ```
 *
 * 骨架布局必须与真实内容**同高同位**：骨架的头像 40dp，真实头像也得 40dp，否则数据到达
 * 的瞬间会跳一下。下面的预设都按对应真实组件取值，改动时两边一起改。
 *
 * ## 无障碍
 *
 * 骨架整体带 `contentDescription`，TalkBack 会读「正在载入」而不是把几十个空块逐个念一遍。
 * 视觉上不承载信息，所以子块不加语义。
 */
private const val SKELETON_SWEEP_MILLIS = 1400

/**
 * 扫光画笔：让本 Modifier 所在的矩形呈现「灰底 + 一道斜向高光慢慢扫过」。
 *
 * 高光位置在**绘制阶段**读动画状态，所以扫光不触发重组，只触发重绘——一屏十几块骨架
 * 同时呼吸也不会掉帧。
 */
@Composable
fun Modifier.skeletonShimmer(
    shape: Shape = Radius.smShape,
    baseColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
): Modifier {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_SWEEP_MILLIS, easing = Motion.linearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonShimmerProgress",
    )
    return this
        .clip(shape)
        .drawWithCache {
            val band = size.width.coerceAtLeast(1f) * 1.2f
            val travel = size.width + band * 2f
            val height = size.height.coerceAtLeast(1f)
            onDrawBehind {
                val start = -band + travel * progress.value
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(baseColor, highlightColor, baseColor),
                        start = Offset(start, 0f),
                        end = Offset(start + band, height),
                    ),
                )
            }
        }
}

/** 单个骨架块。所有预设都由它拼出来，需要新形状时先组合再考虑加新函数。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = Spacing.md,
    shape: Shape = Radius.xsShape,
) {
    Box(
        modifier = modifier
            .height(height)
            .skeletonShimmer(shape = shape),
    )
}

/** 一行文本占位。[widthFraction] 是相对父宽的百分比，用来做参差的段落。 */
@Composable
fun SkeletonTextLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 12.dp,
) {
    SkeletonBlock(
        modifier = modifier.fillMaxWidth(widthFraction.coerceIn(0.05f, 1f)),
        height = height,
    )
}

/** 圆形占位（头像、图标位）。 */
@Composable
fun SkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .skeletonShimmer(shape = androidx.compose.foundation.shape.CircleShape),
    )
}

/**
 * 卡片占位，高度按真实卡片常见值给。
 *
 * [withHeader] 时在块内画两条**不透明**的深色条做层次——注意这里刻意不再嵌一层
 * shimmer：两层独立扫光叠在一起会互相干扰，看起来像脏了。层次靠明度差表达，扫光只留一层。
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    withHeader: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .skeletonShimmer(shape = Radius.mdShape),
    ) {
        if (withHeader) {
            val stripe = MaterialTheme.colorScheme.surfaceContainerHighest
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(12.dp)
                        .background(stripe, Radius.xsShape),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .background(stripe, Radius.xsShape),
                )
            }
        }
    }
}

/** 列表行占位：头像 + 两行文字 + 可选尾部操作。对应 `RuntimeCard` 里的标准行。 */
@Composable
fun SkeletonListRow(
    modifier: Modifier = Modifier,
    hasAvatar: Boolean = true,
    hasTrailing: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasAvatar) SkeletonCircle(size = 40.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SkeletonTextLine(widthFraction = 0.55f)
            SkeletonTextLine(widthFraction = 0.85f, height = 10.dp)
        }
        if (hasTrailing) SkeletonBlock(modifier = Modifier.width(48.dp), height = 20.dp)
    }
}

/** 列表骨架。[itemCount] 取首屏可见行数，多于此值没有意义（滚不到）。 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
    hasAvatar: Boolean = true,
    contentSpacing: Dp = Spacing.sm,
) {
    val label = stringResource(R.string.components_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        repeat(itemCount) { index ->
            SkeletonListRow(
                hasAvatar = hasAvatar,
                hasTrailing = index % 3 == 0,
            )
        }
    }
}

/** 卡片流骨架，用于工具箱、模型列表这类「一格一卡」的屏。[columns] 与真实栅格保持一致。 */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    itemCount: Int = 4,
    columns: Int = 2,
    tileHeight: Dp = 108.dp,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val label = stringResource(R.string.components_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        var remaining = itemCount
        while (remaining > 0) {
            val inRow = minOf(safeColumns, remaining)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                repeat(inRow) {
                    SkeletonCard(modifier = Modifier.weight(1f), height = tileHeight)
                }
                // 行不满时补空位，避免最后一行被拉伸成整宽
                repeat(safeColumns - inRow) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
            remaining -= inRow
        }
    }
}

/** 单条聊天气泡占位。左侧是助手、右侧是用户，宽度错落才像真实对话。 */
@Composable
fun SkeletonChatBubble(
    modifier: Modifier = Modifier,
    fromUser: Boolean = false,
    lines: Int = 2,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (fromUser) 0.62f else 0.78f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            repeat(lines) { index ->
                SkeletonTextLine(
                    widthFraction = if (index == lines - 1) 0.6f else 1f,
                    height = 14.dp,
                )
            }
        }
    }
}

/** 会话流骨架。进聊天页首帧用它顶住布局，比转圈更少视觉噪音。 */
@Composable
fun SkeletonChatThread(
    modifier: Modifier = Modifier,
    messageCount: Int = 5,
) {
    val label = stringResource(R.string.components_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        repeat(messageCount) { index ->
            SkeletonChatBubble(
                fromUser = index % 2 == 1,
                lines = if (index % 3 == 0) 3 else 2,
            )
        }
    }
}

/** 详情页骨架：大标题 + 若干段 + 一块内容。用于工具详情、模型详情这类层级屏。 */
@Composable
fun SkeletonDetail(
    modifier: Modifier = Modifier,
    paragraphCount: Int = 3,
) {
    val label = stringResource(R.string.components_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SkeletonTextLine(widthFraction = 0.5f, height = 22.dp)
        SkeletonTextLine(widthFraction = 0.28f, height = 12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            repeat(paragraphCount) { index ->
                SkeletonTextLine(widthFraction = if (index == paragraphCount - 1) 0.7f else 1f)
            }
        }
        SkeletonCard(height = 120.dp, withHeader = true)
        SkeletonCard(height = 72.dp)
    }
}

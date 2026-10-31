package top.tianyan.app.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 天衍 · Git 空状态矢量插图
 *
 * 设计语言：unDraw 极简扁平风格
 *
 * - 淡色圆形背景
 * - Git 分支拓扑曲线
 * - 三个提交节点
 * - 独立装饰星芒
 * - Material3 动态主题色
 * - 轻微入场及星芒悬浮动画
 *
 * 纯 Canvas 绘制，无图片资源依赖。
 * 装饰性元素不进入无障碍树。
 */
@Composable
fun TianyanGitEmptyIllustration(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    val backgroundColor = colors.primaryContainer.copy(alpha = 0.46f)
    val branchColor = colors.onSurfaceVariant.copy(alpha = 0.30f)
    val primaryNode = colors.primary
    val centerNode = colors.onSurfaceVariant
    val endNode = lerp(colors.tertiary, colors.onSurfaceVariant, 0.28f)
    val sparkleColor = lerp(colors.tertiary, colors.onSurfaceVariant, 0.38f)
    val smallNode = colors.onSurfaceVariant.copy(alpha = 0.55f)

    // 主分支：左侧提交节点延伸至右侧
    val mainBranch = remember {
        Path().apply {
            moveTo(60f, 134f)

            cubicTo(
                80f, 132f,
                99f, 129f,
                113f, 129f,
            )

            cubicTo(
                139f, 118f,
                158f, 129f,
                181f, 113f,
            )

            cubicTo(
                194f, 105f,
                203f, 102f,
                218f, 102f,
            )
        }
    }

    // 向上延伸的分支
    val upperBranch = remember {
        Path().apply {
            moveTo(113f, 129f)

            cubicTo(
                117f, 105f,
                132f, 82f,
                166f, 85f,
            )
        }
    }

    // 四角星芒
    val sparkle = remember {
        Path().apply {
            moveTo(214f, 31f)

            cubicTo(216f, 40f, 220f, 44f, 227f, 46f)
            cubicTo(220f, 49f, 216f, 53f, 214f, 61f)
            cubicTo(212f, 53f, 208f, 49f, 201f, 46f)
            cubicTo(209f, 43f, 212f, 39f, 214f, 31f)

            close()
        }
    }

    // 入场动画
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 600,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // 星芒轻微上下浮动
    val transition = rememberInfiniteTransition(
        label = "gitEmptyFloat",
    )

    val floating by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2800,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sparkleFloating",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(194.dp)
            .clearAndSetSemantics {}
            .graphicsLayer {
                val progress = appear.value

                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
            },
    ) {
        // 统一设计坐标系 310 × 194
        val designWidth = 310f
        val designHeight = 194f

        val scaleFactor = min(
            size.width / designWidth,
            size.height / designHeight,
        )

        withTransform({
            translate(
                left = (size.width - designWidth * scaleFactor) / 2f,
                top = (size.height - designHeight * scaleFactor) / 2f,
            )

            scale(
                scaleX = scaleFactor,
                scaleY = scaleFactor,
                pivot = Offset.Zero,
            )
        }) {

            // ─────────────────────────
            // 1. 圆形氛围背景
            // ─────────────────────────

            drawCircle(
                color = backgroundColor,
                radius = 76f,
                center = Offset(119f, 114f),
            )

            // ─────────────────────────
            // 2. Git 分支曲线
            // ─────────────────────────

            val branchStroke = Stroke(
                width = 3.6f,
                cap = StrokeCap.Round,
            )

            drawPath(
                path = mainBranch,
                color = branchColor,
                style = branchStroke,
            )

            drawPath(
                path = upperBranch,
                color = branchColor,
                style = branchStroke,
            )

            // ─────────────────────────
            // 3. Git 提交节点
            // ─────────────────────────

            // 左侧起始提交
            drawCircle(
                color = primaryNode,
                radius = 8f,
                center = Offset(60f, 134f),
            )

            // 中间分叉提交
            drawCircle(
                color = centerNode,
                radius = 8f,
                center = Offset(113f, 129f),
            )

            // 上方分支末端提交
            drawCircle(
                color = endNode,
                radius = 8f,
                center = Offset(166f, 85f),
            )

            // ─────────────────────────
            // 4. 小型点缀
            // ─────────────────────────

            drawCircle(
                color = smallNode,
                radius = 4.3f,
                center = Offset(78f, 165f),
            )

            // ─────────────────────────
            // 5. 四角星芒
            // ─────────────────────────

            val floatOffset = (floating - 0.5f) * 4f

            withTransform({
                translate(top = floatOffset)
            }) {
                drawPath(
                    path = sparkle,
                    color = sparkleColor,
                )
            }
        }
    }
}

@Preview(name = "浅色", locale = "zh", showBackground = true)
@Composable
private fun TianyanGitEmptyIllustrationPreview() {
    RuntimePreview { TianyanGitEmptyIllustration() }
}

@Preview(
    name = "深色",
    locale = "zh",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TianyanGitEmptyIllustrationNightPreview() {
    RuntimePreview { TianyanGitEmptyIllustration() }
}

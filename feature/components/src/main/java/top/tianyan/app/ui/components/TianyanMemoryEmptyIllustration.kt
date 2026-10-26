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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * 天衍 · 记忆空状态矢量插图
 *
 * 视觉元素：
 * - 柔和圆形背景
 * - 悬浮圆角记忆容器
 * - 双半球大脑符号
 * - 四角星芒、小圆点
 *
 * 特性：
 * - 纯 Compose Canvas
 * - Material3 动态主题适配
 * - 入场渐显与轻微悬浮
 * - 等比缩放，不拉伸
 * - 不进入无障碍树
 */
@Composable
fun TianyanMemoryEmptyIllustration(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    val backgroundColor = colors.primaryContainer.copy(alpha = 0.42f)

    val tileColor = lerp(
        colors.primaryContainer,
        colors.secondaryContainer,
        0.35f,
    ).copy(alpha = 0.85f)

    val brainColor = colors.onSurface.copy(alpha = 0.90f)
    val brainLineColor = colors.surface.copy(alpha = 0.85f)

    val sparkleColor = lerp(
        colors.tertiary,
        colors.onSurfaceVariant,
        0.38f,
    )

    val dotColor = colors.onSurfaceVariant.copy(alpha = 0.48f)

    // ─────────────────────────
    // 大脑左半球
    // ─────────────────────────

    val leftBrain = remember {
        Path().apply {
            moveTo(18f, 3f)

            cubicTo(14f, 0f, 9f, 3f, 8f, 7f)
            cubicTo(3f, 6f, 0f, 11f, 3f, 16f)
            cubicTo(0f, 20f, 3f, 25f, 8f, 26f)
            cubicTo(10f, 30f, 15f, 30f, 18f, 27f)
            cubicTo(20f, 24f, 19f, 7f, 18f, 3f)

            close()
        }
    }

    // 大脑右半球
    val rightBrain = remember {
        Path().apply {
            moveTo(22f, 3f)

            cubicTo(26f, 0f, 31f, 3f, 32f, 7f)
            cubicTo(37f, 7f, 40f, 12f, 37f, 16f)
            cubicTo(40f, 21f, 37f, 26f, 32f, 26f)
            cubicTo(30f, 30f, 25f, 30f, 22f, 27f)
            cubicTo(20f, 24f, 21f, 7f, 22f, 3f)

            close()
        }
    }

    // 左侧脑回纹路
    val leftFold = remember {
        Path().apply {
            moveTo(9f, 11f)
            cubicTo(14f, 9f, 15f, 14f, 11f, 17f)
            cubicTo(8f, 19f, 8f, 21f, 10f, 23f)
        }
    }

    // 右侧脑回纹路
    val rightFold = remember {
        Path().apply {
            moveTo(29f, 9f)
            cubicTo(25f, 10f, 25f, 14f, 30f, 16f)
            cubicTo(33f, 19f, 31f, 22f, 28f, 23f)
        }
    }

    // 四角星芒
    val sparkle = remember {
        Path().apply {
            moveTo(181f, 17f)

            cubicTo(183f, 27f, 186f, 30f, 195f, 33f)
            cubicTo(186f, 36f, 183f, 40f, 181f, 50f)
            cubicTo(179f, 40f, 176f, 36f, 167f, 33f)
            cubicTo(176f, 30f, 179f, 27f, 181f, 17f)

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

    // 轻微悬浮动画
    val transition = rememberInfiniteTransition(
        label = "memoryFloat",
    )

    val floating by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floating",
    )

    val bob = (floating - 0.5f) * 4f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clearAndSetSemantics {}
            .graphicsLayer {
                val progress = appear.value

                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
            },
    ) {
        // 基准设计尺寸：310 × 180
        val designWidth = 310f
        val designHeight = 180f

        val factor = min(
            size.width / designWidth,
            size.height / designHeight,
        )

        withTransform({
            translate(
                left = (size.width - designWidth * factor) / 2f,
                top = (size.height - designHeight * factor) / 2f,
            )

            scale(
                scaleX = factor,
                scaleY = factor,
                pivot = Offset.Zero,
            )
        }) {

            // ─────────────────────────
            // 1. 柔和圆形背景
            // ─────────────────────────

            drawCircle(
                color = backgroundColor,
                radius = 76f,
                center = Offset(119f, 92f),
            )

            // ─────────────────────────
            // 2. 记忆容器
            // ─────────────────────────

            withTransform({
                translate(top = bob)
            }) {

                drawRoundRect(
                    color = tileColor,
                    topLeft = Offset(91f, 49f),
                    size = Size(79f, 79f),
                    cornerRadius = CornerRadius(27f),
                )

                // ─────────────────────────
                // 3. 大脑图形
                // ─────────────────────────

                withTransform({
                    translate(
                        left = 112f,
                        top = 72f,
                    )
                }) {

                    // 左半球
                    drawPath(
                        path = leftBrain,
                        color = brainColor,
                    )

                    // 右半球
                    drawPath(
                        path = rightBrain,
                        color = brainColor,
                    )

                    // 中央半球分界线
                    drawLine(
                        color = brainLineColor,
                        start = Offset(20f, 5f),
                        end = Offset(20f, 26f),
                        strokeWidth = 1.4f,
                        cap = StrokeCap.Round,
                    )

                    // 左侧脑回
                    drawPath(
                        path = leftFold,
                        color = brainLineColor,
                        style = Stroke(
                            width = 2f,
                            cap = StrokeCap.Round,
                        ),
                    )

                    // 右侧脑回
                    drawPath(
                        path = rightFold,
                        color = brainLineColor,
                        style = Stroke(
                            width = 2f,
                            cap = StrokeCap.Round,
                        ),
                    )

                    // 上部辅助脑回
                    drawLine(
                        color = brainLineColor.copy(alpha = 0.75f),
                        start = Offset(13f, 7f),
                        end = Offset(11f, 10f),
                        strokeWidth = 1.8f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // ─────────────────────────
            // 4. 四角星芒
            // ─────────────────────────

            withTransform({
                translate(top = -bob * 0.65f)
            }) {
                drawPath(
                    path = sparkle,
                    color = sparkleColor,
                )
            }

            // ─────────────────────────
            // 5. 底部小圆点
            // ─────────────────────────

            drawCircle(
                color = dotColor,
                radius = 4.5f,
                center = Offset(79f, 143f),
            )
        }
    }
}

@Preview(name = "浅色", locale = "zh", showBackground = true)
@Composable
private fun TianyanMemoryEmptyIllustrationPreview() {
    RuntimePreview { TianyanMemoryEmptyIllustration() }
}

@Preview(
    name = "深色",
    locale = "zh",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TianyanMemoryEmptyIllustrationNightPreview() {
    RuntimePreview { TianyanMemoryEmptyIllustration() }
}

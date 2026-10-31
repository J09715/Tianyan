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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 天衍 · 沙箱工坊空状态插图
 *
 * 设计：
 * - unDraw 风格，极简扁平矢量
 * - 打开的沙箱容器，象征等待创建工程
 * - 悬浮文件与代码卡片
 * - 主题自适应，支持 Material You / Monet
 * - 入场渐显 + 轻微悬浮动画
 *
 * 纯 Canvas 绘制，无图片、SVG 或 Drawable 依赖。
 * 纯装饰内容，不进入无障碍树。
 * 场景绘制见 drawTianyanSandboxScene（TianyanSandboxScene.kt / TianyanSandboxCrate.kt）。
 */
@Composable
fun TianyanSandboxIllustration(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    // 入场动画
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 650,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // 悬浮动画
    val infinite = rememberInfiniteTransition(label = "sandboxFloat")

    val floatProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2800,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floating",
    )

    val bob = (floatProgress - 0.5f) * 7f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = appear.value
                    alpha = progress
                    scaleX = 0.94f + 0.06f * progress
                    scaleY = 0.94f + 0.06f * progress
                    transformOrigin = TransformOrigin(0.5f, 0.65f)
                },
        ) {
            drawTianyanSandboxScene(colors, bob)
        }
    }
}

@Preview(name = "浅色", locale = "zh", showBackground = true)
@Composable
private fun TianyanSandboxIllustrationPreview() {
    RuntimePreview { TianyanSandboxIllustration() }
}

@Preview(
    name = "深色",
    locale = "zh",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TianyanSandboxIllustrationNightPreview() {
    RuntimePreview { TianyanSandboxIllustration() }
}

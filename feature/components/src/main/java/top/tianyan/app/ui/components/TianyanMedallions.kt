@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package top.tianyan.app.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 微缩徽记·提交图谱：Git 空态（无变更/无分支/无提交/无标签）使用。
 * 几何与配色同为纯矢量 + colorScheme 取色，随 Monet / HCT 换肤。
 */
@Composable
fun TianyanGitGraphMedallion(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
    }
    val lineColor = colors.outlineVariant.copy(alpha = 0.85f)

    Box(
        modifier = modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 96.dp)
                .graphicsLayer {
                    val progress = appear.value
                    alpha = progress
                    scaleX = 0.92f + 0.08f * progress
                    scaleY = 0.92f + 0.08f * progress
                    transformOrigin = TransformOrigin(0.5f, 0.6f)
                },
        ) {
            Surface(
                modifier = Modifier.align(Alignment.Center).offset(x = (-10).dp, y = 4.dp)
                    .size(88.dp).rotate(-8f),
                shape = MaterialShapes.Puffy.toShape(),
                color = colors.primaryContainer.copy(alpha = 0.42f),
            ) {}

            Canvas(
                modifier = Modifier.align(Alignment.Center).offset(y = (-2).dp)
                    .size(width = 104.dp, height = 64.dp),
            ) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                val main = Path().apply {
                    moveTo(w * 0.08f, h * 0.78f)
                    cubicTo(w * 0.33f, h * 0.78f, w * 0.42f, h * 0.66f, w * 0.60f, h * 0.66f)
                    cubicTo(w * 0.77f, h * 0.66f, w * 0.83f, h * 0.50f, w * 0.94f, h * 0.50f)
                }
                val branch = Path().apply {
                    moveTo(w * 0.37f, h * 0.73f)
                    cubicTo(w * 0.40f, h * 0.47f, w * 0.50f, h * 0.34f, w * 0.66f, h * 0.34f)
                }
                drawPath(main, lineColor, style = stroke)
                drawPath(branch, lineColor, style = stroke)
                val dotRadius = 4.5.dp.toPx()
                drawCircle(colors.primary, dotRadius, Offset(w * 0.08f, h * 0.78f))
                drawCircle(colors.secondary, dotRadius, Offset(w * 0.37f, h * 0.73f))
                drawCircle(colors.tertiary, dotRadius, Offset(w * 0.66f, h * 0.34f))
            }

            RuntimeIcon(
                RuntimeIconName.Sparkles,
                Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 6.dp).size(16.dp),
                colors.tertiary,
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 12.dp)
                    .size(5.dp),
                shape = CircleShape,
                color = colors.secondary.copy(alpha = 0.5f),
            ) {}
        }
    }
}

/**
 * 微缩徽记·字形章：小空态通用（光晕 + 字形瓦片 + 星与微粒），传入图标按上下文取义。
 */
@Composable
fun TianyanGlyphMedallion(icon: RuntimeIconName, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 96.dp)
                .graphicsLayer {
                    val progress = appear.value
                    alpha = progress
                    scaleX = 0.92f + 0.08f * progress
                    scaleY = 0.92f + 0.08f * progress
                    transformOrigin = TransformOrigin(0.5f, 0.6f)
                },
        ) {
            Surface(
                modifier = Modifier.align(Alignment.Center).offset(x = (-8).dp, y = 4.dp)
                    .size(88.dp).rotate(-8f),
                shape = MaterialShapes.Puffy.toShape(),
                color = colors.primaryContainer.copy(alpha = 0.40f),
            ) {}
            Surface(
                modifier = Modifier.align(Alignment.Center).size(46.dp).rotate(-4f),
                shape = MaterialShapes.Cookie4Sided.toShape(),
                color = colors.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RuntimeIcon(icon, Modifier.size(24.dp), colors.onSecondaryContainer)
                }
            }
            RuntimeIcon(
                RuntimeIconName.Sparkles,
                Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 10.dp).size(16.dp),
                colors.tertiary,
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 14.dp)
                    .size(5.dp),
                shape = CircleShape,
                color = colors.tertiary.copy(alpha = 0.5f),
            ) {}
        }
    }
}

@Preview(name = "Git 徽记 · 浅色", locale = "zh", showBackground = true)
@Composable
private fun TianyanGitGraphMedallionPreview() {
    RuntimePreview { TianyanGitGraphMedallion() }
}

@Preview(name = "Git 徽记 · 深色", locale = "zh", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TianyanGitGraphMedallionNightPreview() {
    RuntimePreview { TianyanGitGraphMedallion() }
}

@Preview(name = "字形章 · 浅色", locale = "zh", showBackground = true)
@Composable
private fun TianyanGlyphMedallionPreview() {
    RuntimePreview { TianyanGlyphMedallion(RuntimeIconName.Brain) }
}

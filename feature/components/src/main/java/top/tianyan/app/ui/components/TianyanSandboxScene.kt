package top.tianyan.app.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.min

/**
 * 「沙箱工坊」插图场景：设计坐标系 320 × 220。
 * 由 TianyanSandboxIllustration 在每个动画帧内调用：背景 → 悬浮文件 → 代码卡片 → 漂浮装饰 → 沙箱箱体。
 */
internal fun DrawScope.drawTianyanSandboxScene(colors: ColorScheme, bob: Float) {
    // 设计坐标系：320 × 220
    val factor = min(size.width / 320f, size.height / 220f)

    withTransform({
        translate(
            left = (size.width - 320f * factor) / 2f,
            top = (size.height - 220f * factor) / 2f,
        )
        scale(factor, factor, Offset.Zero)
    }) {
        drawSandboxBackdrop(colors)
        drawFloatingFile(colors, bob)
        drawFloatingCodeCard(colors, bob)
        drawFloatingAccents(colors)
        drawSandboxCrate(colors)
    }
}

// ─────────────────────────
// 1. 柔和背景
// ─────────────────────────

private fun DrawScope.drawSandboxBackdrop(colors: ColorScheme) {
    val blobA = colors.primaryContainer.copy(alpha = 0.48f)
    val blobB = colors.tertiaryContainer.copy(alpha = 0.40f)

    val mainBlob = Path().apply {
        moveTo(73f, 64f)
        cubicTo(91f, 20f, 145f, 22f, 177f, 46f)
        cubicTo(209f, 70f, 224f, 28f, 264f, 43f)
        cubicTo(305f, 59f, 290f, 114f, 270f, 135f)
        cubicTo(246f, 159f, 187f, 149f, 146f, 162f)
        cubicTo(100f, 178f, 44f, 130f, 73f, 64f)
        close()
    }

    drawPath(mainBlob, blobA)

    val pinkBlob = Path().apply {
        moveTo(30f, 146f)
        cubicTo(54f, 128f, 77f, 146f, 105f, 151f)
        cubicTo(153f, 160f, 219f, 131f, 258f, 132f)
        cubicTo(292f, 133f, 311f, 165f, 290f, 191f)
        cubicTo(255f, 211f, 100f, 208f, 44f, 197f)
        cubicTo(20f, 190f, 12f, 163f, 30f, 146f)
        close()
    }

    drawPath(pinkBlob, blobB)

    // 底部投影
    drawOval(
        color = colors.primary.copy(alpha = 0.09f),
        topLeft = Offset(72f, 193f),
        size = Size(195f, 15f),
    )
}

// ─────────────────────────
// 2. 悬浮文件
// ─────────────────────────

private fun DrawScope.drawFloatingFile(colors: ColorScheme, bob: Float) {
    val primary = colors.primary
    val ink = colors.onSurfaceVariant
    val cardColor = lerp(colors.surface, colors.surfaceContainerHigh, 0.55f)

    withTransform({
        rotate(
            degrees = -13f,
            pivot = Offset(99f, 80f),
        )
        translate(0f, -bob)
    }) {
        drawRoundRect(
            color = primary.copy(alpha = 0.07f),
            topLeft = Offset(72f, 42f),
            size = Size(72f, 88f),
            cornerRadius = CornerRadius(13f),
        )

        drawRoundRect(
            color = cardColor,
            topLeft = Offset(68f, 37f),
            size = Size(72f, 88f),
            cornerRadius = CornerRadius(13f),
        )

        // 文件内容
        drawRoundRect(
            color = ink.copy(alpha = 0.42f),
            topLeft = Offset(83f, 61f),
            size = Size(40f, 5f),
            cornerRadius = CornerRadius(3f),
        )

        drawRoundRect(
            color = ink.copy(alpha = 0.30f),
            topLeft = Offset(83f, 76f),
            size = Size(31f, 5f),
            cornerRadius = CornerRadius(3f),
        )

        drawRoundRect(
            color = ink.copy(alpha = 0.22f),
            topLeft = Offset(83f, 91f),
            size = Size(23f, 5f),
            cornerRadius = CornerRadius(3f),
        )
    }
}

// ─────────────────────────
// 3. 悬浮代码卡片
// ─────────────────────────

private fun DrawScope.drawFloatingCodeCard(colors: ColorScheme, bob: Float) {
    val primary = colors.primary

    withTransform({
        rotate(
            degrees = 13f,
            pivot = Offset(231f, 78f),
        )
        translate(0f, bob * 0.7f)
    }) {
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(
                    colors.primaryContainer,
                    colors.secondaryContainer,
                ),
                start = Offset(195f, 42f),
                end = Offset(267f, 112f),
            ),
            topLeft = Offset(198f, 42f),
            size = Size(68f, 70f),
            cornerRadius = CornerRadius(17f),
        )

        // 左尖括号 <
        drawLine(
            color = primary,
            start = Offset(226f, 70f),
            end = Offset(216f, 79f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = primary,
            start = Offset(216f, 79f),
            end = Offset(226f, 88f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )

        // 右尖括号 >
        drawLine(
            color = primary,
            start = Offset(239f, 70f),
            end = Offset(249f, 79f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = primary,
            start = Offset(249f, 79f),
            end = Offset(239f, 88f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )

        // 中间斜杠
        drawLine(
            color = primary.copy(alpha = 0.7f),
            start = Offset(237f, 66f),
            end = Offset(229f, 92f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
    }
}

// ─────────────────────────
// 4. 漂浮装饰
// ─────────────────────────

private fun DrawScope.drawFloatingAccents(colors: ColorScheme) {
    val primary = colors.primary
    val accent = colors.tertiary

    // 星芒
    val sparkle = Path().apply {
        moveTo(174f, 29f)
        quadraticTo(177f, 44f, 188f, 47f)
        quadraticTo(177f, 51f, 174f, 65f)
        quadraticTo(171f, 51f, 160f, 47f)
        quadraticTo(171f, 44f, 174f, 29f)
        close()
    }
    drawPath(sparkle, accent.copy(alpha = 0.85f))

    // 小圆点
    drawCircle(
        color = colors.tertiary.copy(alpha = 0.30f),
        radius = 6f,
        center = Offset(55f, 125f),
    )

    drawCircle(
        color = primary.copy(alpha = 0.18f),
        radius = 4f,
        center = Offset(280f, 91f),
    )

    drawCircle(
        color = accent.copy(alpha = 0.22f),
        radius = 3f,
        center = Offset(155f, 24f),
    )

    // 文件到沙箱的虚线轨迹
    drawArc(
        color = primary.copy(alpha = 0.5f),
        startAngle = 258f,
        sweepAngle = 88f,
        useCenter = false,
        topLeft = Offset(129f, 73f),
        size = Size(90f, 79f),
        style = Stroke(
            width = 2.2f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(5f, 8f),
            ),
        ),
    )
}

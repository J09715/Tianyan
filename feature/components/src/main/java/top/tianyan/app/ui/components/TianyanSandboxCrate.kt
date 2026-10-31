package top.tianyan.app.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp

/**
 * 「沙箱工坊」插图的沙箱箱体（设计坐标系 320 × 220）：
 * 正面/右侧面 → 内部开口 → 打开的盒盖 → 容器上的终端标识。
 */
internal fun DrawScope.drawSandboxCrate(colors: ColorScheme) {
    val frontColor = lerp(colors.surface, colors.primaryContainer, 0.75f)
    val sideColor = lerp(colors.surface, colors.primary, 0.30f)
    val flapColor = lerp(colors.surface, colors.primaryContainer, 0.86f)
    val flapHighlight = lerp(colors.surface, colors.secondaryContainer, 0.78f)
    val cavityColor = lerp(colors.primary, colors.onSurface, 0.32f)

    // 正面
    val front = polygon(
        Offset(111f, 133f),
        Offset(209f, 145f),
        Offset(209f, 199f),
        Offset(111f, 186f),
    )

    drawPath(
        brush = Brush.linearGradient(
            colors = listOf(
                frontColor,
                flapHighlight,
            ),
            start = Offset(115f, 132f),
            end = Offset(211f, 202f),
        ),
        path = front,
    )

    // 右侧面
    drawPath(
        path = polygon(
            Offset(209f, 145f),
            Offset(251f, 128f),
            Offset(251f, 184f),
            Offset(209f, 199f),
        ),
        brush = Brush.linearGradient(
            listOf(sideColor, frontColor),
            start = Offset(209f, 145f),
            end = Offset(253f, 198f),
        ),
    )

    // 内部开口
    drawPath(
        path = polygon(
            Offset(93f, 122f),
            Offset(200f, 105f),
            Offset(263f, 123f),
            Offset(209f, 145f),
        ),
        brush = Brush.linearGradient(
            colors = listOf(
                colors.primary.copy(alpha = 0.62f),
                cavityColor.copy(alpha = 0.94f),
            ),
            start = Offset(96f, 115f),
            end = Offset(245f, 145f),
        ),
    )

    // 内部折面
    drawPath(
        path = polygon(
            Offset(200f, 105f),
            Offset(263f, 123f),
            Offset(209f, 145f),
            Offset(200f, 134f),
        ),
        color = colors.onPrimaryContainer.copy(alpha = 0.15f),
    )

    // ─────────────────────────
    // 打开的盒盖
    // ─────────────────────────

    // 左边展开的盖板
    drawPath(
        path = polygon(
            Offset(93f, 122f),
            Offset(209f, 145f),
            Offset(174f, 175f),
            Offset(66f, 153f),
        ),
        brush = Brush.linearGradient(
            colors = listOf(
                flapHighlight,
                flapColor,
            ),
            start = Offset(80f, 116f),
            end = Offset(190f, 172f),
        ),
    )

    // 右边展开的盖板
    drawPath(
        path = polygon(
            Offset(209f, 145f),
            Offset(263f, 123f),
            Offset(291f, 154f),
            Offset(239f, 173f),
        ),
        brush = Brush.linearGradient(
            colors = listOf(
                flapColor,
                sideColor,
            ),
            start = Offset(207f, 133f),
            end = Offset(282f, 177f),
        ),
    )

    // ─────────────────────────
    // 容器上的终端标识
    // ─────────────────────────

    val symbolColor = colors.onPrimaryContainer.copy(
        alpha = 0.80f,
    )

    // >
    drawLine(
        color = symbolColor,
        start = Offset(137f, 164f),
        end = Offset(149f, 175f),
        strokeWidth = 4.5f,
        cap = StrokeCap.Round,
    )

    drawLine(
        color = symbolColor,
        start = Offset(149f, 175f),
        end = Offset(137f, 186f),
        strokeWidth = 4.5f,
        cap = StrokeCap.Round,
    )

    // _
    drawLine(
        color = symbolColor,
        start = Offset(157f, 188f),
        end = Offset(174f, 190f),
        strokeWidth = 4.5f,
        cap = StrokeCap.Round,
    )

    // 右边的小装饰
    drawLine(
        color = colors.primary.copy(alpha = 0.50f),
        start = Offset(282f, 116f),
        end = Offset(290f, 107f),
        strokeWidth = 3f,
        cap = StrokeCap.Round,
    )
}

/**
 * 创建封闭四边形路径（Offset 为 value class，不可作 vararg，固定四点入参）。
 */
private fun polygon(p1: Offset, p2: Offset, p3: Offset, p4: Offset): Path {
    return Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
    }
}

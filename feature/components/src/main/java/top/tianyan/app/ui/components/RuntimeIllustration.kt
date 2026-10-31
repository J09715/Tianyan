package top.tianyan.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

enum class RuntimeIllustrationScene { Welcome, Appearance, Settings, Notice }

/** Original vector scenes. Every fill comes from the current theme; no remote image is needed. */
@Composable
fun RuntimeIllustration(scene: RuntimeIllustrationScene, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(180.dp).clearAndSetSemantics {}) {
        val factor = minOf(size.width / 320f, size.height / 200f)
        withTransform({
            translate((size.width - 320f * factor) / 2, (size.height - 200f * factor) / 2)
            scale(factor, factor, Offset.Zero)
        }) {
            drawOval(colors.primaryContainer, Offset(58f, 19f), Size(232f, 164f))
            drawCircle(colors.tertiaryContainer, 19f, Offset(281f, 30f))
            drawCircle(colors.secondaryContainer, 9f, Offset(35f, 135f))
            drawLine(colors.outlineVariant, Offset(24f, 182f), Offset(301f, 182f), 2f)
            drawPerson(colors)
            when (scene) {
                RuntimeIllustrationScene.Welcome -> drawTerminal(colors)
                RuntimeIllustrationScene.Appearance -> drawPalette(colors)
                RuntimeIllustrationScene.Settings -> drawSettings(colors)
                RuntimeIllustrationScene.Notice -> drawNotice(colors)
            }
        }
    }
}

private fun DrawScope.panel(colors: ColorScheme) {
    drawRoundRect(colors.surfaceContainerHighest, Offset(139f, 38f), Size(143f, 125f), CornerRadius(20f))
}

private fun DrawScope.drawTerminal(c: ColorScheme) {
    panel(c)
    repeat(3) { drawCircle(c.outline, 2.5f, Offset(157f + 10f * it, 54f)) }
    drawLine(c.primary, Offset(158f, 78f), Offset(173f, 91f), 4f, StrokeCap.Round)
    drawLine(c.primary, Offset(173f, 91f), Offset(158f, 104f), 4f, StrokeCap.Round)
    drawLine(c.primary, Offset(183f, 104f), Offset(201f, 104f), 4f, StrokeCap.Round)
    drawLine(c.outlineVariant, Offset(158f, 126f), Offset(258f, 126f), 5f, StrokeCap.Round)
    drawLine(c.outlineVariant, Offset(158f, 140f), Offset(228f, 140f), 5f, StrokeCap.Round)
    drawCircle(c.tertiary, 18f, Offset(274f, 153f))
    drawLine(c.onTertiary, Offset(274f, 144f), Offset(274f, 162f), 3f, StrokeCap.Round)
    drawLine(c.onTertiary, Offset(265f, 153f), Offset(283f, 153f), 3f, StrokeCap.Round)
}

private fun DrawScope.drawPalette(c: ColorScheme) {
    panel(c)
    val swatches = listOf(c.primary, c.secondary, c.tertiary)
    swatches.forEachIndexed { index, color ->
        drawRoundRect(color, Offset(155f + 37f * index, 56f), Size(29f, 61f), CornerRadius(14f))
        drawCircle(c.surface, 3f, Offset(169.5f + 37f * index, 103f))
    }
    drawLine(c.outlineVariant, Offset(158f, 137f), Offset(259f, 137f), 4f, StrokeCap.Round)
    drawCircle(c.primary, 7f, Offset(222f, 137f))
}

private fun DrawScope.drawSettings(c: ColorScheme) {
    panel(c)
    repeat(3) { index ->
        val y = 67f + index * 33f
        drawCircle(c.secondaryContainer, 9f, Offset(161f, y))
        drawLine(c.onSecondaryContainer, Offset(158f, y), Offset(164f, y), 2f, StrokeCap.Round)
        drawLine(c.outlineVariant, Offset(180f, y), Offset(226f, y), 5f, StrokeCap.Round)
        drawRoundRect(if (index == 1) c.surfaceContainerLow else c.primary,
            Offset(242f, y - 7f), Size(25f, 14f), CornerRadius(7f))
        drawCircle(if (index == 1) c.outline else c.onPrimary, 5f,
            Offset(if (index == 1) 249f else 260f, y))
    }
}

private fun DrawScope.drawNotice(c: ColorScheme) {
    val shield = Path().apply {
        moveTo(210f, 36f); lineTo(271f, 61f); lineTo(264f, 115f)
        cubicTo(258f, 141f, 232f, 158f, 210f, 170f)
        cubicTo(188f, 158f, 162f, 141f, 156f, 115f)
        lineTo(149f, 61f); close()
    }
    drawPath(shield, c.secondaryContainer)
    val check = Path().apply { moveTo(185f, 100f); lineTo(203f, 118f); lineTo(237f, 81f) }
    drawPath(check, c.onSecondaryContainer, style = Stroke(6f, cap = StrokeCap.Round))
}

private fun DrawScope.drawPerson(c: ColorScheme) {
    // A reader at a desk: abstract skin/clothing colors intentionally follow the user's palette.
    drawRoundRect(c.secondaryContainer, Offset(55f, 113f), Size(53f, 48f), CornerRadius(15f))
    val legs = Path().apply {
        moveTo(81f, 138f); lineTo(108f, 141f); lineTo(120f, 176f)
        lineTo(105f, 176f); lineTo(92f, 155f); lineTo(73f, 155f); close()
    }
    drawPath(legs, c.onSurfaceVariant)
    drawLine(c.onSurface, Offset(105f, 178f), Offset(128f, 178f), 6f, StrokeCap.Round)
    drawLine(c.outlineVariant, Offset(58f, 160f), Offset(58f, 181f), 3f)
    val body = Path().apply {
        moveTo(67f, 90f); cubicTo(47f, 106f, 57f, 136f, 72f, 144f)
        lineTo(99f, 139f); lineTo(91f, 103f); close()
    }
    drawPath(body, c.primary)
    drawCircle(c.tertiaryContainer, 15f, Offset(81f, 73f))
    val hair = Path().apply {
        moveTo(66f, 76f); cubicTo(51f, 51f, 94f, 45f, 99f, 68f)
        lineTo(84f, 65f); lineTo(79f, 80f); close()
    }
    drawPath(hair, c.onSurface)
    drawLine(c.primary, Offset(84f, 102f), Offset(110f, 119f), 13f, StrokeCap.Round)
    drawLine(c.tertiaryContainer, Offset(110f, 119f), Offset(141f, 103f), 9f, StrokeCap.Round)
    drawCircle(c.tertiaryContainer, 6f, Offset(143f, 102f))
    drawLine(c.outline, Offset(119f, 165f), Offset(295f, 165f), 3f, StrokeCap.Round)
    drawLine(c.outline, Offset(135f, 165f), Offset(135f, 181f), 3f)
    drawLine(c.outline, Offset(280f, 165f), Offset(280f, 181f), 3f)
}

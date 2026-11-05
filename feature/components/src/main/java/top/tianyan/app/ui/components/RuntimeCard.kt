package top.tianyan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import top.tianyan.app.ui.theme.Spacing
import top.tianyan.app.ui.theme.resolveCardBorder

/**
 * 天衍精制卡片：1 层表面色 + 1dp 主题发丝边。
 *
 * 从 `RuntimeComponents.kt` 拆出（该文件撞上 740 行棘轮基线）。视觉口径的完整说明见
 * [resolveCardBorder]：不传 `borderColor` 时用「浅色半透明暗线 / 深色半透明白线」，
 * 深色主题下不依赖阴影也能让卡片浮起来；显式传 `Color.Transparent` 才是无边。
 */
@Composable
fun RuntimeCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = Color.Unspecified,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = resolveCardBorder(borderColor)

    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

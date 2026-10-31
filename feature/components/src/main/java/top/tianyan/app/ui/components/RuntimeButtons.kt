@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package top.tianyan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Use the native expressive shape animation while keeping existing sizing and callbacks.
/**
 * 主操作按钮：tonal 为 true 时使用 FilledTonalButton，否则使用 Button。
 */
@Composable
fun RuntimeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(shape = shape),
            colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
            border = border,
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(shape = shape),
            colors = colors ?: ButtonDefaults.buttonColors(),
            border = border,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
fun RuntimeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = shape),
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun RuntimeFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = RuntimeButton(onClick, modifier, enabled, tonal = true, contentPadding = contentPadding, shape = shape, colors = colors, border = border, content = content)

@Composable
fun RuntimeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    shape: Shape = ButtonDefaults.textShape,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = shape),
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun RuntimeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val accessibilityLabel = contentDescription
    // 将无障碍标签挂到按钮节点上（Icon 自身 contentDescription 恒为 null）
    IconButton(
        shapes = IconButtonDefaults.shapes(),
        onClick = onClick,
        modifier = modifier.semantics { accessibilityLabel?.let { this.contentDescription = it } },
        enabled = enabled,
        content = content,
    )
}

/** Filled icon action with a native 48dp touch target and Material disabled states. */
@Composable
fun RuntimeFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.FilledIconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.semantics { contentDescription?.let { this.contentDescription = it } },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor, contentColor = contentColor),
        content = content)
}


package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 模型能力标签的展示数据；null 表示未知，不展示 */
data class ModelCapabilityUi(
    val vision: Boolean? = null,
    val tool: Boolean? = null,
    val reasoning: Boolean? = null,
    val contextTokens: Int? = null,
)

/**
 * 模型能力标签行：视觉 / 工具 / 推理 / 上下文窗口。
 * 全部未知时整行不渲染，避免未知模型在列表里空占一行。
 */
@Composable
fun ModelCapabilityTags(
    capability: ModelCapabilityUi,
    modifier: Modifier = Modifier,
) {
    val tags = buildList {
        if (capability.vision == true) add("视觉")
        if (capability.tool == true) add("工具")
        if (capability.reasoning == true) add("推理")
        capability.contextTokens?.takeIf { it > 0 }?.let { add(formatModelContext(it)) }
    }
    if (tags.isEmpty()) return
    Text(tags.joinToString(" · "), modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

fun formatModelContext(tokens: Int): String = when {
    tokens <= 0 -> ""
    tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    else -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
}

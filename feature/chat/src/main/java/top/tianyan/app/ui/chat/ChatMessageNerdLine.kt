package top.tianyan.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.formatRuntimeProcessDuration
import top.tianyan.app.ui.components.RuntimeIconName

/** 当前会话的上下文窗口限额，由 ChatScreen 随 ContextUsage.limitTokens 提供；分段条用它做分母。 */
val LocalChatContextLimit = staticCompositionLocalOf { 128_000 }

/**
 * 消息级统计行（一瞥可得，点击原地展开详情与上下文分段条）。
 * 只渲染带 token 用量的助手消息；旧消息无该字段时整行隐藏。完整逐请求诊断仍在上下文面板。
 */
@Composable
internal fun AssistantNerdLine(message: AssistantText) {
    val prompt = message.promptTokens
    val completion = message.completionTokens
    // 旧消息没有统一缓存口径，异常用量也不能导致超过 100% 的命中率或负分段。
    val cached = (message.cachedTokens ?: 0).coerceIn(0, (prompt ?: 0).coerceAtLeast(0))
    if (prompt == null && completion == null) return
    val limit = LocalChatContextLimit.current
    val used = (prompt ?: 0) + (completion ?: 0)
    val nearLimit = limit in 1..used && used * 10 / limit >= 9

    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val summary = buildString {
                append(stringResource(R.string.chat_nerd_context, formatTokenCount(used)))
                if (prompt != null && prompt > 0) {
                    append(" · ")
                    append(stringResource(R.string.chat_nerd_cache, (cached.toLong() * 100 / prompt).toInt()))
                }
                if (completion != null && completion > 0) {
                    append(" · ")
                    append(stringResource(R.string.chat_nerd_output, formatTokenCount(completion)))
                }
                message.totalMs?.takeIf { it > 0 }?.let {
                    append(" · ")
                    append(stringResource(R.string.chat_nerd_turn, formatRuntimeProcessDuration(it)))
                }
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            RuntimeIcon(
                if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                Modifier.size(12.dp),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(start = 2.dp, top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ContextUsageBar(
                    cached = cached,
                    uncached = ((prompt ?: 0) - cached).coerceAtLeast(0),
                    output = completion ?: 0,
                    limit = limit,
                    nearLimit = nearLimit,
                )
                NerdLegend(
                    prompt = prompt ?: 0,
                    completion = completion ?: 0,
                    cached = cached,
                    limit = limit,
                    nearLimit = nearLimit,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NerdLegend(prompt: Int, completion: Int, cached: Int, limit: Int, nearLimit: Boolean) {
    val segments = listOf(
        NerdSegment(stringResource(R.string.chat_nerd_cached), cached, MaterialTheme.colorScheme.primary),
        NerdSegment(stringResource(R.string.chat_nerd_uncached), (prompt - cached).coerceAtLeast(0), MaterialTheme.colorScheme.tertiary),
        NerdSegment(stringResource(R.string.chat_nerd_output), completion, MaterialTheme.colorScheme.secondary),
        NerdSegment(stringResource(R.string.chat_nerd_remaining), (limit - prompt - completion).coerceAtLeast(0),
            if (nearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(segment.color))
                Text(
                    text = "${segment.label} ${formatTokenCount(segment.value)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class NerdSegment(val label: String, val value: Int, val color: Color)

/** 四色分段条：缓存命中 / 新输入 / 输出 / 剩余。过小的段画成圆点保证可见，接近或超出限额时剩余段转警示色。 */
@Composable
private fun ContextUsageBar(cached: Int, uncached: Int, output: Int, limit: Int, nearLimit: Boolean) {
    data class Segment(val value: Int, val color: Color)
    val segments = listOf(
        Segment(cached, MaterialTheme.colorScheme.primary),
        Segment(uncached, MaterialTheme.colorScheme.tertiary),
        Segment(output, MaterialTheme.colorScheme.secondary),
        Segment((limit - cached - uncached - output).coerceAtLeast(0),
            if (nearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
    )
    val total = limit.coerceAtLeast(cached + uncached + output).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            val fraction = segment.value.toFloat() / total
            if (segment.value <= 0) {
                // 空段不占位
            } else if (fraction * 100f < 1.5f) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(segment.color)
                )
            } else {
                Box(
                    Modifier
                        .weight(fraction)
                        .height(8.dp)
                        .background(segment.color)
                )
            }
        }
    }
}

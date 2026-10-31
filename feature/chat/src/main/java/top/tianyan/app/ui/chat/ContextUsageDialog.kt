package top.tianyan.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import top.tianyan.app.ui.theme.semanticColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.StatusBadge
import java.util.Locale
import kotlin.math.roundToInt
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * 🌟 上下文用量可视化弹窗 (Context Usage Dialog)
 * 高保真复刻 8 维彩色分段进度条与用量细分清单。
 */
@Composable
fun ContextUsageDialog(
    usage: ContextUsage,
    onDismiss: () -> Unit,
    onInspectRequest: () -> Unit = {},
) {

    val limitTokens = usage.limitTokens.coerceAtLeast(1)
    val rawRatio = (usage.usedTokens.toFloat() / limitTokens).coerceIn(0f, 1f)
    val displayPercent = (rawRatio * 100).roundToInt().coerceIn(0, 100)

    ChatBottomSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context_title)) },
        confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 2. Summary stats row: "73% Full" / "~185.9K / 256K Tokens"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.chat_context_full, displayPercent),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_context_tokens_summary,
                            formatDialogTokenCount(usage.usedTokens),
                            formatLimitTokens(usage.limitTokens),
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }

                if (usage.declaredTokens != usage.limitTokens) {
                    Text(
                        text = stringResource(
                            R.string.chat_context_declared_limit,
                            formatLimitTokens(usage.declaredTokens),
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                if (usage.compactionThresholdTokens in 1 until usage.limitTokens) {
                    Text(
                        text = stringResource(
                            R.string.chat_context_compaction_threshold,
                            formatLimitTokens(usage.compactionThresholdTokens),
                            usage.foldingRatioPercent,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }


                // 3. Segmented multi-color progress bar
                ContextUsageSegmentedBar(
                    usage = usage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )

                // 4. Breakdown legend rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 分类色取自主题分类色板，随明暗切换校准；不再在页面里手写十六进制色值。
                    val palette = semanticColors().categorical
                    val categories = remember(usage.breakdown, palette) {
                        listOf(
                            ContextCategorySpec(palette[0], R.string.chat_context_system_prompt, usage.breakdown.systemPromptTokens),
                            ContextCategorySpec(palette[1], R.string.chat_context_tool_definitions, usage.breakdown.toolDefinitionTokens),
                            ContextCategorySpec(palette[2], R.string.chat_context_rules, usage.breakdown.rulesTokens),
                            ContextCategorySpec(palette[3], R.string.chat_context_skills, usage.breakdown.skillsTokens),
                            ContextCategorySpec(palette[4], R.string.chat_context_mcp_dynamic, usage.breakdown.mcpTokens),
                            ContextCategorySpec(palette[5], R.string.chat_context_subagent_definitions, usage.breakdown.subagentTokens),
                            ContextCategorySpec(palette[6], R.string.chat_context_summarized_conversation, usage.breakdown.summarizedTokens),
                            ContextCategorySpec(palette[7], R.string.chat_context_conversation, usage.breakdown.conversationTokens),
                        )
                    }

                    categories.forEach { spec ->
                        ContextUsageRow(spec = spec)
                    }
                }

                RuntimeTextButton(onClick = onInspectRequest) {
                    Text(stringResource(R.string.chat_request_context_title))
                }
                // 5. Optional Tianyan Enhanced Footer (KV Cache & Compaction)
                if (usage.cachedTokens > 0L || usage.compacted) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (usage.cachedTokens > 0L) {
                            Text(
                                text = stringResource(
                                    R.string.chat_context_kv_cache,
                                    formatDialogTokenCount(usage.cachedTokens.toInt()),
                                    usage.cacheHitRatePercent ?: 0,
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                        if (usage.compacted) {
                            StatusBadge(
                                text = stringResource(R.string.chat_context_compacted_active),
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ContextUsageRow(
    spec: ContextCategorySpec,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color swatch
        Box(
            modifier = Modifier
                .size(11.dp)
                .background(spec.color, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        // Category Label
        Text(
            text = stringResource(spec.titleRes),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        // Formatted Token Count
        Text(
            text = formatDialogTokenCount(spec.tokens),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
fun ContextUsageSegmentedBar(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    val palette = semanticColors().categorical
    val items = remember(usage.breakdown, palette) {
        listOf(
            SegmentItem(palette[0], usage.breakdown.systemPromptTokens),
            SegmentItem(palette[1], usage.breakdown.toolDefinitionTokens),
            SegmentItem(palette[2], usage.breakdown.rulesTokens),
            SegmentItem(palette[3], usage.breakdown.skillsTokens),
            SegmentItem(palette[4], usage.breakdown.mcpTokens),
            SegmentItem(palette[5], usage.breakdown.subagentTokens),
            SegmentItem(palette[6], usage.breakdown.summarizedTokens),
            SegmentItem(palette[7], usage.breakdown.conversationTokens),
        ).filter { it.tokens > 0 }
    }

    val limit = usage.limitTokens.coerceAtLeast(1)
    val totalUsed = usage.usedTokens.coerceAtLeast(0)

    val animatedRatio by animateFloatAsState(
        targetValue = (totalUsed.toFloat() / limit).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "contextBarRatio",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp)),
    ) {
        val w = size.width
        val h = size.height

        // 1. Draw track (empty budget background)
        drawRect(
            color = Color(0xFF27272A),
            topLeft = Offset.Zero,
            size = size,
        )

        // 2. Draw segments seamlessly without gaps and without rounded corners
        if (items.isNotEmpty() && animatedRatio > 0f) {
            val totalUsedW = w * animatedRatio
            val sumTokens = items.sumOf { it.tokens }.coerceAtLeast(1)

            var cursorX = 0f
            for (item in items) {
                val proportionalW = (item.tokens.toFloat() / sumTokens) * totalUsedW
                val segmentW = minOf(proportionalW, (w - cursorX).coerceAtLeast(0f))
                if (segmentW > 0f) {
                    drawRect(
                        color = item.color,
                        topLeft = Offset(cursorX, 0f),
                        size = Size(segmentW, h),
                    )
                    cursorX += segmentW
                }
                if (cursorX >= w) break
            }
        }

        // 3. Compaction threshold marker: ring/bar still shows model-window usage,
        // while this line tells the user when the next request will fold history.
        val thresholdRatio = (usage.compactionThresholdTokens.toFloat() / limit).coerceIn(0f, 1f)
        if (thresholdRatio in 0.01f..0.99f) {
            val markerX = w * thresholdRatio
            drawLine(
                color = Color.White.copy(alpha = 0.72f),
                start = Offset(markerX, 0f),
                end = Offset(markerX, h),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

private data class ContextCategorySpec(
    val color: Color,
    val titleRes: Int,
    val tokens: Int,
)

private data class SegmentItem(
    val color: Color,
    val tokens: Int,
)

private fun formatDialogTokenCount(tokens: Int): String = when {
        tokens <= 0 -> "0"
        tokens < 1000 -> tokens.toString()
        tokens < 1_000_000 -> {
            val k = tokens / 1000.0
            String.format(Locale.US, "%.1fK", k)
        }
        else -> {
            val m = tokens / 1_000_000.0
            String.format(Locale.US, "%.1fM", m)
        }
    }

private fun formatLimitTokens(tokens: Int): String = when {
        tokens <= 0 -> "0"
        tokens < 1000 -> tokens.toString()
        tokens % 1000 == 0 && tokens < 1_000_000 -> "${tokens / 1000}K"
        tokens < 1_000_000 -> String.format(Locale.US, "%.1fK", tokens / 1000.0)
        tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    }

package top.tianyan.app.ui.settings.stats.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.StatsLedger
import top.tianyan.app.core.model.StatsLedgerGroupDim
import top.tianyan.app.core.model.StatsLedgerGroupRow
import top.tianyan.app.core.model.StatsTrendDay
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIconName
import java.util.Locale

/**
 * 「用量」分区：账本口径的核心数字。
 *
 * 只放账本给得出的**真实计量值**；估算口径的 token 三项已从这里退场（由构成条承担），
 * 底部一行只留估算口径独有的**行为指标**，因此与页面其它分区不重复。
 */
@Composable
fun LedgerUsagePanel(
    ledger: StatsLedger,
    conversations: Int,
    messages: Int,
    launches: Int,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatsSectionCard(
        title = "用量",
        modifier = modifier,
        trailing = {
            IconButton(onClick = onOpenDetail, modifier = Modifier.size(24.dp)) {
                RuntimeIcon(
                    name = RuntimeIconName.OpenInNew,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                LedgerMetricRow("Token 合计", ledgerCompact(ledger.totalTokens))
                LedgerMetricRow("输入", ledgerCompact(ledger.inputTokens))
                LedgerMetricRow("推理", ledgerCompact(ledger.reasoningTokens))
                LedgerMetricRow("缓存读取", ledgerCompact(ledger.cacheReadTokens))
                LedgerMetricRow("平均单次", ledgerCompact(ledger.averageTokensPerCall()))
            }
            Column(modifier = Modifier.weight(1f)) {
                LedgerMetricRow("LLM 调用", "${ledger.calls}")
                LedgerMetricRow("输出", ledgerCompact(ledger.outputTokens))
                LedgerMetricRow("缓存命中率", ledgerPercent(ledger.cacheHitRate))
                LedgerMetricRow("缓存写入", ledgerCompact(ledger.cacheWriteTokens))
                LedgerMetricRow("成本", "未计算")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LedgerCounter("对话", "$conversations", Modifier.weight(1f))
            LedgerCounter("消息", ledgerCompact(messages.toLong()), Modifier.weight(1f))
            LedgerCounter("启动", "$launches", Modifier.weight(1f))
        }

        Text(
            text = "以上均为用量账本记录的真实计量值；底部三项是与 token 无关的行为指标。成本暂未计算——目前还没有定价表可用。",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LedgerMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun LedgerCounter(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
        )
    }
}

/**
 * 「用量趋势」分区。
 *
 * 复用既有 [StatsUsageChart]：喂账本口径的数据即可，于是「点立柱看当日各模型」
 * 与「左右滑动看趋势」两个既有手感原样继承，不需要重写手势。
 */
@Composable
fun LedgerTrendPanel(
    days: List<StatsTrendDay>,
    modifier: Modifier = Modifier,
) {
    StatsSectionCard(title = "用量趋势", modifier = modifier) {
        if (days.isEmpty()) {
            Text(
                text = "所选范围内没有账本记录",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            StatsUsageChart(days = days, modifier = Modifier.fillMaxWidth())
            Text(
                text = "点立柱看当日各模型用量，左右滑动可看更早区间。",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 「分解」分区：同一批数字的三种拆法。
 *
 * 比例条**同色**，只表达"占多少"；类型构成（输入/输出/推理/缓存写）留给「构成」分区，
 * 两块刻意不重叠。
 */
@Composable
fun LedgerBreakdownPanel(
    rows: List<StatsLedgerGroupRow>,
    groupDim: StatsLedgerGroupDim,
    onGroupChange: (StatsLedgerGroupDim) -> Unit,
    modifier: Modifier = Modifier,
) {
    StatsSectionCard(title = "分解", modifier = modifier) {
        LedgerGroupControls(groupDim = groupDim, onGroupChange = onGroupChange)
        Spacer(modifier = Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Text(
                text = "该作用域下没有可分解的数据",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
            return@StatsSectionCard
        }

        val maxTokens = rows.maxOf { it.totalTokens }.coerceAtLeast(1L)
        val sumTokens = rows.sumOf { it.totalTokens }.coerceAtLeast(1L)

        for (row in rows) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "${ledgerCompact(row.totalTokens)} · ${ledgerPercent(row.totalTokens.toDouble() / sumTokens)} · ${row.calls} 次",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((row.totalTokens.toFloat() / maxTokens.toFloat()).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = "入 ${ledgerCompact(row.inputTokens)} · 出 ${ledgerCompact(row.outputTokens)} · 推 ${ledgerCompact(row.reasoningTokens)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * 「构成」分区：token 类型构成。
 * 缓存命中读取**不计入**合计——它是输入前缀的复用部分，计入会与输入双重计算。
 */
@Composable
fun LedgerCompositionPanel(
    ledger: StatsLedger,
    modifier: Modifier = Modifier,
) {
    val segments = listOf(
        Triple("输入", ledger.inputTokens, MaterialTheme.colorScheme.primary),
        Triple("输出", ledger.outputTokens, MaterialTheme.colorScheme.secondary),
        Triple("推理", ledger.reasoningTokens, MaterialTheme.colorScheme.tertiary),
        Triple("缓存写入", ledger.cacheWriteTokens, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ).filter { (_, value, _) -> value > 0L }

    StatsSectionCard(title = "构成", modifier = modifier) {
        if (segments.isEmpty()) {
            Text(
                text = "所选范围内没有账本记录",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
            return@StatsSectionCard
        }

        val total = segments.sumOf { it.second }.coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            for ((_, value, color) in segments) {
                Box(
                    modifier = Modifier
                        .weight(value.toFloat() / total.toFloat())
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((label, value, color) in segments) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Text(
                        text = "$label  ${ledgerCompact(value)} · ${ledgerPercent(value.toDouble() / total)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        Text(
            text = "缓存命中读取 ${ledgerCompact(ledger.cacheReadTokens)} 不计入合计：它是输入前缀的复用部分，计入会与输入双重计算。",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 计费口径里"一次调用平均多少 token"。 */
internal fun StatsLedger.averageTokensPerCall(): Long =
    if (calls <= 0) 0L else totalTokens / calls

internal fun ledgerPercent(ratio: Double): String {
    if (ratio <= 0.0) return "0%"
    return String.format(Locale.US, "%.1f%%", ratio * 100.0)
}

internal fun ledgerCompact(value: Long): String {
    if (value < 1000L) return value.toString()
    if (value < 1_000_000L) {
        val k = value / 1000.0
        return String.format(Locale.US, if (k < 10) "%.1fk" else "%.0fk", k)
    }
    val m = value / 1_000_000.0
    return String.format(Locale.US, if (m < 10) "%.1fM" else "%.0fM", m)
}

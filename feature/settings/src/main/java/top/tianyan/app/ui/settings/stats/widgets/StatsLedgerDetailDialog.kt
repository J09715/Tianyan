package top.tianyan.app.ui.settings.stats.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.StatsLedger
import top.tianyan.app.core.model.StatsLedgerCallRow
import top.tianyan.app.core.model.StatsLedgerGroupDim
import top.tianyan.app.core.model.StatsLedgerGroupRow
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LEDGER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 用量明细弹窗。
 *
 * 与 [StatsRankSection] 的「查看全部」同构：图标触发、内容可滚动、底部单个「关闭」。
 * 区别是这里给的是逐对象的完整 token 拆分与逐次调用明细——也就是估算口径下拿不到的
 * 推理 token 与缓存读写分列。拆分维度跟随页面上选的分组方式。
 */
@Composable
internal fun LedgerDetailDialog(
    ledger: StatsLedger,
    groupDim: StatsLedgerGroupDim,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "用量明细",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LedgerDetailHeader(ledger = ledger)

                    DetailBlock(title = "按" + ledgerGroupDimLabel(groupDim) + "拆分") {
                        val rows = ledger.groupsOf(groupDim)
                        if (rows.isEmpty()) {
                            DetailEmpty(text = "所选范围内没有账本记录")
                        } else {
                            for (row in rows) {
                                LedgerGroupRowItem(row = row)
                            }
                        }
                    }

                    DetailBlock(title = "最近调用") {
                        if (ledger.recentCalls.isEmpty()) {
                            DetailEmpty(text = "所选范围内没有账本记录")
                        } else {
                            for (call in ledger.recentCalls) {
                                LedgerCallRowItem(call = call)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun LedgerDetailHeader(ledger: StatsLedger) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "合计 ${ledgerCompact(ledger.totalTokens)} Token，共 ${ledger.calls} 次调用",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Text(
            text = "输入 ${ledgerCompact(ledger.inputTokens)} · 输出 ${ledgerCompact(ledger.outputTokens)} · " +
                "推理 ${ledgerCompact(ledger.reasoningTokens)} · 缓存读 ${ledgerCompact(ledger.cacheReadTokens)} · " +
                "缓存写 ${ledgerCompact(ledger.cacheWriteTokens)}",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Text(
            text = "成本暂未计算：目前还没有定价表可用，因此这里显式留空，而不是给一个看起来像真的估算值。",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        )
    }
}

@Composable
private fun DetailBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            ),
        )
        content()
    }
}

@Composable
private fun DetailEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    )
}

@Composable
private fun LedgerGroupRowItem(row: StatsLedgerGroupRow) {
    DetailRow(
        primary = row.label,
        secondary = row.provider + " · " + row.calls + " 次调用",
        trailing = "入 ${ledgerCompact(row.inputTokens)} 出 ${ledgerCompact(row.outputTokens)} " +
            "推 ${ledgerCompact(row.reasoningTokens)}",
    )
}

@Composable
private fun LedgerCallRowItem(call: StatsLedgerCallRow) {
    DetailRow(
        primary = call.label,
        secondary = formatLedgerTime(call.createdAt) + " · " + call.provider,
        trailing = "入 ${ledgerCompact(call.inputTokens)} 出 ${ledgerCompact(call.outputTokens)} " +
            "推 ${ledgerCompact(call.reasoningTokens)}",
    )
}

@Composable
private fun DetailRow(
    primary: String,
    secondary: String,
    trailing: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
        )
    }
}

private fun formatLedgerTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(LEDGER_TIME_FORMAT)

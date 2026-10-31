package top.tianyan.app.ui.settings.stats.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.StatsLedgerGroupDim
import top.tianyan.app.core.model.StatsLedgerScopeDim
import top.tianyan.app.core.model.StatsLedgerScopeOption
import top.tianyan.app.core.model.StatsLedgerScopeOptions
import top.tianyan.app.ui.components.RuntimeFilterChip

/**
 * 作用域控件组：上面的「维度」是大控件，下面的「取值」是小部件，两者是父子关系
 * ——选完维度才出现取值胶囊，没选维度时下面整行不占位。
 *
 * 取值列表**恒按未限定口径**统计（由仓库保证），否则选中一项后其余胶囊会消失、切不回去。
 */
@Composable
fun LedgerScopeControls(
    scopeDim: StatsLedgerScopeDim,
    scopeValue: String?,
    options: StatsLedgerScopeOptions,
    onScopeChange: (StatsLedgerScopeDim, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (dim in LEDGER_SCOPE_DIMS) {
                val selected = dim == scopeDim
                RuntimeFilterChip(
                    selected = selected,
                    onClick = { onScopeChange(dim, null) },
                    label = {
                        Text(
                            text = ledgerScopeDimLabel(dim),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    },
                )
            }
        }

        val values = ledgerScopeValuesOf(options, scopeDim)
        if (values.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (option in values) {
                    val selected = option.id == scopeValue
                    RuntimeFilterChip(
                        selected = selected,
                        onClick = { onScopeChange(scopeDim, option.id) },
                        label = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 分组方式控件。与作用域正交：作用域决定"数字属于谁"，这里决定"同一批数字怎么拆"。
 * 三种分组在快照里已预计算，所以切换它不需要重新查库。
 */
@Composable
fun LedgerGroupControls(
    groupDim: StatsLedgerGroupDim,
    onGroupChange: (StatsLedgerGroupDim) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (dim in LEDGER_GROUP_DIMS) {
            val selected = dim == groupDim
            RuntimeFilterChip(
                selected = selected,
                onClick = { onGroupChange(dim) },
                label = {
                    Text(
                        text = ledgerGroupDimLabel(dim),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                },
            )
        }
    }
}

/** Bot 维度经 sessionId→botId 三路映射聚合；工具维度**故意不做**（账本没落工具名）。 */
private val LEDGER_SCOPE_DIMS = listOf(
    StatsLedgerScopeDim.ALL,
    StatsLedgerScopeDim.MODEL,
    StatsLedgerScopeDim.PROVIDER,
    StatsLedgerScopeDim.SESSION,
    StatsLedgerScopeDim.BOT,
)

private val LEDGER_GROUP_DIMS = listOf(
    StatsLedgerGroupDim.MODEL,
    StatsLedgerGroupDim.PROVIDER,
    StatsLedgerGroupDim.SESSION,
    StatsLedgerGroupDim.BOT,
)

private fun ledgerScopeDimLabel(dim: StatsLedgerScopeDim): String = when (dim) {
    StatsLedgerScopeDim.ALL -> "整体"
    StatsLedgerScopeDim.MODEL -> "按模型"
    StatsLedgerScopeDim.PROVIDER -> "按供应商"
    StatsLedgerScopeDim.SESSION -> "按会话"
    StatsLedgerScopeDim.BOT -> "按 Bot"
}

internal fun ledgerGroupDimLabel(dim: StatsLedgerGroupDim): String = when (dim) {
    StatsLedgerGroupDim.MODEL -> "模型"
    StatsLedgerGroupDim.PROVIDER -> "供应商"
    StatsLedgerGroupDim.SESSION -> "会话"
    StatsLedgerGroupDim.BOT -> "Bot"
}

internal fun ledgerScopeValuesOf(
    options: StatsLedgerScopeOptions,
    dim: StatsLedgerScopeDim,
): List<StatsLedgerScopeOption> = when (dim) {
    StatsLedgerScopeDim.ALL -> emptyList()
    StatsLedgerScopeDim.MODEL -> options.models
    StatsLedgerScopeDim.PROVIDER -> options.providers
    StatsLedgerScopeDim.SESSION -> options.sessions
    StatsLedgerScopeDim.BOT -> options.bots
}

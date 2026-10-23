package top.tianyan.app.ui.settings.stats.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.common.Fill
import top.tianyan.app.core.model.StatsTrendDay
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.theme.Spacing

/**
 * 用量趋势柱状图。
 *
 * 原本手写 199 行 Canvas（柱宽/间距自适应、点击选中、浮动详情卡），
 * 改用 Vico 后坐标轴、刻度、动画、无障碍标签全由库负责，
 * 这里只保留「数据 → 模型」映射与主题色接入。
 */
@Composable
fun StatsUsageChart(
    days: List<StatsTrendDay>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无用量数据",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
        }
        return
    }

    val scheme = MaterialTheme.colorScheme
    val totals = remember(days) {
        days.map { day -> day.providerTokens.values.sumOf { it.totalTokens }.toDouble() }
    }
    val modelProducer = remember { CartesianChartModelProducer() }

    // runTransaction 是 suspend，且模型更新不需要跟随重组，放 LaunchedEffect 里跑一次
    LaunchedEffect(totals) {
        modelProducer.runTransaction {
            columnSeries { series(totals) }
        }
    }

    RuntimeCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "近 ${days.size} 天 Token 用量",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            CartesianChartHost(
                chart = rememberUsageChart(scheme),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
    }
}

/** 图表本体：层 + 两轴。抽成独立 composable 才能调用 remember* 系列。 */
@Composable
private fun rememberUsageChart(scheme: ColorScheme): CartesianChart {
    val column = rememberColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
            rememberLineComponent(
                fill = Fill(scheme.primary.toArgb()),
                thickness = 12.dp,
            ),
        ),
    )
    val startAxis = VerticalAxis.rememberStart(
        line = rememberLineComponent(fill = Fill(scheme.outlineVariant.toArgb())),
        label = rememberTextComponent(color = scheme.onSurfaceVariant),
    )
    val bottomAxis = HorizontalAxis.rememberBottom(
        line = rememberLineComponent(fill = Fill(scheme.outlineVariant.toArgb())),
        label = rememberTextComponent(color = scheme.onSurfaceVariant),
    )
    return remember(scheme) { CartesianChart(column, startAxis = startAxis, bottomAxis = bottomAxis) }
}

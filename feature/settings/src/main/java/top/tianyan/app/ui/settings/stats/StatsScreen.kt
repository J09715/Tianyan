package top.tianyan.app.ui.settings.stats
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.model.StatsDateRangePreset
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.settings.stats.widgets.LedgerBreakdownPanel
import top.tianyan.app.ui.settings.stats.widgets.LedgerCompositionPanel
import top.tianyan.app.ui.settings.stats.widgets.LedgerDetailDialog
import top.tianyan.app.ui.settings.stats.widgets.LedgerScopeControls
import top.tianyan.app.ui.settings.stats.widgets.LedgerTrendPanel
import top.tianyan.app.ui.settings.stats.widgets.LedgerUsagePanel
import top.tianyan.app.ui.settings.stats.widgets.StatsHeatmap
import top.tianyan.app.ui.settings.stats.widgets.StatsMetricGrid
import top.tianyan.app.ui.settings.stats.widgets.StatsSectionCard
import top.tianyan.app.ui.settings.stats.widgets.StatsUsageChart
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel? = previewOrNull { koinViewModel() },
) {
    val uiState by viewModel?.uiState?.collectAsStateWithLifecycle() ?: previewStateOf(StatsUiState())

    // 明细弹窗的开关就放在页面这一层：多个分区（用量右上角、分解行）都可能打开它。
    var showLedgerDetail by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "数据统计与用量分析",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)),
        ) {
            // 顶部时间范围筛选胶囊
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = listOf(
                    StatsDateRangePreset.ALL_TIME to "全部时间",
                    StatsDateRangePreset.LAST_30_DAYS to "近30天",
                    StatsDateRangePreset.PREVIOUS_MONTH to "上个月",
                    StatsDateRangePreset.PREVIOUS_QUARTER to "上季度",
                )

                for ((preset, label) in presets) {
                    val selected = uiState.range.preset == preset
                    RuntimeFilterChip(
                        selected = selected,
                        onClick = { viewModel?.setDateRangePreset(preset) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }
            }

            // 作用域控件是页面级的（与时间范围同级），不放在 LazyColumn 里，
            // 否则滚动时会跟着内容跑掉。账本为空时整块隐藏，不出现空壳控件。
            val ledgerForScope = uiState.snapshot?.ledger?.takeIf { it.calls > 0 }
            if (ledgerForScope != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    LedgerScopeControls(
                        scopeDim = uiState.scopeDim,
                        scopeValue = uiState.scopeValue,
                        options = ledgerForScope.scopeOptions,
                        onScopeChange = { dim, value -> viewModel?.setLedgerScope(dim, value) },
                    )
                }
            }

            if (uiState.isLoading) {
                RuntimeLinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 加载失败：错误态 + 重试入口（复用模块内 RuntimeCard + 按钮模式）
            uiState.error?.let { errorMessage ->
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            RuntimeButton(
                                onClick = { viewModel?.refresh() },
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }
            }

            // 加载完成但没有任何数据：给出空态而非整页空白
            if (uiState.error == null && !uiState.isLoading && uiState.snapshot?.heatmap.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无统计数据。开始使用 Agent 对话与工具后，这里会展示活跃度与 Token 用量。",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val snapshot = uiState.snapshot
            if (snapshot != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. 活跃度打卡热力图（与用量无关的独立维度，原样保留）
                    item {
                        StatsSectionCard(title = "活跃度打卡热力图") {
                            StatsHeatmap(days = snapshot.heatmap)
                        }
                    }

                    if (snapshot.ledger.calls > 0) {
                        // 2. 用量 —— 账本的真实计量值。估算口径的 token 三项已从这里退场，
                        //    只保留它独有的行为指标，因此与下面几个分区不重复。
                        item {
                            LedgerUsagePanel(
                                ledger = snapshot.ledger,
                                conversations = snapshot.summary.totalConversations,
                                messages = snapshot.summary.totalMessages,
                                launches = snapshot.summary.launchCount,
                                onOpenDetail = { showLedgerDetail = true },
                            )
                        }

                        // 3. 用量趋势 —— 复用既有图表组件：点立柱看当日各模型、
                        //    左右滑动看趋势两个手感原样继承，不必重写手势。
                        item {
                            LedgerTrendPanel(days = snapshot.ledger.trend)
                        }

                        // 4. 分解 —— 三种分组在快照里已预计算，切换分组不查库。
                        item {
                            LedgerBreakdownPanel(
                                rows = snapshot.ledger.groupsOf(uiState.groupDim),
                                groupDim = uiState.groupDim,
                                onGroupChange = { viewModel?.setLedgerGroupDim(it) },
                            )
                        }

                        // 5. 构成 —— token 类型构成，到这一节页面结束。
                        item {
                            LedgerCompositionPanel(ledger = snapshot.ledger)
                        }
                    } else {
                        // 账本为空：早于用量账本落库的历史用法。这里回退到估算口径，
                        // 否则这些用户整页只剩一张热力图——与「估算口径要覆盖账本落库之前的历史」
                        // 这一设计前提直接矛盾。
                        item {
                            StatsSectionCard(title = "核心指标概览") {
                                StatsMetricGrid(summary = snapshot.summary)
                            }
                        }
                        item {
                            StatsSectionCard(title = "Token 用量与消耗趋势") {
                                StatsUsageChart(days = snapshot.trend)
                            }
                        }
                    }
                }
            }
        }

        val ledger = uiState.snapshot?.ledger
        if (showLedgerDetail && ledger != null) {
            LedgerDetailDialog(
                ledger = ledger,
                groupDim = uiState.groupDim,
                onDismiss = { showLedgerDetail = false },
            )
        }
    }
}

package top.tianyan.app.ui.chat

import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.ContextUsageBreakdown
import top.tianyan.app.harness.diagnostics.RequestContextSnapshot
import top.tianyan.app.harness.diagnostics.RequestArchiveStatus

data class ContextUsage(
    val usedTokens: Int = 0,
    /**
     * 模型上下文窗口（总预算，也是面板百分比的分母）。
     * 主流 harness 以模型窗口展示占用；实际折叠线见 [compactionThresholdTokens]。
     */
    val limitTokens: Int = 128_000,
    /** 达到该 token 数后下一次请求会触发历史压缩。 */
    val compactionThresholdTokens: Int = 116_000,
    /**
     * 当前生效的模型上下文窗口。优先用户显式配置，其次自动适配主流模型元数据，
     * 最后才回退全局预算。仅用于面板标注，不参与百分比计算。
     */
    val declaredTokens: Int = 128_000,
    /** 历史折叠线比例（%）。面板据此标注「按 X% 折叠」，使折叠决策对用户可见。 */
    val foldingRatioPercent: Int = ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT,
    val systemTokens: Int = 0,
    val toolTokens: Int = 0,
    val conversationTokens: Int = 0,
    val compacted: Boolean = false,
    val cachedTokens: Long = 0L,
    val cacheHitRatePercent: Int? = null,
    val breakdown: ContextUsageBreakdown = ContextUsageBreakdown(),
    val requests: List<RequestContextSnapshot> = emptyList(),
    val requestArchiveStatus: RequestArchiveStatus = RequestArchiveStatus.LOADING,
)


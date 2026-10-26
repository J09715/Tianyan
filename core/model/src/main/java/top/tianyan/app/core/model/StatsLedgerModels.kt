package top.tianyan.app.core.model

/**
 * 用量账本（`harness_usage`）在统计面板上的投影。
 *
 * 与 [StatsSummary] 并行展示、**口径不同**：summary 的 token 数是从正文长度估算的，
 * 这里全部来自账本落库的真实计量值。保留两套是因为估算口径覆盖了账本落库之前的历史数据，
 * 直接替换会让老会话用量凭空归零。
 *
 * 界面上是三层结构，与下面的字段一一对应：
 *  ① **作用域**（[StatsLedgerScopeDim]）—— 决定数字"属于谁"，由仓库按
 *     `modelId / provider / sessionId` 过滤后组装成这个快照；
 *  ② **取值**（[scopeOptions]）—— 作用域下的具体对象。取值列表**永远按未限定口径**统计，
 *     否则选中一个之后其余胶囊会消失、再也切不回去；
 *  ③ **分组方式**（[StatsLedgerGroupDim]）—— 同一批数字怎么拆。三种分组在快照里
 *     都已预计算（[byModel] / [byProvider] / [bySession]），切换分组不需要重新查库。
 */
data class StatsLedger(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    /** 已带定价的调用数；[estimatedCostUsd] 为 null 时说明当前没有任何模型配置过单价。 */
    val pricedCalls: Int = 0,
    val estimatedCostUsd: Double? = null,
    val scopeOptions: StatsLedgerScopeOptions = StatsLedgerScopeOptions(),
    val byModel: List<StatsLedgerGroupRow> = emptyList(),
    val byProvider: List<StatsLedgerGroupRow> = emptyList(),
    val bySession: List<StatsLedgerGroupRow> = emptyList(),
    val byBot: List<StatsLedgerGroupRow> = emptyList(),
    val recentCalls: List<StatsLedgerCallRow> = emptyList(),
    val trend: List<StatsTrendDay> = emptyList(),
) {
    /**
     * 计费口径的 token 合计。
     * 缓存读**不计入**：它是输入前缀的复用部分，计入会与 [inputTokens] 双重计算。
     */
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens

    /** 缓存命中率 = 命中读取量 ÷ (真实输入 + 命中读取量)。无可度量时为 0。 */
    val cacheHitRate: Double
        get() = (inputTokens + cacheReadTokens)
            .takeIf { it > 0L }
            ?.let { cacheReadTokens.toDouble() / it.toDouble() }
            ?: 0.0

    /** 按当前分组维度取预计算好的行。 */
    fun groupsOf(dim: StatsLedgerGroupDim): List<StatsLedgerGroupRow> = when (dim) {
        StatsLedgerGroupDim.MODEL -> byModel
        StatsLedgerGroupDim.PROVIDER -> byProvider
        StatsLedgerGroupDim.SESSION -> bySession
        StatsLedgerGroupDim.BOT -> byBot
    }
}

/**
 * 作用域维度：数字"属于谁"。工具维度**故意不做**——账本没落工具名，无法归属。
 * Bot 维度经 sessionId→botId 三路映射（私聊/群维度/独立任务会话）聚合。
 */
enum class StatsLedgerScopeDim { ALL, MODEL, PROVIDER, SESSION, BOT }

/** 分组方式：同一批数字"怎么拆"。与作用域正交。 */
enum class StatsLedgerGroupDim { MODEL, PROVIDER, SESSION, BOT }

/** 某一分组维度下的一行。 */
data class StatsLedgerGroupRow(
    val id: String,
    val label: String,
    val provider: String,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens
}

/** 作用域下的一个可选值。 */
data class StatsLedgerScopeOption(val id: String, val label: String)

/** 三种作用域各自的可选值集合。 */
data class StatsLedgerScopeOptions(
    val models: List<StatsLedgerScopeOption> = emptyList(),
    val providers: List<StatsLedgerScopeOption> = emptyList(),
    val sessions: List<StatsLedgerScopeOption> = emptyList(),
    val bots: List<StatsLedgerScopeOption> = emptyList(),
)

/** 账本逐次调用的明细行。 */
data class StatsLedgerCallRow(
    val id: String,
    val createdAt: Long,
    val label: String,
    val provider: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
)

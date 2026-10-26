package top.tianyan.app.ui.settings.stats

import top.tianyan.app.core.database.HarnessUsageEntity
import top.tianyan.app.core.database.UsageLedgerBotRow
import top.tianyan.app.core.database.UsageLedgerRow
import top.tianyan.app.core.database.UsageLedgerSessionRow
import top.tianyan.app.core.model.StatsLedger
import top.tianyan.app.core.model.StatsLedgerCallRow
import top.tianyan.app.core.model.StatsLedgerGroupRow
import top.tianyan.app.core.model.StatsLedgerScopeOption
import top.tianyan.app.core.model.StatsLedgerScopeOptions
import top.tianyan.app.core.model.StatsTokenBucket
import top.tianyan.app.core.model.StatsTrendDay
import java.time.LocalDate

/**
 * 用量账本快照的组装。
 *
 * 从 [StatsRepository] 抽出来的原因有两个：一是它让该文件逼近 400 行硬上限；
 * 二是这段逻辑只做纯内存归并（SQL 已在 HarnessUsageQueries 里聚合完），
 * 与 IO 无关，单独放更好读也更好测。
 *
 * 入参里的 `ledgerRows` / `ledgerSessionRows` 都是**已被作用域过滤**的结果，
 * 而 `baseDayRows` / `baseSessionRows` 始终是未限定口径——后者专供取值列表，
 * 否则选中一项后其余胶囊会消失、再也切不回去。
 */
internal fun assembleLedger(
    ledgerRows: List<UsageLedgerRow>,
    ledgerSessionRows: List<UsageLedgerSessionRow>,
    ledgerBotRows: List<UsageLedgerBotRow> = emptyList(),
    recentRows: List<HarnessUsageEntity>,
    baseDayRows: List<UsageLedgerRow>,
    baseSessionRows: List<UsageLedgerSessionRow>,
    baseBotRows: List<UsageLedgerBotRow> = emptyList(),
    trendStart: LocalDate,
    trendEnd: LocalDate,
    modelLabelOf: (String) -> String,
    providerLabelOf: (String, String) -> String,
    sessionLabelOf: (String) -> String,
    botLabelOf: (String) -> String = { "Bot " + it.take(8) },
): StatsLedger {
    var ledgerCalls = 0
    var ledgerInput = 0L
    var ledgerOutput = 0L
    var ledgerReasoning = 0L
    var ledgerCacheRead = 0L
    var ledgerCacheWrite = 0L
    val ledgerTrendBuckets = mutableMapOf<LocalDate, MutableMap<String, StatsTokenBucket>>()
    val modelBuckets = linkedMapOf<String, StatsLedgerGroupRow>()
    val providerBuckets = linkedMapOf<String, StatsLedgerGroupRow>()
    val sessionBuckets = linkedMapOf<String, StatsLedgerGroupRow>()
    val botBuckets = linkedMapOf<String, StatsLedgerGroupRow>()

    fun mergeGroup(
        buckets: MutableMap<String, StatsLedgerGroupRow>,
        key: String,
        label: String,
        provider: String,
        input: Long,
        output: Long,
        reasoning: Long,
        cacheRead: Long,
        cacheWrite: Long,
        calls: Int,
    ) {
        val merged = buckets[key]
        buckets[key] = StatsLedgerGroupRow(
            id = key,
            label = label,
            provider = provider,
            calls = (merged?.calls ?: 0) + calls,
            inputTokens = (merged?.inputTokens ?: 0L) + input,
            outputTokens = (merged?.outputTokens ?: 0L) + output,
            reasoningTokens = (merged?.reasoningTokens ?: 0L) + reasoning,
            cacheReadTokens = (merged?.cacheReadTokens ?: 0L) + cacheRead,
            cacheWriteTokens = (merged?.cacheWriteTokens ?: 0L) + cacheWrite,
        )
    }

    for (row in ledgerRows) {
        ledgerCalls += row.calls
        ledgerInput += row.inputTokens
        ledgerOutput += row.outputTokens
        ledgerReasoning += row.reasoningTokens
        ledgerCacheRead += row.cacheReadTokens
        ledgerCacheWrite += row.cacheWriteTokens

        val modelLabel = modelLabelOf(row.modelId)
        val providerLabel = providerLabelOf(row.provider, row.modelId)

        // 同一对象会分布在多个本地日桶里，按 key 累加成一行。
        mergeGroup(modelBuckets, row.modelId.ifBlank { providerLabel }, modelLabel, providerLabel,
            row.inputTokens, row.outputTokens, row.reasoningTokens,
            row.cacheReadTokens, row.cacheWriteTokens, row.calls)
        mergeGroup(providerBuckets, providerLabel, providerLabel, providerLabel,
            row.inputTokens, row.outputTokens, row.reasoningTokens,
            row.cacheReadTokens, row.cacheWriteTokens, row.calls)

        val day = runCatching { LocalDate.parse(row.localDay) }.getOrNull() ?: continue
        if (day.isBefore(trendStart) || day.isAfter(trendEnd)) continue
        val dayMap = ledgerTrendBuckets.getOrPut(day) { mutableMapOf() }
        dayMap[modelLabel] = (dayMap[modelLabel] ?: StatsTokenBucket()).add(
            input = row.inputTokens,
            output = row.outputTokens,
            cached = row.cacheReadTokens,
            // 推理与缓存写入归入 uncategorized，才能计入 totalTokens、参与柱高。
            uncategorized = row.reasoningTokens + row.cacheWriteTokens,
            activity = row.calls,
        )
    }

    for (row in ledgerSessionRows) {
        mergeGroup(sessionBuckets, row.sessionId, sessionLabelOf(row.sessionId),
            providerLabelOf(row.provider, row.modelId),
            row.inputTokens, row.outputTokens, row.reasoningTokens,
            row.cacheReadTokens, row.cacheWriteTokens, row.calls)
    }

    for (row in ledgerBotRows) {
        mergeGroup(botBuckets, row.botId, botLabelOf(row.botId),
            providerLabelOf(row.provider, row.modelId),
            row.inputTokens, row.outputTokens, row.reasoningTokens,
            row.cacheReadTokens, row.cacheWriteTokens, row.calls)
    }

    val ledgerTrend = ledgerTrendBuckets.entries
        .sortedBy { it.key }
        .map { (date, map) -> StatsTrendDay(date = date, providerTokens = map) }

    val ledgerRecentCalls = recentRows.map { row ->
        StatsLedgerCallRow(
            id = row.id,
            createdAt = row.createdAt,
            // 旁路问答（/btw）单独标注 kind，与普通轮次调用区分（DS Bot 用量页语义）。
            label = modelLabelOf(row.modelId.orEmpty()) + (if (row.operationId == "btw") " · 旁路问答" else ""),
            provider = providerLabelOf(row.provider.orEmpty(), row.modelId.orEmpty()),
            inputTokens = row.inputTokens,
            outputTokens = row.outputTokens,
            reasoningTokens = row.reasoningTokens,
            cacheReadTokens = row.cacheReadTokens,
            cacheWriteTokens = row.cacheWriteTokens,
        )
    }

    // 成本一律留空：estimatedCostUsd 需要模型定价表支撑，当前没有任何定价来源，
    // 宁可显式呈现「未计算」，也不给一个看起来像真的估算数字。
    val ledger = StatsLedger(
        calls = ledgerCalls,
        inputTokens = ledgerInput,
        outputTokens = ledgerOutput,
        reasoningTokens = ledgerReasoning,
        cacheReadTokens = ledgerCacheRead,
        cacheWriteTokens = ledgerCacheWrite,
        pricedCalls = 0,
        estimatedCostUsd = null,
        scopeOptions = StatsLedgerScopeOptions(
            models = baseDayRows.groupBy { it.modelId }.entries
                .map { (id, rows) -> StatsLedgerScopeOption(id, modelLabelOf(id)) to rows.sumOf { it.calls } }
                .sortedByDescending { it.second }.map { it.first },
            providers = baseDayRows.groupBy { it.provider }.entries
                .map { (id, rows) -> StatsLedgerScopeOption(id, id.ifBlank { "内置" }) to rows.sumOf { it.calls } }
                .sortedByDescending { it.second }.map { it.first },
            sessions = baseSessionRows.groupBy { it.sessionId }.entries
                .map { (id, rows) -> StatsLedgerScopeOption(id, sessionLabelOf(id)) to rows.sumOf { it.calls } }
                .sortedByDescending { it.second }.map { it.first },
            bots = baseBotRows.groupBy { it.botId }.entries
                .map { (id, rows) -> StatsLedgerScopeOption(id, botLabelOf(id)) to rows.sumOf { it.calls } }
                .sortedByDescending { it.second }.map { it.first },
        ),
        byModel = modelBuckets.values.sortedByDescending { it.totalTokens }.take(20),
        byProvider = providerBuckets.values.sortedByDescending { it.totalTokens }.take(20),
        bySession = sessionBuckets.values.sortedByDescending { it.totalTokens }.take(20),
        byBot = botBuckets.values.sortedByDescending { it.totalTokens }.take(20),
        recentCalls = ledgerRecentCalls,
        trend = ledgerTrend,
    )

    return ledger
}

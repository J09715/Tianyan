package top.tianyan.app.ui.settings.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.datastore.AppStatsPreferences
import top.tianyan.app.core.model.StatsDateRange
import top.tianyan.app.core.model.StatsHeatmapDay
import top.tianyan.app.core.model.StatsLedger
import top.tianyan.app.core.model.StatsLedgerScopeDim
import top.tianyan.app.core.model.StatsLedgerScopeOption
import top.tianyan.app.core.model.StatsSnapshot
import top.tianyan.app.core.model.StatsSummary
import top.tianyan.app.core.model.StatsTokenBucket
import top.tianyan.app.core.model.StatsTrendDay
import java.time.LocalDate
import java.time.ZoneId

class StatsRepository(
    private val runtimeRepository: HarnessRuntimeRepository,
    private val sessionRepository: HarnessSessionRepository,
    private val aiModelRepository: AiModelRepository,
    private val appStatsPreferences: AppStatsPreferences,
    /** Bot 维度报表（DS Bot 用量页）：会话→Bot 映射与 Bot 名册。 */
    private val botRepository: top.tianyan.app.core.database.repository.PersonaBotRepository? = null,
    private val groupSessions: top.tianyan.app.core.database.repository.BotGroupSessionRepository? = null,
    private val assignments: top.tianyan.app.core.database.repository.BotAssignmentRepository? = null,
) {
    /** Bot 名下全部会话（私聊 + 群维度 + 独立任务）；无 Bot 数据源时返回空。 */
    private suspend fun sessionIdsOfBot(botId: String): List<String> {
        val bot = botRepository?.getBot(botId) ?: return emptyList()
        return buildList {
            add(bot.sessionId)
            groupSessions?.listSessionIdsForBot(botId)?.let(::addAll)
            addAll(
                assignments?.assignmentsForBot(botId).orEmpty()
                    .filter { it.independent }.mapNotNull { it.sessionId },
            )
        }.distinct()
    }
    suspend fun buildSnapshot(
        range: StatsDateRange,
        scopeDim: StatsLedgerScopeDim = StatsLedgerScopeDim.ALL,
        scopeValue: String? = null,
        now: LocalDate = LocalDate.now(),
    ): StatsSnapshot = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        // 与 SQL 日切共用同一偏移，避免 CAST(createdAt/86400000) 按 UTC 日切、本地午夜错桶。
        val tzOffsetMs = zone.rules.getOffset(now.atStartOfDay(zone).toInstant()).totalSeconds * 1000L
        val startEpochMs = range.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val endEpochMs = range.end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

        // 1. 基础汇总
        val totalSessions = sessionRepository.countInRange(startEpochMs, endEpochMs)
        val totalMessages = runtimeRepository.countEntriesInRange(startEpochMs, endEpochMs)
        val launchCount = appStatsPreferences.appLaunchCount.first()

        // 2. 所有模型与会话缓存
        val allModels = aiModelRepository.observeAll().first().associateBy { it.id }
        val allSessions = sessionRepository.listAll().associateBy { it.id }

        // 3. 用量聚合 —— 关键改动（2026-09-14 OOM 修复）
        //
        // 原实现：runtimeRepository.listEntriesInRange(startEpochMs, endEpochMs) 把区间内**全部**
        // harness_entries（含完整 payloadJson）读进内存，再对每条 json.parseToJsonElement 建树。
        // 实测库内 15,888 条（tool_result 5,696 条，单体可达数十 KB~数 MB），
        // 选「全部时间」时直接把 256MB 堆打爆 → java.lang.OutOfMemoryError。
        //
        // 现改为 SQL 层聚合：json_extract 在 SQLite 侧取标量、按 (sessionId, customType) GROUP BY，
        // 1.5 万行压缩成几十行小结果返回，Kotlin 侧不再接触 payloadJson 本体。
        val usageRows = runtimeRepository.aggregateUsageInRange(startEpochMs, endEpochMs)

        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var totalCachedTokens = 0L

        val modelUsageCounts = mutableMapOf<String, Int>()
        val providerUsageCounts = mutableMapOf<String, Int>()
        val assistantUsageCounts = mutableMapOf<String, Int>()

        // 趋势图聚合桶
        val trendStart = if (range.isAllTime) now.minusDays(29) else (range.start ?: now.minusDays(29))
        val trendEnd = if (range.isAllTime) now else (range.end ?: now)
        val trendBuckets = mutableMapOf<LocalDate, MutableMap<String, StatsTokenBucket>>()

        var dayCursor = trendStart
        while (!dayCursor.isAfter(trendEnd)) {
            trendBuckets[dayCursor] = mutableMapOf()
            dayCursor = dayCursor.plusDays(1)
        }

        // 话题会话排行用的每会话累计条数（由聚合行累加，无需原始消息）
        val sessionEntryCounts = mutableMapOf<String, Int>()

        for (row in usageRows) {
            val session = allSessions[row.sessionId]
            val sessionModelId = session?.modelId
            val modelEntity = sessionModelId?.let { allModels[it] }

            val providerName = modelEntity?.provider?.ifBlank { "默认" } ?: "内置"
            val modelName = modelEntity?.name ?: sessionModelId ?: "通用助手"

            // 统计助手/工作区维度
            val workspaceLabel = session?.workspace?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')?.ifBlank { null }
                ?: session?.title?.takeIf { it.isNotBlank() }
                ?: "默认工程"
            assistantUsageCounts[workspaceLabel] = (assistantUsageCounts[workspaceLabel] ?: 0) + row.entryCount
            sessionEntryCounts[row.sessionId] = (sessionEntryCounts[row.sessionId] ?: 0) + row.entryCount

            val tokens = tokenContributionFromAggregate(
                customType = row.customType,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                cachedTokens = row.cachedTokens,
                textChars = row.textChars,
                reasoningChars = row.reasoningChars,
            )
            totalInputTokens += tokens.input
            totalOutputTokens += tokens.output
            totalCachedTokens += tokens.cached

            when (row.customType) {
                "assistant" -> {
                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + row.entryCount
                    providerUsageCounts[providerName] = (providerUsageCounts[providerName] ?: 0) + row.entryCount
                }
                "tool_call" -> {
                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + row.entryCount
                }
            }
        }

        // 趋势桶：按本地日聚合 token（与区间汇总同一套 json_extract 标量，不取 payloadJson）
        val dailyRows = runtimeRepository.aggregateDailyCounts(startEpochMs, endEpochMs, tzOffsetMs)
        for (row in dailyRows) {
            val msgDate = LocalDate.ofEpochDay(row.localEpochDay)
            if (msgDate.isBefore(trendStart) || msgDate.isAfter(trendEnd)) continue
            val session = allSessions[row.sessionId]
            val providerName = session?.modelId?.let { allModels[it] }?.provider?.ifBlank { "默认" } ?: "内置"
            val dayMap = trendBuckets.getOrPut(msgDate) { mutableMapOf() }
            val bucket = dayMap[providerName] ?: StatsTokenBucket()
            val tokens = tokenContributionFromAggregate(
                customType = row.customType,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                cachedTokens = row.cachedTokens,
                textChars = row.textChars,
                reasoningChars = row.reasoningChars,
            )
            dayMap[providerName] = bucket.add(
                input = tokens.input,
                output = tokens.output,
                cached = tokens.cached,
                activity = row.entryCount,
            )
        }

        // 4. 热力图（近 180 天打卡矩阵）—— 同样用按本地日聚合，不加载原始条目
        val heatmapStart = now.minusDays(180)
        val heatmapStartEpoch = heatmapStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val heatmapStartEpochDay = heatmapStart.toEpochDay()
        val rawHeatmapRows = runtimeRepository.aggregateDailyCounts(heatmapStartEpoch, null, tzOffsetMs)
            .filter { it.localEpochDay >= heatmapStartEpochDay }
            .groupingBy { LocalDate.ofEpochDay(it.localEpochDay).toString() }
            .fold(0) { acc, item -> acc + item.entryCount }

        val heatmapDays = mutableListOf<StatsHeatmapDay>()
        var hCursor = now.minusDays(180)
        while (!hCursor.isAfter(now)) {
            val dateStr = hCursor.toString()
            val count = rawHeatmapRows[dateStr] ?: 0
            heatmapDays.add(StatsHeatmapDay(date = hCursor, count = count))
            hCursor = hCursor.plusDays(1)
        }

        // 5~7. 三个排行分区已按要求从界面下线，这里不再构建列表（原先每轮都在算却无人消费）。

        // 8. 趋势数据组装
        val trendList = trendBuckets.entries.map { (date, map) ->
            StatsTrendDay(date = date, providerTokens = map)
        }

        // 9. 用量账本（权威口径）—— harness_usage 里逐次 LLM 调用的真实计量值。
        //
        // 与第 1~8 步的估算口径并行存在，而不是替换：估算口径覆盖了账本落库之前的历史数据，
        // 直接替换会让老会话用量凭空归零。它额外补上推理 token、缓存读写分列，
        // 以及按 模型 / 供应商 / 会话 的归因。
        //
        // 三层：① 作用域决定数字"属于谁"；② 取值列表恒按未限定口径统计，否则选中一项后
        // 其余胶囊会消失、再也切不回去；③ 三种分组在这里一次性预计算，界面切分组不查库。
        val scopeModelId = scopeValue.takeIf { scopeDim == StatsLedgerScopeDim.MODEL }
        val scopeProvider = scopeValue.takeIf { scopeDim == StatsLedgerScopeDim.PROVIDER }
        val scopeSessionId = scopeValue.takeIf { scopeDim == StatsLedgerScopeDim.SESSION }
        val scopeBotId = scopeValue.takeIf { scopeDim == StatsLedgerScopeDim.BOT }
        val scopeBotSessionIds = scopeBotId?.let { sessionIdsOfBot(it) }.orEmpty()

        val baseDayRows = runtimeRepository.aggregateUsageLedger(startEpochMs, endEpochMs, tzOffsetMs, null, null, null)
        // 按会话聚合不做日切，因此不需要时区偏移参数。
        val baseSessionRows = runtimeRepository.aggregateUsageLedgerBySession(startEpochMs, endEpochMs, null, null, null)
        val baseBotRows = botRepository?.let {
            runtimeRepository.aggregateUsageLedgerByBot(startEpochMs, endEpochMs, null)
        }.orEmpty()

        // 按会话/按 Bot 作用域必须走 SQL 过滤（按日聚合行里没有 sessionId）；
        // 模型/供应商在内存里筛即可。
        val ledgerRows = when {
            scopeSessionId != null -> runtimeRepository.aggregateUsageLedger(
                startEpochMs, endEpochMs, tzOffsetMs, scopeModelId, scopeProvider, scopeSessionId,
            )
            scopeBotId != null -> runtimeRepository.aggregateUsageLedgerForSessions(
                startEpochMs, endEpochMs, tzOffsetMs, scopeBotSessionIds,
            )
            else -> baseDayRows.filter { row ->
                (scopeModelId == null || row.modelId == scopeModelId) &&
                    (scopeProvider == null || row.provider == scopeProvider)
            }
        }
        val ledgerSessionRows = baseSessionRows.filter { row ->
            (scopeModelId == null || row.modelId == scopeModelId) &&
                (scopeProvider == null || row.provider == scopeProvider) &&
                (scopeSessionId == null || row.sessionId == scopeSessionId) &&
                (scopeBotId == null || row.sessionId in scopeBotSessionIds)
        }
        // Bot 分组行：会话作用域经 SQL 过滤；模型/供应商在内存筛；Bot 作用域按会话集过滤。
        val ledgerBotRows = runtimeRepository.aggregateUsageLedgerByBot(startEpochMs, endEpochMs, scopeSessionId)
            .filter { row ->
                (scopeModelId == null || row.modelId == scopeModelId) &&
                    (scopeProvider == null || row.provider == scopeProvider) &&
                    (scopeBotId == null || row.botId == scopeBotId)
            }
        val ledgerRecentRows = if (scopeBotId != null) {
            runtimeRepository.listRecentUsageForSessions(startEpochMs, endEpochMs, 40, scopeBotSessionIds)
        } else {
            runtimeRepository.listRecentUsage(
                startEpochMs, endEpochMs, 40, scopeModelId, scopeProvider, scopeSessionId,
            )
        }

        fun modelLabelOf(modelId: String): String =
            allModels[modelId]?.name ?: modelId.ifBlank { "未标注模型" }

        fun providerLabelOf(provider: String, modelId: String): String =
            allModels[modelId]?.provider?.ifBlank { "默认" } ?: provider.ifBlank { "内置" }

        fun sessionLabelOf(sessionId: String): String =
            allSessions[sessionId]?.title?.ifBlank { "未命名会话" } ?: "会话 " + sessionId.take(8)

        val botsById = botRepository?.listBots().orEmpty().associateBy { it.id }
        fun botLabelOf(botId: String): String =
            botsById[botId]?.let { "${it.name}（${it.role}）" } ?: "Bot " + botId.take(8)

        val ledger = assembleLedger(
            ledgerRows = ledgerRows,
            ledgerSessionRows = ledgerSessionRows,
            ledgerBotRows = ledgerBotRows,
            recentRows = ledgerRecentRows,
            baseDayRows = baseDayRows,
            baseSessionRows = baseSessionRows,
            baseBotRows = baseBotRows,
            trendStart = trendStart,
            trendEnd = trendEnd,
            modelLabelOf = { modelLabelOf(it) },
            providerLabelOf = { provider, modelId -> providerLabelOf(provider, modelId) },
            sessionLabelOf = { sessionLabelOf(it) },
            botLabelOf = { botLabelOf(it) },
        )

        StatsSnapshot(
            range = range,
            summary = StatsSummary(
                totalConversations = totalSessions,
                totalMessages = totalMessages,
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cachedTokens = totalCachedTokens,
                launchCount = launchCount,
            ),
            heatmap = heatmapDays,
            trend = trendList,
            modelRank = emptyList(),
            assistantRank = emptyList(),
            topicRank = emptyList(),
            ledger = ledger,
        )
    }

}

internal data class AggregateTokenContribution(
    val input: Long,
    val output: Long,
    val cached: Long,
)

/**
 * 把 SQL 聚合行转成与区间汇总相同的 input/output/cached 口径。
 * 趋势图按天桶必须走这条路径，否则只有 activityCount、totalTokens 恒为 0。
 */
internal fun tokenContributionFromAggregate(
    customType: String?,
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    textChars: Long,
    reasoningChars: Long,
): AggregateTokenContribution = when (customType) {
    "user" -> AggregateTokenContribution(
        input = estimateTokensFromChars(textChars),
        output = 0L,
        cached = 0L,
    )
    "assistant" -> AggregateTokenContribution(
        input = promptTokens,
        output = if (completionTokens > 0L) {
            completionTokens
        } else {
            estimateTokensFromChars(textChars + reasoningChars)
        },
        cached = cachedTokens,
    )
    else -> AggregateTokenContribution(0L, 0L, 0L)
}

/**
 * 由「字符数」估算 token —— 供 SQL 聚合路径使用。
 *
 * 原实现按字符逐个数权重（CJK 记 2、ASCII 记 1）再乘 0.75；SQL 侧 LENGTH() 只能拿到字符数、
 * 拿不到原文，故这里直接用字符数近似：按 1 字符 ≈ 1 权重保守估计，再乘同一个 0.75 系数，
 * 保证与逐字符版本同量级、不产生数量级偏差。
 */
internal fun estimateTokensFromChars(chars: Long): Long {
    if (chars <= 0L) return 0L
    return (chars * 0.75).toLong().coerceAtLeast(1L)
}

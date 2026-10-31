package top.tianyan.app.core.database

import androidx.room.Query

/**
 * `harness_usage` 账本的只读聚合查询。
 *
 * `harness_usage` 是 append-only 的计费账本，逐次 LLM 调用落一行，承载**真实计量值**
 * （inputTokens / outputTokens / reasoningTokens / cacheReadTokens / cacheWriteTokens /
 * provider / modelId），而不是从正文长度反推的估算值。
 *
 * 统计界面此前完全没读这张表：它从 `harness_entries.payloadJson` 里 `json_extract`
 * 抠标量再用字符数估 token，于是推理 token、缓存读写分列、按模型/供应商/会话归因
 * 这三类信息在界面上根本不存在，总量也只是估算。
 *
 * 聚合全部压在 SQLite 侧（延续 2026-09-14 OOM 事故后的口径，Kotlin 侧只接触标量）。
 * 三种「作用域」过滤参数都可选：传 null 表示该维度不限定；三个都传 null 即整体口径。
 *
 * 注意：**故意不落工具维度**。账本里没有工具名字段，无法归属到具体工具，
 * 硬造一个空维度只会误导读者；需要工具维度时应当先改写入侧采集。
 */
interface HarnessUsageQueries {

    /**
     * 按 (本地日, provider, model) 聚合，用于趋势、总量与模型/供应商拆分。
     *
     * [tzOffsetMs] 与 StatsRepository 使用的 ZoneId 偏移一致，避免 UTC 日切把本地午夜前后错桶。
     * 修正行（`adjustment = true`）在 SQL 内剔除；`SUM` 外包 `COALESCE` 保证非空。
     * `sessionId` 只作为过滤条件、不进分组，否则会把行数放大到「天 × 会话 × 模型」量级。
     */
    @Query("""
        SELECT
            strftime('%Y-%m-%d', (createdAt + :tzOffsetMs) / 1000, 'unixepoch') AS localDay,
            COALESCE(provider, '') AS provider,
            COALESCE(modelId, '') AS modelId,
            COALESCE(SUM(inputTokens), 0) AS inputTokens,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COALESCE(SUM(cacheReadTokens), 0) AS cacheReadTokens,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(*) AS calls
        FROM harness_usage
        WHERE adjustment = 0
          AND (:start IS NULL OR createdAt >= :start)
          AND (:end IS NULL OR createdAt < :end)
          AND (:modelId IS NULL OR modelId = :modelId)
          AND (:provider IS NULL OR provider = :provider)
          AND (:sessionId IS NULL OR sessionId = :sessionId)
        GROUP BY
            strftime('%Y-%m-%d', (createdAt + :tzOffsetMs) / 1000, 'unixepoch'),
            provider,
            modelId
        ORDER BY localDay
    """)
    suspend fun aggregateLedgerByDay(
        start: Long?,
        end: Long?,
        tzOffsetMs: Long,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerRow>

    /**
     * 按 (sessionId, provider, model) 聚合，用于会话维度的拆分与取值列表。
     * 行数量级是「会话 × 模型」，通常几十行，可以安全地整体读入。
     */
    @Query("""
        SELECT
            COALESCE(sessionId, '') AS sessionId,
            COALESCE(provider, '') AS provider,
            COALESCE(modelId, '') AS modelId,
            COALESCE(SUM(inputTokens), 0) AS inputTokens,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COALESCE(SUM(cacheReadTokens), 0) AS cacheReadTokens,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(*) AS calls
        FROM harness_usage
        WHERE adjustment = 0
          AND (:start IS NULL OR createdAt >= :start)
          AND (:end IS NULL OR createdAt < :end)
          AND (:modelId IS NULL OR modelId = :modelId)
          AND (:provider IS NULL OR provider = :provider)
          AND (:sessionId IS NULL OR sessionId = :sessionId)
        GROUP BY sessionId, provider, modelId
    """)
    suspend fun aggregateLedgerBySession(
        start: Long?,
        end: Long?,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerSessionRow>

    /** 账本最近若干次调用（倒序），供明细下钻使用。 */
    @Query("""
        SELECT * FROM harness_usage
        WHERE adjustment = 0
          AND (:start IS NULL OR createdAt >= :start)
          AND (:end IS NULL OR createdAt < :end)
          AND (:modelId IS NULL OR modelId = :modelId)
          AND (:provider IS NULL OR provider = :provider)
          AND (:sessionId IS NULL OR sessionId = :sessionId)
        ORDER BY sequence DESC
        LIMIT :limit
    """)
    suspend fun listRecentUsage(
        start: Long?,
        end: Long?,
        limit: Int,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<HarnessUsageEntity>

    /**
     * 按 (botId, provider, model) 聚合：Bot 维度报表（DS Bot 用量页语义）。
     * sessionId→botId 经三路映射（私聊会话 / 群维度会话 / 独立任务会话）在 SQL 侧完成；
     * `:sessionId` 只作过滤（如按会话作用域下钻时），不进分组。
     */
    @Query("""
        SELECT
            m.botId AS botId,
            COALESCE(u.provider, '') AS provider,
            COALESCE(u.modelId, '') AS modelId,
            COALESCE(SUM(u.inputTokens), 0) AS inputTokens,
            COALESCE(SUM(u.outputTokens), 0) AS outputTokens,
            COALESCE(SUM(u.reasoningTokens), 0) AS reasoningTokens,
            COALESCE(SUM(u.cacheReadTokens), 0) AS cacheReadTokens,
            COALESCE(SUM(u.cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(*) AS calls
        FROM harness_usage u
        JOIN (
            SELECT sessionId, id AS botId FROM persona_bots
            UNION ALL SELECT sessionId, botId FROM bot_group_sessions
            UNION ALL SELECT sessionId, botId FROM bot_assignments WHERE independent = 1
        ) m ON u.sessionId = m.sessionId
        WHERE u.adjustment = 0
          AND (:start IS NULL OR u.createdAt >= :start)
          AND (:end IS NULL OR u.createdAt < :end)
          AND (:sessionId IS NULL OR u.sessionId = :sessionId)
        GROUP BY m.botId, u.provider, u.modelId
    """)
    suspend fun aggregateLedgerByBot(
        start: Long?,
        end: Long?,
        sessionId: String?,
    ): List<UsageLedgerBotRow>

    /** Bot 作用域下的按日聚合：sessionIds 为空时由调用方短路，不下发 SQL。 */
    @Query("""
        SELECT
            strftime('%Y-%m-%d', (createdAt + :tzOffsetMs) / 1000, 'unixepoch') AS localDay,
            COALESCE(provider, '') AS provider,
            COALESCE(modelId, '') AS modelId,
            COALESCE(SUM(inputTokens), 0) AS inputTokens,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COALESCE(SUM(cacheReadTokens), 0) AS cacheReadTokens,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(*) AS calls
        FROM harness_usage
        WHERE adjustment = 0
          AND (:start IS NULL OR createdAt >= :start)
          AND (:end IS NULL OR createdAt < :end)
          AND sessionId IN (:sessionIds)
        GROUP BY localDay, provider, modelId
        ORDER BY localDay
    """)
    suspend fun aggregateUsageLedgerForSessions(
        start: Long?,
        end: Long?,
        tzOffsetMs: Long,
        sessionIds: List<String>,
    ): List<UsageLedgerRow>

    /** Bot 作用域下的最近调用明细；sessionIds 为空时由调用方短路。 */
    @Query("""
        SELECT * FROM harness_usage
        WHERE adjustment = 0
          AND (:start IS NULL OR createdAt >= :start)
          AND (:end IS NULL OR createdAt < :end)
          AND sessionId IN (:sessionIds)
        ORDER BY sequence DESC
        LIMIT :limit
    """)
    suspend fun listRecentUsageForSessions(
        start: Long?,
        end: Long?,
        limit: Int,
        sessionIds: List<String>,
    ): List<HarnessUsageEntity>
}

/** 账本按 (本地日, provider, model) 聚合后的投影行；字段全部为 SQLite 侧标量。 */
data class UsageLedgerRow(
    val localDay: String,
    val provider: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val calls: Int,
)

/** 账本按 (sessionId, provider, model) 聚合后的投影行。 */
data class UsageLedgerSessionRow(
    val sessionId: String,
    val provider: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val calls: Int,
)

/** 账本按 (botId, provider, model) 聚合后的投影行（Bot 维度报表）。 */
data class UsageLedgerBotRow(
    val botId: String,
    val provider: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val calls: Int,
)

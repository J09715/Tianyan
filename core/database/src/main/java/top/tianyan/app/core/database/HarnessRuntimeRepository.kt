package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Persistence port used by the harness runtime; feature modules never depend on its DAO. */
interface HarnessRuntimeRepository {
    suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String? = null): HarnessLaneEntity
    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?
    fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>>
    suspend fun listEntries(sessionId: String): List<HarnessEntryEntity>
    suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity>
    suspend fun countEntriesInRange(start: Long?, end: Long?): Int

    /**
     * 用量统计用：按 (sessionId, customType) 聚合，不把 payloadJson 带进内存。
     * 见 HarnessRuntimeDao.aggregateUsageInRange 的说明（2026-09-14 OOM 事故）。
     */
    suspend fun aggregateUsageInRange(start: Long?, end: Long?): List<UsageAggregateRow>

    /**
     * 用量统计用：按 (本地日, sessionId, customType) 聚合条目数与 token 标量。
     * [tzOffsetMs] 与 StatsRepository 使用的 [java.time.ZoneId] 偏移一致。
     */
    suspend fun aggregateDailyCounts(start: Long?, end: Long?, tzOffsetMs: Long): List<DailyCountRow>

    /**
     * 用量统计用：账本按 (本地日, provider, model) 聚合。
     * 与 [aggregateUsageInRange] 不同，这里读的是真实计量值、能给出推理 token 与缓存读写分列；
     * 三个过滤参数均可选，传 null 表示该维度不限定，全传 null 即整体口径。
     */
    suspend fun aggregateUsageLedger(
        start: Long?,
        end: Long?,
        tzOffsetMs: Long,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerRow>

    /** 用量统计用：账本按 (sessionId, provider, model) 聚合，服务会话维度的拆分与取值列表。 */
    suspend fun aggregateUsageLedgerBySession(
        start: Long?,
        end: Long?,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerSessionRow>

    /** 用量统计用：账本最近若干次调用明细（倒序），供界面下钻。 */
    suspend fun listRecentUsage(
        start: Long?,
        end: Long?,
        limit: Int,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<HarnessUsageEntity>

    /** 账本按 (botId, provider, model) 聚合，服务 Bot 维度报表与取值列表。 */
    suspend fun aggregateUsageLedgerByBot(start: Long?, end: Long?, sessionId: String?): List<UsageLedgerBotRow>

    /** Bot 作用域下的按日聚合（sessionIds 为空时返回空，不下发 SQL）。 */
    suspend fun aggregateUsageLedgerForSessions(start: Long?, end: Long?, tzOffsetMs: Long, sessionIds: List<String>): List<UsageLedgerRow>

    /** Bot 作用域下的最近调用明细；sessionIds 为空时返回空。 */
    suspend fun listRecentUsageForSessions(start: Long?, end: Long?, limit: Int, sessionIds: List<String>): List<HarnessUsageEntity>
    suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity>
    suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> {
        require(limit > 0) { "Branch tail limit must be positive" }
        return branch(sessionId, leafId).takeLast(limit)
    }
    suspend fun branchWindow(sessionId: String, leafId: String?, minSequence: Long): List<HarnessEntryEntity> =
        branch(sessionId, leafId).filter { it.sequence >= minSequence || it.entryType == "recall_context" }
    suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String?,
        entryType: String,
    ): HarnessEntryEntity? = branch(sessionId, leafId).lastOrNull { it.entryType == entryType }
    suspend fun searchBranch(sessionId: String, leafId: String?, query: String, limit: Int): List<HarnessEntryEntity> =
        branch(sessionId, leafId).asReversed().filter { it.entryType == "message" && it.payloadJson.contains(query, ignoreCase = true) }.take(limit)
    suspend fun branchEntryAt(sessionId: String, leafId: String?, index: Int): HarnessEntryEntity? =
        branch(sessionId, leafId).filter { it.entryType == "message" }.getOrNull(index)
    suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? =
        listEntries(sessionId).firstOrNull { it.id == entryId }
    suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity)
    suspend fun moveLane(sessionId: String, laneName: String, leafId: String?)
    suspend fun clearLaneOperation(sessionId: String, laneName: String)
    suspend fun findOperation(operationId: String): HarnessOperationEntity?
    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>
    suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    /** Atomically claims a queued task with its full input and operation, including queue consumption. */
    suspend fun acceptTaskOperation(taskId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, queueItemId: String? = null) {
        error("Atomic task admission is not supported by this repository")
    }
    /** Ends a leftover run and starts its successor, with optional input, in one transaction. */
    suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
        previousResult: HarnessLaneResultEntity?, taskId: String?) {
        error("Atomic run takeover is not supported by this repository")
    }
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun saveOperation(operation: HarnessOperationEntity)
    suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity)
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity)
    suspend fun completeTaskOperation(taskId: String, result: HarnessLaneResultEntity,
        expectedLane: HarnessLaneEntity, delivery: HarnessEntryEntity) {
        error("Atomic task delivery is not supported by this repository")
    }
    suspend fun enqueue(item: HarnessQueueItemEntity)
    suspend fun listQueue(sessionId: String, laneName: String, queueType: String): List<HarnessQueueItemEntity>
    suspend fun listAllQueues(sessionId: String, laneName: String): List<HarnessQueueItemEntity>
    suspend fun cancelQueued(itemId: String)
    suspend fun clearQueue(sessionId: String, laneName: String, queueType: String)
    /** Moves every item of [fromType] to [toType] without rewriting payload or createdAt. */
    suspend fun retargetQueue(sessionId: String, laneName: String, fromType: String, toType: String): Int =
        error("Queue retarget is not supported by this repository")
    suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity)
    suspend fun recordUsage(usage: HarnessUsageEntity)
    suspend fun listUsage(sessionId: String): List<HarnessUsageEntity>
    suspend fun deleteSessionData(sessionId: String)
}

class RoomHarnessRuntimeRepository(
    private val dao: HarnessRuntimeDao,
    private val blobStore: HarnessBlobStore? = null,
) : HarnessRuntimeRepository {

    private fun sanitizeForStorage(entry: HarnessEntryEntity): HarnessEntryEntity {
        val store = blobStore ?: return entry
        val processed = store.storeIfLarge(entry.sessionId, entry.id, entry.payloadJson)
        return if (processed === entry.payloadJson) entry else entry.copy(payloadJson = processed)
    }

    private fun restoreFromStorage(entry: HarnessEntryEntity): HarnessEntryEntity {
        val store = blobStore ?: return entry
        val restored = store.read(entry.payloadJson)
        return if (restored === entry.payloadJson) entry else entry.copy(payloadJson = restored)
    }

    override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity {
        dao.findLane(sessionId, laneName)?.let { return it }
        if (atEntryId != null) {
            require(dao.findEntry(atEntryId)?.sessionId == sessionId) { "Lane anchor does not belong to session $sessionId" }
        }
        val lane = HarnessLaneEntity(
            sessionId = sessionId,
            name = laneName,
            leafId = atEntryId,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertLane(lane)
        return lane
    }

    override suspend fun findLane(sessionId: String, laneName: String) = dao.findLane(sessionId, laneName)
    override fun observeLanes(sessionId: String) = dao.observeLanes(sessionId)
    override suspend fun listEntries(sessionId: String): List<HarnessEntryEntity> =
        dao.listEntries(sessionId).map(::restoreFromStorage)
    override suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity> =
        dao.listEntriesInRange(start, end).map(::restoreFromStorage)
    override suspend fun countEntriesInRange(start: Long?, end: Long?) = dao.countEntriesInRange(start, end)

    override suspend fun aggregateUsageInRange(start: Long?, end: Long?): List<UsageAggregateRow> =
        // 该查询只返回标量聚合值，不涉及 payloadJson 实体，故不做 restoreFromStorage。
        dao.aggregateUsageInRange(start, end)

    override suspend fun aggregateDailyCounts(start: Long?, end: Long?, tzOffsetMs: Long): List<DailyCountRow> =
        dao.aggregateDailyCounts(start, end, tzOffsetMs)

    override suspend fun aggregateUsageLedger(
        start: Long?,
        end: Long?,
        tzOffsetMs: Long,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerRow> =
        // 纯标量聚合，不涉及 payloadJson 实体，因此不做 restoreFromStorage。
        dao.aggregateLedgerByDay(start, end, tzOffsetMs, modelId, provider, sessionId)

    override suspend fun aggregateUsageLedgerBySession(
        start: Long?,
        end: Long?,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<UsageLedgerSessionRow> =
        dao.aggregateLedgerBySession(start, end, modelId, provider, sessionId)

    override suspend fun listRecentUsage(
        start: Long?,
        end: Long?,
        limit: Int,
        modelId: String?,
        provider: String?,
        sessionId: String?,
    ): List<HarnessUsageEntity> =
        dao.listRecentUsage(start, end, limit, modelId, provider, sessionId)

    override suspend fun aggregateUsageLedgerByBot(start: Long?, end: Long?, sessionId: String?) =
        dao.aggregateLedgerByBot(start, end, sessionId)

    override suspend fun aggregateUsageLedgerForSessions(start: Long?, end: Long?, tzOffsetMs: Long, sessionIds: List<String>): List<UsageLedgerRow> =
        if (sessionIds.isEmpty()) emptyList() else dao.aggregateUsageLedgerForSessions(start, end, tzOffsetMs, sessionIds)

    override suspend fun listRecentUsageForSessions(start: Long?, end: Long?, limit: Int, sessionIds: List<String>): List<HarnessUsageEntity> =
        if (sessionIds.isEmpty()) emptyList() else dao.listRecentUsageForSessions(start, end, limit, sessionIds)

    override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
        return leafId?.let { dao.branch(sessionId, it).map(::restoreFromStorage) }.orEmpty()
    }
    override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> {
        require(limit > 0) { "Branch tail limit must be positive" }
        return leafId?.let { dao.branchTail(sessionId, it, limit).map(::restoreFromStorage) }.orEmpty()
    }
    override suspend fun branchWindow(sessionId: String, leafId: String?, minSequence: Long): List<HarnessEntryEntity> {
        return leafId?.let { dao.branchWindow(sessionId, it, minSequence).map(::restoreFromStorage) }.orEmpty()
    }
    override suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String?,
        entryType: String,
    ): HarnessEntryEntity? = leafId?.let {
        dao.latestBranchEntryOfType(sessionId, it, entryType)?.let(::restoreFromStorage)
    }
    override suspend fun searchBranch(sessionId: String, leafId: String?, query: String, limit: Int): List<HarnessEntryEntity> =
        leafId?.let {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            dao.searchBranch(sessionId, it, escaped, limit).map(::restoreFromStorage)
        }.orEmpty()
    override suspend fun branchEntryAt(sessionId: String, leafId: String?, index: Int): HarnessEntryEntity? =
        leafId?.let { dao.branchEntryAt(sessionId, it, index)?.let(::restoreFromStorage) }
    private suspend fun ensureUniqueStorageEntry(entry: HarnessEntryEntity): HarnessEntryEntity {
        var candidate = entry
        repeat(8) {
            val existing = dao.findEntry(candidate.id) ?: return candidate
            if (existing.sessionId == entry.sessionId && existing.payloadJson == entry.payloadJson) {
                // Idempotent retry of the same logical entry.
                return candidate
            }
            val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
            candidate = entry.copy(id = "${entry.id}_$randomSuffix")
        }
        val fallback = UUID.randomUUID().toString().replace("-", "")
        val resolved = entry.copy(id = "${entry.id}_$fallback")
        check(dao.findEntry(resolved.id) == null) {
            "Unable to allocate a unique harness entry id for ${entry.id}"
        }
        return resolved
    }

    override suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? =
        dao.findEntry(entryId)?.takeIf { it.sessionId == sessionId }?.let(::restoreFromStorage)

    override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
        require(entry.sessionId == sessionId) { "Entry session mismatch" }
        repeat(APPEND_RETRY_LIMIT) {
            val lane = ensureLane(sessionId, laneName)
            if (lane.leafId == entry.id) {
                val existing = dao.findEntry(entry.id)
                if (existing != null && existing.sessionId == sessionId && existing.payloadJson == entry.payloadJson) {
                    return
                }
            }
            // Concurrent appends often race on a stale parentId captured before another writer
            // advanced the leaf. Rebase onto the current leaf instead of crashing the UI loop.
            val linked = if (entry.parentId == lane.leafId) entry else entry.copy(parentId = lane.leafId)
            val uniqueEntry = ensureUniqueStorageEntry(linked)
            val sanitized = sanitizeForStorage(uniqueEntry)
            val updatedLane = lane.copy(leafId = uniqueEntry.id, updatedAt = System.currentTimeMillis())
            val outcome = runCatching { dao.appendEntry(sanitized, updatedLane) }
            if (outcome.isSuccess) return
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            if ("moved while appending" in message) {
                return@repeat
            }
            throw outcome.exceptionOrNull()!!
        }
        error("Unable to append entry ${entry.id} to $sessionId/$laneName after $APPEND_RETRY_LIMIT retries")
    }

    override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
        val lane = ensureLane(sessionId, laneName)
        if (leafId != null) require(dao.findEntry(leafId)?.sessionId == sessionId) { "Unknown lane target $leafId" }
        dao.upsertLane(lane.copy(leafId = leafId, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun clearLaneOperation(sessionId: String, laneName: String) {
        val lane = dao.findLane(sessionId, laneName) ?: return
        dao.upsertLane(lane.copy(currentOperationId = null, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun findOperation(operationId: String) = dao.findOperation(operationId)
    override suspend fun listActiveOperations(sessionId: String) = dao.listActiveOperations(sessionId)
    override suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.acceptOperation(sanitized, updatedLane, operation)
    }

    override suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.acceptQueuedOperation(queueItemId, sanitized, updatedLane, operation)
    }

    override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) =
        dao.beginOperation(lane, operation)

    override suspend fun acceptTaskOperation(taskId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, queueItemId: String?) {
        require(lane.leafId == entry.id) { "Admission lane must point to the input entry" }
        val unique = ensureUniqueStorageEntry(entry)
        dao.acceptTaskOperation(taskId, sanitizeForStorage(unique), lane.copy(leafId = unique.id), operation, queueItemId)
    }
    override suspend fun saveOperation(operation: HarnessOperationEntity) = dao.upsertOperation(operation)
    override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
        previousResult: HarnessLaneResultEntity?, taskId: String?) {
        require(entry == null || lane.leafId == entry.id) { "Admission lane must point to the input entry" }
        val unique = entry?.let { ensureUniqueStorageEntry(it) }
        dao.acceptRunTakeover(queueItemId, unique?.let(::sanitizeForStorage), lane.copy(leafId = unique?.id ?: lane.leafId),
            operation, previousLane, previousResult, taskId)
    }
    override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
        val uniqueEntry = entry?.let { ensureUniqueStorageEntry(it) }
        val sanitized = uniqueEntry?.let(::sanitizeForStorage)
        val updatedLane = if (entry != null && lane.leafId == entry.id && uniqueEntry != null && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.settleEffect(sanitized, usage, operation, updatedLane)
    }

    override suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) =
        dao.finishOperation(result, lane)
    override suspend fun completeTaskOperation(taskId: String, result: HarnessLaneResultEntity,
        expectedLane: HarnessLaneEntity, delivery: HarnessEntryEntity) =
        dao.completeTaskOperation(taskId, result, expectedLane, sanitizeForStorage(delivery))
    override suspend fun enqueue(item: HarnessQueueItemEntity) = dao.insertQueueItem(item)
    override suspend fun listQueue(sessionId: String, laneName: String, queueType: String) =
        dao.listQueue(sessionId, laneName, queueType)
    override suspend fun listAllQueues(sessionId: String, laneName: String) =
        dao.listAllQueues(sessionId, laneName)
    override suspend fun cancelQueued(itemId: String) = dao.deleteQueueItem(itemId)
    override suspend fun clearQueue(sessionId: String, laneName: String, queueType: String) =
        dao.clearQueue(sessionId, laneName, queueType)
    override suspend fun retargetQueue(sessionId: String, laneName: String, fromType: String, toType: String) =
        dao.retargetQueue(sessionId, laneName, fromType, toType)
    override suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.consumeQueueItem(itemId, sanitized, updatedLane)
    }
    override suspend fun recordUsage(usage: HarnessUsageEntity) {
        dao.insertUsage(usage)
    }
    override suspend fun listUsage(sessionId: String) = dao.listUsage(sessionId)
    override suspend fun deleteSessionData(sessionId: String) {
        dao.deleteSessionData(sessionId)
        blobStore?.deleteSessionBlobs(sessionId)
    }

    private companion object {
        const val APPEND_RETRY_LIMIT = 8
    }
}

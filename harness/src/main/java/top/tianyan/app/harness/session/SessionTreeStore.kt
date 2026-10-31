package top.tianyan.app.harness.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import java.util.concurrent.ConcurrentHashMap
import java.lang.ref.SoftReference

/**
 * Serialization and active-branch projection for the immutable session tree.
 *
 * 解码策略（借鉴 DSH fail-closed）：无法解码或版本不可识别的 message 条目不进投影，
 * 但必须在 [SessionCorruptionLedger] 登记（error 级日志 + 可查询记录）——静默丢史不允许。
 * [loadStrict] 保持严格语义：坏条目直接抛出。
 */
class SessionTreeStore(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val logger: AppLogger,
    private val payloadMigrations: SessionPayloadMigrations = SessionPayloadMigrations(),
    private val corruptionLedger: SessionCorruptionLedger = SessionCorruptionLedger(),
) {
    private class LaneLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val laneLocks = ConcurrentHashMap<String, LaneLock>()
    private data class CachedBranch(val leafId: String?, val messages: SoftReference<List<HarnessMessage>>)
    private val decodedBranches = LinkedHashMap<String, CachedBranch>(8, 0.75f, true)

    private suspend fun decodedBranch(sessionId: String, leafId: String?): List<HarnessMessage> {
        synchronized(decodedBranches) {
            decodedBranches[sessionId]?.takeIf { it.leafId == leafId }?.messages?.get()?.let { return it }
        }
        val loaded = repository.branch(sessionId, leafId).mapNotNull(::decode)
        synchronized(decodedBranches) {
            decodedBranches[sessionId] = CachedBranch(leafId, SoftReference(loaded))
            if (decodedBranches.size > MAX_DECODED_BRANCHES) {
                decodedBranches.remove(decodedBranches.keys.first())
            }
        }
        return loaded
    }

    private suspend fun <T> withRetainedLaneLock(sessionId: String, laneName: String, block: suspend () -> T): T {
        val key = "$sessionId/$laneName"
        val holder = laneLocks.compute(key) { _, current ->
            (current ?: LaneLock()).also { it.users++ }
        }!!
        try {
            return holder.mutex.withLock { block() }
        } finally {
            // Count waiters as users. Remove only after the last holder leaves, so a new
            // caller cannot obtain a different mutex while an older waiter is still queued.
            laneLocks.compute(key) { _, current ->
                if (current !== holder) current else holder.takeIf { --it.users > 0 }
            }
        }
    }

    suspend fun ensureMainLane(sessionId: String) {
        repository.ensureLane(sessionId, MAIN_LANE)
    }

    /** 当前 lane 的叶子条目 id（无 lane 或空 lane 时为 null）。 */
    suspend fun laneLeafId(sessionId: String, laneName: String = MAIN_LANE): String? =
        runCatching { repository.findLane(sessionId, laneName)?.leafId }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()

    suspend fun load(sessionId: String, laneName: String = MAIN_LANE): List<HarnessMessage> = runCatching {
        val lane = repository.ensureLane(sessionId, laneName)
        repository.branchTail(sessionId, lane.leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        if (throwable is CancellationException) throw throwable
        logger.e("Failed to load harness branch for $sessionId/$laneName: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    /** Read-only durable history; storage errors must not masquerade as an empty completed run. */
    suspend fun loadStrict(sessionId: String, laneName: String = MAIN_LANE): List<HarnessMessage> = try {
        currentCoroutineContext().ensureActive()
        val lane = repository.findLane(sessionId, laneName)
        currentCoroutineContext().ensureActive()
        val messages = if (lane == null) emptyList() else
            repository.branchTail(sessionId, lane.leafId, MAX_LIVE_ENTRIES)
                .filter { it.entryType == "message" }
                .map(::decodeStrict)
        currentCoroutineContext().ensureActive()
        messages
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw failure
    }

    suspend fun loadAt(sessionId: String, leafId: String?): List<HarnessMessage> = runCatching {
        repository.branchTail(sessionId, leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        if (throwable is CancellationException) throw throwable
        logger.e("Failed to load harness branch at $sessionId/$leafId: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun append(sessionId: String, message: HarnessMessage, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            val lane = repository.ensureLane(sessionId, laneName)
            val entry = HarnessEntryEntity(
                id = message.id,
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = message.createdAt,
                entryType = "message",
                customType = messageType(message),
                payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
                payloadVersion = HarnessEntryPayloadVersion.CURRENT,
            )
            repository.appendToLane(sessionId, laneName, entry)
        }
    }

    /**
     * 持久化某条用户消息的前缀块（recall_context entry，紧随该用户消息追加）。
     *
     * 块内容 = 记忆召回 + 任务计划看板（见 [top.tianyan.app.harness.prompt.MemoryRecallSelector.turnPrefixBlock]）：
     * 二者都是逐轮变化的内容，合并成同一段字节挂在该轮上，轮内冻结。
     *
     * entry id 由 userMessageId 确定性推导，幂等性由存储层语义保证（ensureUniqueStorageEntry）：
     * 同 id 同 payload 幂等复用（分支重放场景直接重新激活原 entry）；同 id 不同 payload
     * （该轮之后记忆库变了的重跑）自动派生新 id 落库，投影按 sequence 取最新者。
     * 该 entry 不解码进消息流（entryType 不是 "message"），仅由 CompactionManager.project
     * 读出并映射到 provider 投影的对应 user 消息后缀；UI 与检索路径天然不可见。
     *
     * @return 是否成功持久化。调用方必须在 false 时放弃挂载——未持久化的字节一旦进入
     *   投影，下一轮组装便会漂移，违背 prefix cache 稳定性契约（低权威背景资料缺失无损正确性）。
     */
    suspend fun appendRecallBlock(
        sessionId: String,
        userMessageId: String,
        block: String,
        laneName: String = MAIN_LANE,
    ): Boolean {
        if (block.isBlank()) return false
        return withRetainedLaneLock(sessionId, laneName) {
            runCatching {
                val lane = repository.ensureLane(sessionId, laneName)
                val entry = HarnessEntryEntity(
                    id = RECALL_ENTRY_PREFIX + userMessageId,
                    sessionId = sessionId,
                    parentId = lane.leafId,
                    createdAt = System.currentTimeMillis(),
                    entryType = RECALL_ENTRY_TYPE,
                    customType = userMessageId,
                    payloadJson = block,
                    payloadVersion = HarnessEntryPayloadVersion.CURRENT,
                )
                repository.appendToLane(sessionId, laneName, entry)
            }.onFailure {
                if (it is CancellationException) throw it
                logger.w("Failed to persist recall block for $userMessageId: ${it.message}")
            }.isSuccess
        }
    }

    /**
     * 供压缩等"读快照-落库"协作方复用同一把 per-lane 锁：保证 compaction entry 的
     * 重读与写入和本 lane 的 append 串行化。锁内不得执行长耗时挂起调用（如 LLM）。
     */
    suspend fun <T> withLaneLock(sessionId: String, laneName: String = MAIN_LANE, block: suspend () -> T): T =
        withRetainedLaneLock(sessionId, laneName, block)

    /** Navigate to the parent of [entryId], preserving the abandoned branch. */
    suspend fun rewindBefore(sessionId: String, entryId: String, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            val target = repository.findEntry(sessionId, entryId) ?: return@withRetainedLaneLock
            repository.moveLane(sessionId, laneName, target.parentId)
        }
    }

    suspend fun moveTo(sessionId: String, entryId: String?, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            repository.moveLane(sessionId, laneName, entryId)
        }
    }

    suspend fun deleteSession(sessionId: String) {
        repository.deleteSessionData(sessionId)
        synchronized(decodedBranches) { decodedBranches.remove(sessionId) }
    }

    data class SearchHit(val message: HarnessMessage, val index: Int)

    suspend fun search(sessionId: String, query: String, limit: Int = 8): List<HarnessMessage> =
        searchIndexed(sessionId, query, limit).map { it.message }

    /** 返回活动分支中的原始索引，与 read(index) 使用同一套编号。 */
    suspend fun searchIndexed(sessionId: String, query: String, limit: Int = 8): List<SearchHit> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        val resultsByCallId = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
        val callsById = messages.filterIsInstance<ToolCall>().associateBy { it.id }
        val terms = needle.split(SEARCH_TERM_SEPARATOR).filter { it.isNotBlank() }

        return messages.withIndex().mapNotNull { (index, message) ->
            val relatedText = when (message) {
                is ToolCall -> resultsByCallId[message.id].orEmpty().joinToString("\n") { searchableText(it) }
                is ToolResult -> callsById[message.toolCallId]?.let(::searchableText).orEmpty()
                else -> ""
            }
            val haystack = searchableText(message) + "\n" + relatedText
            val exactMatch = haystack.contains(needle, ignoreCase = true)
            val matchedTerms = terms.count { haystack.contains(it, ignoreCase = true) }
            if (!exactMatch && (terms.isEmpty() || matchedTerms != terms.size)) null
            else SearchMatch(message, index, if (exactMatch) matchedTerms + 2 else matchedTerms)
        }.sortedWith(compareByDescending<SearchMatch> { it.score }.thenByDescending { it.index })
            .take(limit.coerceIn(1, 20))
            .map { SearchHit(it.message, it.index) }
    }

    suspend fun read(sessionId: String, messageId: String? = null, index: Int? = null): HarnessMessage? {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        return when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        }
    }

    /** Read one active-branch message and keep a tool call/result exchange together. */
    suspend fun readWithRelated(
        sessionId: String,
        messageId: String? = null,
        index: Int? = null,
    ): List<HarnessMessage> {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        val selected = when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        } ?: return emptyList()
        // Both anchors resolve to the same call-rooted group; otherwise a read started from a result
        // returns the call ID as its continuation id and the next page would switch to a wider text set.
        val relatedIds = when (selected) {
            is ToolCall -> relatedGroupIds(messages, selected.id)
            is ToolResult -> relatedGroupIds(messages, selected.toolCallId)
            else -> mutableSetOf(selected.id)
        }
        return messages.filter { it.id in relatedIds }
    }

    /** A call keeps pending-approval placeholders and the final settlement as separate results. */
    private fun relatedGroupIds(messages: List<HarnessMessage>, callId: String): MutableSet<String> =
        messages.filterIsInstance<ToolResult>()
            .filter { it.toolCallId == callId }
            .mapTo(mutableSetOf(callId)) { it.id }

    internal fun decode(entity: HarnessEntryEntity): HarnessMessage? {
        if (entity.entryType != "message") return null
        if (entity.payloadVersion > HarnessEntryPayloadVersion.CURRENT) {
            recordCorruption(
                entity,
                "条目负载版本 ${entity.payloadVersion} 高于当前支持的 ${HarnessEntryPayloadVersion.CURRENT}（应用被降级？）",
            )
            return null
        }
        val payload = if (entity.payloadVersion < HarnessEntryPayloadVersion.CURRENT) {
            runCatching {
                payloadMigrations.upgrade(entity.payloadJson, entity.payloadVersion)
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                recordCorruption(entity, "迁移链升级失败：${failure.message}")
                return null
            }
        } else entity.payloadJson
        return runCatching { json.decodeFromString(HarnessMessage.serializer(), payload) }
            .onFailure { recordCorruption(entity, "payload 解码失败：${it.message}") }
            .getOrNull()
    }

    private fun recordCorruption(entity: HarnessEntryEntity, cause: String) {
        logger.e("Corrupt harness entry ${entity.id} (type=${entity.entryType}, v${entity.payloadVersion}) in ${entity.sessionId}: $cause")
        corruptionLedger.record(entity, cause)
    }

    /** 严格路径的版本感知解码：迁移链升级 + 超前版本显式报错，坏条目不上浮为空历史。 */
    private fun decodeStrict(entity: HarnessEntryEntity): HarnessMessage {
        if (entity.payloadVersion > HarnessEntryPayloadVersion.CURRENT) {
            val cause = "条目负载版本 ${entity.payloadVersion} 高于当前支持的 ${HarnessEntryPayloadVersion.CURRENT}（应用被降级？）"
            recordCorruption(entity, cause)
            throw IllegalArgumentException("Harness entry ${entity.id}: $cause")
        }
        val payload = if (entity.payloadVersion < HarnessEntryPayloadVersion.CURRENT) {
            payloadMigrations.upgrade(entity.payloadJson, entity.payloadVersion)
        } else entity.payloadJson
        return json.decodeFromString(HarnessMessage.serializer(), payload)
    }

    /** 某会话被跳过投影的腐坏条目记录（诊断入口，见 [SessionCorruptionLedger]）。 */
    fun corruptionReport(sessionId: String): List<CorruptEntryRecord> = corruptionLedger.snapshotFor(sessionId)

    private fun messageType(message: HarnessMessage): String = when (message) {
        is UserMessage -> "user"
        is AssistantText -> "assistant"
        is ToolCall -> "tool_call"
        is ToolResult -> "tool_result"
        is CapabilityEvent -> "capability_event"
        is SkillSuggestion -> "skill_suggestion"
        is TaskDelivery -> "task_delivery"
        is ModelSwitchEvent -> "model_switch"
        is TurnChangesEvent -> "turn_changes"
    }

    private fun searchableText(message: HarnessMessage): String = when (message) {
        is CapabilityEvent -> "${message.kind} ${message.name} ${message.details}"
        is SkillSuggestion -> "${message.skillName} ${message.description}"
        is TaskDelivery -> "task_delivery 任务交付 ${message.report.taskId} ${message.report.gaps}"
        is ModelSwitchEvent -> "${message.fromLabel} ${message.toLabel}"
        is TurnChangesEvent -> "turn_changes 轮文件改动 ${(message.created + message.modified + message.touched).joinToString(" ")}"
        is UserMessage -> message.text
        is AssistantText -> "${message.text}\n${message.reasoning.orEmpty()}"
        is ToolCall -> "${message.rawToolName.orEmpty()} ${message.tool} ${message.args} ${message.reasoning.orEmpty()}"
        is ToolResult -> message.output
    }

    companion object {
        const val MAIN_LANE = "main"
        private const val MAX_DECODED_BRANCHES = 4
        const val RECALL_ENTRY_PREFIX = "recall_"
        /** 记忆召回后缀 entry 类型：紧随用户轮持久化，project() 读出映射为该轮 provider 后缀。 */
        const val RECALL_ENTRY_TYPE = "recall_context"
        /** Maximum decoded messages retained per live UI/session projection. */
        const val MAX_LIVE_ENTRIES = 600
        private val SEARCH_TERM_SEPARATOR = Regex("[\\s,，;；|]+")
    }

    private data class SearchMatch(val message: HarnessMessage, val index: Int, val score: Int)
}

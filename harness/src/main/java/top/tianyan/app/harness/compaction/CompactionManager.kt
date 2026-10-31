package top.tianyan.app.harness.compaction

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.session.SessionContextProjector

/** compress 锚点解析结果。 */
sealed interface CompressAnchorResult {
    data class Resolved(val keepFromIndex: Int) : CompressAnchorResult
    data class Invalid(val message: String) : CompressAnchorResult
}

/** Persists compaction as an immutable tree entry and projects provider context from it. */
class CompactionManager(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val sessionStore: SessionTreeStore,
    private val summarizer: CompactionSummarizer? = null,
) {
    /** 测试钩子：压缩落库前注入并发写，模拟 acceptRun 等直写路径的竞态窗口。 */
    @Volatile
    internal var beforeCompactionWriteForTest: (suspend () -> Unit)? = null

    /**
     * 压缩变更信号：每次 compact() 成功落库后自增。UI（上下文用量面板）据此响应式重算
     * 真实压缩投影——手动压缩（compress 工具）与系统自动压缩（请求前折叠 / 模型切换 /
     * 上下文溢出紧急压缩）都走同一 [compact] 路径，因此统一被覆盖。
     */
    private val _compactionRevision = MutableStateFlow(0L)
    val compactionRevision: StateFlow<Long> = _compactionRevision.asStateFlow()

    private val contextProjector = SessionContextProjector(repository, json)

    /** Canonical source entries and recovery diagnostics used by the provider projection. */
    suspend fun inspect(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE) =
        contextProjector.inspect(sessionId, laneName)

    suspend fun project(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactedContext =
        inspect(sessionId, laneName).context()

    /**
     * 最近一次压缩的轻量快照（不解码保留消息）。
     * 折叠条数为历次压缩累计；摘要与时间取最新一条——与 UI 横幅对齐。
     * 会话从未压缩时返回 null——UI 据此隐藏折叠提示。
     */
    suspend fun latestSnapshot(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactionSnapshot? {
        return try {
            currentCoroutineContext().ensureActive()
            val lane = repository.findLane(sessionId, laneName)
            currentCoroutineContext().ensureActive()
            if (lane == null) return null
            val entry = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
            currentCoroutineContext().ensureActive()
            val newest = entry?.let { json.decodeFromString(CompactionPayload.serializer(), it.payloadJson) } ?: return null
            CompactionSnapshot(newest.summary, newest.cumulativeCompactedMessageCount ?: newest.compactedMessageCount, newest.createdAt)
        } catch (cancellation: CancellationException) { throw cancellation }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
    }

    suspend fun compact(
        sessionId: String,
        context: CompactedContext,
        keepFromIndex: Int,
        laneName: String = SessionTreeStore.MAIN_LANE,
        model: ModelConfig? = null,
        summaryContext: SummaryRequestContext? = null,
    ): CompactedContext {
        require(keepFromIndex in 1..context.messages.size) { "Compaction must remove at least one message" }
        // 摘要在锁外生成：前缀对账通过时锁内重读的折叠段与调用方快照完全一致，摘要输入不变；
        // 不能持 laneLock 跨 LLM 调用——否则分支切换/消息追加会被阻塞整个摘要时长。
        val collapsedForSummary = context.messages.take(keepFromIndex)
        // 上一份摘要层（压缩摘要 + 尚未折叠的分支摘要）作为迭代上下文传入 LLM，
        // 天然实现滚动合并；LLM 不可用时机械摘要 + 字符串拼接兜底。
        val previousSummaries = buildList {
            context.summary?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(context.branchSummaries.filter { it.isNotBlank() })
        }
        val llmSummary = if (model != null && summarizer != null && collapsedForSummary.isNotEmpty()) {
            try {
                summarizer.generateSummary(model, collapsedForSummary, previousSummaries, summaryContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(
                    "ContextCompaction",
                    "LLM 结构化摘要失败，回退机械摘要：${throwable.message}",
                )
                null
            }
        } else {
            null
        }
        val baseSummary = if (llmSummary != null) {
            llmSummary
        } else {
            val incrementalSummary = ContextWindowPolicy.buildHistorySummary(collapsedForSummary)
            mergeRollingSummary(previousSummaries.joinToString("\n\n"), incrementalSummary)
        }
        // 累计文件足迹与摘要生成器解耦：LLM 摘要已在 finalizeSummary 里附加 tags，
        // 机械回退（无 summarizer / 紧急压缩）也必须程序化补上，否则模型失忆后
        // 不知道改过哪些文件，会重复 read 甚至覆盖自己的改动。
        val summary = ensureFileTags(
            baseSummary,
            FileOperations.parseFromSummary(previousSummaries.joinToString("\n\n"))
                .mergedWith(FileOperations.extractFrom(collapsedForSummary)),
        )

        // "重读-对账-落库"在 laneLock 内原子完成：调用方传入的 context 是锁外快照，若期间有
        // 消息落库而直接折叠旧快照，新消息的 sequence 会小于压缩 entry、被投影的 afterMessages
        // 过滤，从 provider 视角永久丢失。重读必须走 project() 的投影口径（而非裸 branch），
        // 否则已压缩过的会话前缀对不上，增量压缩会被误判为分支漂移而跳过。
        return sessionStore.withLaneLock(sessionId, laneName) {
            val fresh = project(sessionId, laneName)
            val prefixConsistent = fresh.messages.size >= context.messages.size &&
                fresh.messages.subList(0, context.messages.size)
                    .zip(context.messages)
                    .all { (current, snapshot) -> current.id == snapshot.id }
            if (!prefixConsistent) {
                // 期间发生过分支切换等不可对账的变更：放弃本次压缩，返回最新投影
                // （携带既有摘要层）；下一次组装会重新触发压缩。
                return@withLaneLock fresh
            }
            val lane = repository.ensureLane(sessionId, laneName)
            val collapsed = fresh.messages.take(keepFromIndex)
            val retained = fresh.messages.drop(keepFromIndex)
            val now = System.currentTimeMillis()
            val previousFoldedCount = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
                ?.let { entry ->
                    runCatching { json.decodeFromString(CompactionPayload.serializer(), entry.payloadJson) }.getOrNull()
                }
                ?.let { it.cumulativeCompactedMessageCount ?: it.compactedMessageCount }
                ?: 0
            val payload = CompactionPayload(
                sourceLeafId = lane.leafId,
                summary = summary,
                retainedMessagesJson = null,
                retainedMessages = retained,
                compactedMessageCount = collapsed.size,
                cumulativeCompactedMessageCount = previousFoldedCount + collapsed.size,
                retainedMessageCount = retained.size,
                estimatedTokensBefore = fresh.messages.sumOf(::messageTokens),
                createdAt = now,
                sourceWatermarkSequence = fresh.sourceMaxSequence,
            )
            val entry = HarnessEntryEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = now,
                entryType = ENTRY_TYPE,
                customType = null,
                payloadJson = json.encodeToString(CompactionPayload.serializer(), payload),
            )
            beforeCompactionWriteForTest?.invoke()
            currentCoroutineContext().ensureActive()
            repository.appendToLane(sessionId, laneName, entry)
            currentCoroutineContext().ensureActive()
            _compactionRevision.update { it + 1 }
            Log.d(
                "ContextCompaction",
                "压缩会话 $sessionId：折叠 ${collapsed.size} 条（累计 ${payload.cumulativeCompactedMessageCount}），" +
                    "保留 ${retained.size} 条，摘要 ${summary.length} 字符" +
                    "（${if (llmSummary != null) "LLM 结构化" else "机械回退"}），" +
                    "折叠分支摘要 ${context.branchSummaries.size} 份，" +
                    "压缩前估算 ${payload.estimatedTokensBefore} tokens",
            )
            // Return the canonical committed projection, including concurrent direct writes and provenance watermarks.
            project(sessionId, laneName)
        }
    }

    private fun messageTokens(message: HarnessMessage): Int = ContextWindowPolicy.estimateTokens(message.toString())

    /** 摘要未携带文件足迹 tags 时补上（机械回退路径）；已有则原样返回避免重复。 */
    private fun ensureFileTags(summary: String, files: FileOperations): String {
        val tags = files.renderTags()
        if (tags.isEmpty()) return summary
        if (summary.contains("<read-files>") || summary.contains("<modified-files>")) return summary
        return "$summary\n\n$tags"
    }

    /** Preserve both durable early context and the newest folded state after the cap is reached. */
    private fun mergeRollingSummary(previous: String?, incremental: String): String {
        val old = previous.orEmpty().trim()
        val newest = incremental.trim()
        val combined = listOf(old, newest).filter { it.isNotBlank() }.joinToString("\n\n")
        if (combined.length <= MAX_SUMMARY_CHARS) return combined
        if (old.isBlank()) return newest.takeLast(MAX_SUMMARY_CHARS)
        if (newest.isBlank()) return old.take(MAX_SUMMARY_CHARS)

        val marker = "\n\n[较早摘要中段已省略]\n\n"
        val newestBudget = minOf(newest.length, MAX_SUMMARY_CHARS / 2)
        val oldBudget = (MAX_SUMMARY_CHARS - marker.length - newestBudget).coerceAtLeast(0)
        val oldHead = old.take((oldBudget + 1) / 2)
        val oldTail = old.takeLast(oldBudget / 2)
        return (oldHead + marker + oldTail + "\n\n" + newest.takeLast(newestBudget)).takeLast(MAX_SUMMARY_CHARS)
    }

    companion object {
    /**
     * compress 工具的锚点解析（纯函数）：[anchor] 必须原样、唯一地摘自某条用户消息。
     * - mode=before：压缩锚点轮之前的全部历史（保留锚点轮及之后）；
     * - mode=after：压缩除最后一条用户消息（进行中轮次）外的全部已完成历史。
     * 校验失败返回 [CompressAnchorResult.Invalid]（带可回写给模型的说明）。
     */
    fun resolveCompressAnchor(
        messages: List<HarnessMessage>,
        mode: String,
        anchor: String,
    ): CompressAnchorResult {
        val normalizedMode = mode.trim().lowercase()
        if (normalizedMode !in setOf("before", "after")) {
            return CompressAnchorResult.Invalid("mode 必须是 before（压缩锚点轮之前）或 after（压缩除当前轮外的全部已完成历史）")
        }
        val trimmedAnchor = anchor.trim()
        if (trimmedAnchor.length < MIN_COMPRESS_ANCHOR_CHARS) {
            return CompressAnchorResult.Invalid(
                "anchor 必须原样摘自某条用户消息且足够独特（至少 $MIN_COMPRESS_ANCHOR_CHARS 字符），用于唯一定位压缩边界",
            )
        }
        val anchorIndexes = messages.withIndex().mapNotNull { (index, message) ->
            (message as? UserMessage)?.takeIf { it.text.contains(trimmedAnchor) }?.let { index }
        }
        if (anchorIndexes.isEmpty()) {
            return CompressAnchorResult.Invalid(
                "锚点在当前上下文中没有匹配到任何用户消息。" +
                    "若该轮已被折叠进既有摘要则无法再以它为锚点，请改用更近的轮次；否则请原样复制消息中的一段文字重试",
            )
        }
        if (anchorIndexes.size > 1) {
            return CompressAnchorResult.Invalid(
                "锚点匹配到 ${anchorIndexes.size} 条用户消息，请提供更长、更独特的摘录以唯一定位",
            )
        }
        val keepFromIndex = when (normalizedMode) {
            "before" -> anchorIndexes.single()
            else -> messages.indexOfLast { it is UserMessage }
        }
        if (keepFromIndex < 1) {
            return CompressAnchorResult.Invalid("锚点之前没有可压缩的内容（它已是当前上下文的第一条消息）")
        }
        if (keepFromIndex > messages.size) {
            return CompressAnchorResult.Invalid("压缩边界越界，请重试")
        }
        return CompressAnchorResult.Resolved(keepFromIndex)
    }

    private const val MIN_COMPRESS_ANCHOR_CHARS = 8
        const val ENTRY_TYPE = "compaction"
        const val BRANCH_SUMMARY_ENTRY_TYPE = "branch_summary"
        private const val MAX_SUMMARY_CHARS = 16_000
    }
}

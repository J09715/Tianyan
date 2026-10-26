package top.tianyan.app.harness.compaction

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.session.SessionTreeStore
import kotlinx.coroutines.CancellationException

/**
 * 分支摘要（对齐 pi branch-summarization.ts）：切换分支时把被放弃的分支段
 * 生成摘要注入新位置，保留"换方案前的关键结论"，避免树导航丢失上下文。
 *
 * 摘要优先走 LLM（复用 [CompactionSummarizer] 的结构化格式），失败回退机械摘要；
 * 被放弃段太短（< [MIN_MESSAGES] 条消息）不值得一次 LLM 调用，直接跳过。
 * 同一进程内同一 fromLeafId 只摘要一次，防止来回切换产生重复摘要。
 */
class BranchSummarizer(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val logger: AppLogger,
    private val summarizer: CompactionSummarizer? = null,
) {
    private val summarizedFromLeafs = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 从旧叶子切到新叶子后调用：摘要被放弃的分支段，并把 branch_summary 条目
     * 追加到当前 lane 叶子（即新位置）。必须在 [moveTo] 完成之后调用。
     *
     * @return 是否生成了分支摘要。
     */
    suspend fun summarizeAbandonedBranch(
        sessionId: String,
        oldLeafId: String,
        newLeafId: String,
        laneName: String = SessionTreeStore.MAIN_LANE,
        model: ModelConfig? = null,
    ): Boolean {
        if (oldLeafId == newLeafId) return false
        if (!markSummarized(sessionId, oldLeafId)) return false
        val abandoned = abandonedMessages(sessionId, oldLeafId, newLeafId)
        if (abandoned.size < MIN_MESSAGES) return false

        val summaryText = generateSummaryText(abandoned, model)
        if (summaryText.isBlank()) return false
        val now = System.currentTimeMillis()
        val entry = HarnessEntryEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = newLeafId,
            createdAt = now,
            entryType = CompactionManager.BRANCH_SUMMARY_ENTRY_TYPE,
            customType = null,
            payloadJson = json.encodeToString(
                BranchSummaryPayload.serializer(),
                BranchSummaryPayload(
                    summary = summaryText,
                    fromLeafId = oldLeafId,
                    summarizedMessageCount = abandoned.size,
                    createdAt = now,
                ),
            ),
        )
        return try {
            repository.appendToLane(sessionId, laneName, entry)
            logger.i(
                "分支摘要已注入会话 $sessionId：折叠被放弃分支 ${abandoned.size} 条消息" +
                    "（${if (model != null && summarizer != null) "LLM 结构化" else "机械回退"}）",
            )
            true
        } catch (throwable: Throwable) {
            logger.e("写入分支摘要失败：${throwable.message}", throwable)
            false
        }
    }

    /** 找公共祖先，返回旧分支上被放弃段的消息（按树序）。 */
    private suspend fun abandonedMessages(
        sessionId: String,
        oldLeafId: String,
        newLeafId: String,
    ): List<HarnessMessage> {
        val oldEntries = repository.branch(sessionId, oldLeafId)
        val newEntries = repository.branch(sessionId, newLeafId)
        var common = 0
        while (common < oldEntries.size && common < newEntries.size &&
            oldEntries[common].id == newEntries[common].id
        ) {
            common++
        }
        return oldEntries.drop(common)
            .filter { it.entryType == "message" }
            .mapNotNull { entry ->
                runCatching { json.decodeFromString(HarnessMessage.serializer(), entry.payloadJson) }.getOrNull()
            }
    }

    private suspend fun generateSummaryText(abandoned: List<HarnessMessage>, model: ModelConfig?): String {
        val header = "[分支摘要] 切换分支时保留的上一条分支上下文：\n"
        if (model != null && summarizer != null) {
            val llm = try {
                summarizer.generateSummary(model, abandoned)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger.w("LLM 分支摘要失败，回退机械摘要：${throwable.message}")
                null
            }
            if (llm != null) return header + llm
        }
        return header + ContextWindowPolicy.buildHistorySummary(abandoned)
    }

    /** 同一 fromLeafId 只摘要一次（进程内去重，防来回切换重复注入）。 */
    private fun markSummarized(sessionId: String, fromLeafId: String): Boolean =
        summarizedFromLeafs.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(fromLeafId)

    private companion object {
        const val MIN_MESSAGES = 4
    }
}

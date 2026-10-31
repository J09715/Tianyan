package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.compaction.CompactedContext
import top.tianyan.app.harness.compaction.CompressAnchorResult
import top.tianyan.app.harness.compaction.SummaryRequestContext
import top.tianyan.app.harness.core.ToolBackend
import top.tianyan.app.harness.session.SessionTreeStore

data class ContextMemoryRequest(
    val tool: HarnessTool,       // HISTORY_SEARCH / HISTORY_READ / COMPRESS
    val args: JsonObject,
    val sessionId: String,
    val redactText: (String) -> String = { it },
)

/**
 * Agent 工具后端：上下文记忆管理（history_search / history_read / compress）。
 *
 * 职责边界：
 * - 只负责会话历史检索/回读与手动上下文压缩（会话树检索、CompactionManager 摘要路径）。
 * - 不持有审批或脱敏策略；历史切片前使用 ToolExecutor 管道提供的脱敏函数。
 */
class ContextMemoryToolBackend(
    private val messageStore: SessionTreeStore? = null,
    private val compactionManager: CompactionManager? = null,
    private val sessionDao: HarnessSessionRepository? = null,
    private val providerClient: ProviderClient? = null,
) : ToolBackend<ContextMemoryRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: ContextMemoryRequest): Pair<Boolean, String> = when (request.tool) {
        HarnessTool.HISTORY_SEARCH -> executeHistorySearch(request.args, request.sessionId, request.redactText)
        HarnessTool.HISTORY_READ -> executeHistoryRead(request.args, request.sessionId, request.redactText)
        HarnessTool.COMPRESS -> executeCompress(request.args, request.sessionId)
        else -> throw IllegalArgumentException("Unsupported tool: ${request.tool}")
    }

    private suspend fun executeHistorySearch(args: JsonObject, sessionId: String, redact: (String) -> String): Pair<Boolean, String> {
        val query = JsonArgs.requireString(args, "query")
        val limit = JsonArgs.optionalLong(args, "limit", 8L, 1L, 20L).toInt()
        val matches = messageStore?.searchIndexed(sessionId, query, limit).orEmpty()
        if (matches.isEmpty()) return true to "未找到匹配历史：$query"
        return true to matches.map { (message, index) ->
            // Preview through the hit's whole call/result group: the label of an echoed secret can
            // live in a sibling message, which a per-message redaction pass would never learn.
            val related = messageStore?.readWithRelated(sessionId, messageId = message.id).orEmpty()
            val preview = HistoryReadPage.preview(related.ifEmpty { listOf(message) }, message.id, redact)
            "[index=$index] id=${message.id} time=${message.createdAt} $preview"
        }.joinToString("\n")
    }

    private suspend fun executeHistoryRead(args: JsonObject, sessionId: String, redact: (String) -> String): Pair<Boolean, String> {
        val messageId = args["message_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        val index = if (messageId != null || "index" !in args) null else
            JsonArgs.optionalLong(args, "index", 0, 0, Int.MAX_VALUE.toLong()).toInt()
        require(messageId != null || index != null) { "history.read 需要 message_id 或 index" }
        val offset = JsonArgs.optionalLong(args, "offset", 0, 0, Int.MAX_VALUE.toLong()).toInt()
        val limit = JsonArgs.optionalLong(args, "limit", HistoryReadPage.DEFAULT_LIMIT.toLong(), 1, HistoryReadPage.MAX_LIMIT.toLong()).toInt()
        val messages = messageStore?.readWithRelated(sessionId, messageId, index).orEmpty()
        if (messages.isEmpty()) return false to "未找到指定历史消息"
        return true to HistoryReadPage.render(messages, offset, limit, redact)
    }

    /**
     * compress 工具（对齐 Reasonix）：用户明确要求压缩上下文时，把指定边界之前的历史
     * 折叠为结构化摘要。anchor 必须原样、唯一地摘自某条用户消息——多匹配/零匹配都拒绝，
     * 让模型换更长的摘录重试，而不是猜一个边界静默压错地方。走与自动压缩相同的
     * cache-replay 摘要路径；原文不丢，仍可 history_read 回读。
     */
    private suspend fun executeCompress(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        // compress 只会在用户明确要求时由模型调用，属于显式会话操作。
        // 不受「命令输出压缩」或「自动上下文压缩」开关约束：前者仅控制 RTK 命令输出，
        // 后者仅控制 ApiContextAssembler 的自动折叠策略。
        val manager = compactionManager ?: return false to "未初始化压缩管理器"
        val mode = JsonArgs.optionalString(args, "mode").orEmpty().trim().lowercase()
        val anchor = JsonArgs.optionalString(args, "anchor").orEmpty()
        if (sessionId.isBlank()) return false to "当前没有活动会话，无法压缩"

        val context = manager.project(sessionId)
        val keepFromIndex = when (val resolved = CompactionManager.resolveCompressAnchor(
            context.messages,
            mode,
            anchor,
        )) {
            is CompressAnchorResult.Resolved -> resolved.keepFromIndex
            is CompressAnchorResult.Invalid -> return false to resolved.message
        }
        return compactAtIndex(sessionId, context, keepFromIndex, modeLabel = mode)
    }

    /**
     * UI 手动压缩入口（"+" 附件弹窗）：至少保留最近 [keepRecent] 条，并回退到完整用户轮次，
     * 走与 compress 工具相同的 LLM 摘要路径（模型解析失败退回机械摘要）；原文不丢，可 history_read 回读。
     */
    suspend fun manualCompact(sessionId: String, keepRecent: Int = 4): Pair<Boolean, String> {
        val manager = compactionManager ?: return false to "未初始化压缩管理器"
        if (sessionId.isBlank()) return false to "当前没有活动会话，无法压缩"
        val context = manager.project(sessionId)
        val boundary = manualCompactionBoundary(context.messages, keepRecent)
        if (boundary <= 0) return false to "可折叠的完整历史轮次不足，无需压缩"
        return compactAtIndex(sessionId, context, boundary, modeLabel = "手动")
    }

    private suspend fun compactAtIndex(
        sessionId: String,
        context: CompactedContext,
        keepFromIndex: Int,
        modeLabel: String,
    ): Pair<Boolean, String> {
        val manager = compactionManager ?: return false to "未初始化压缩管理器"
        // 手动压缩也走 LLM 摘要：按会话绑定的模型解析；解析失败退回机械摘要路径
        val model = runCatching {
            sessionDao?.findById(sessionId)?.let { session ->
                providerClient?.resolveConfigured(session.modelId, session.modelVariant)
            }
        }.getOrNull()
        val summaryContext = model?.let {
            SummaryRequestContext(
                systemPrompt = "",
                summaryLayer = context.summaryLayer,
                toolCallMode = if (it.pureChatMode) ToolCallMode.DISABLED else it.toolCallMode,
                visionEnabled = it.visionEnabled,
                recallBlocks = context.recallBlocks,
                // 手动压缩没有装配期的截断快照：直接取投影前缀（截断差异只影响缓存命中起点，不影响正确性）
                replayPrefix = context.messages.take(keepFromIndex),
            )
        }
        val compacted = manager.compact(
            sessionId,
            context,
            keepFromIndex,
            model = model,
            summaryContext = summaryContext,
        )
        val foldedCount = context.messages.size - compacted.messages.size
        return true to buildString {
            append("已按 ")
            append(modeLabel)
            append("模式压缩：折叠 $foldedCount 条消息为结构化摘要")
            append("（保留后 ${compacted.messages.size} 条）。")
            append("被折叠的原文仍在会话记录中，需要细节时可用 history_read(message_id) 按需回读。")
        }
    }

}

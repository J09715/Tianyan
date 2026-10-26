package top.tianyan.app.harness.compaction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.ImagePayloadCompressor
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.session.ApiMessageProjector
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话文本序列化（对齐 pi 的 serializeConversation）：
 * 把 HarnessMessage 列表压平为 `[User]:` / `[Assistant]:` / `[Assistant tool calls]:` / `[Tool result]:` 叙事，供 LLM 摘要请求使用；纯文本形态防止模型把历史当作"要继续的对话"。
 *
 * 设计要点（源自 pi compaction/utils.ts）：
 * - 工具结果截断到 [TOOL_RESULT_CHAR_LIMIT]（2000 字符），超出部分标注省略量——
 *   工具输出（read/bash）是上下文体积的最大来源，摘要请求必须控制预算；
 * - 连续多个 ToolCall 合并为一行（`read(path="a"); edit(path="b")`）；
 * - UI-only 事件（能力激活/模型切换）不参与摘要。
 */
object ConversationText {

    const val TOOL_RESULT_CHAR_LIMIT = 2_000
    private const val ARG_VALUE_CHAR_LIMIT = 240
    private const val THINKING_CHAR_LIMIT = 1_200
    /** 序列化文本总量上限（字符）：超出时保头尾，避免摘要请求本身爆上下文。 */
    const val MAX_SERIALIZED_CHARS = 240_000

    fun toolCallDetailsOf(messages: List<HarnessMessage>): Map<String, Pair<String, JsonObject>> =
        messages.filterIsInstance<ToolCall>().associate { it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args) }

    fun serialize(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>> = toolCallDetailsOf(messages),
        /** 序列化文本上限（字符）；超出保头尾。调用方按目标模型窗口推导（见 CompactionSummarizer）。 */
        maxChars: Int = MAX_SERIALIZED_CHARS,
    ): String {
        val lines = mutableListOf<String>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message) {
                is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion, is TaskDelivery, is TurnChangesEvent -> Unit
                is UserMessage -> lines += "[User]: ${message.text.trim()}"
                is AssistantText -> {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let {
                        lines += "[Assistant thinking]: ${it.trim().take(THINKING_CHAR_LIMIT)}"
                    }
                    if (message.text.isNotBlank()) lines += "[Assistant]: ${message.text.trim()}"
                }
                is ToolCall -> {
                    // 连续 ToolCall 合并为一行，模拟真实 assistant 消息的并行工具调用形态
                    val calls = mutableListOf<String>()
                    var cursor = index
                    while (cursor < messages.size && messages[cursor] is ToolCall) {
                        calls += formatToolCall(messages[cursor] as ToolCall, toolCallDetails)
                        cursor++
                    }
                    lines += "[Assistant tool calls]: ${calls.joinToString("; ")}"
                    index = cursor
                    continue
                }
                is ToolResult -> {
                    val name = toolCallDetails[message.toolCallId]?.first ?: "tool"
                    lines += "[Tool result]($name): ${truncateToolResult(message.output)}"
                }
            }
            index++
        }
        val text = lines.joinToString("\n")
        return if (text.length <= maxChars) text else clipSerialized(text, maxChars)
    }

    /** 超长时保留首部（初始目标）与尾部（最新进展），中段标注省略量。 */
    private fun clipSerialized(text: String, maxChars: Int): String {
        val headBudget = maxChars / 6
        val tailBudget = maxChars - headBudget
        val omitted = text.length - headBudget - tailBudget
        return text.take(headBudget) +
            "\n…[中段 $omitted 字符已省略]…\n" +
            text.takeLast(tailBudget)
    }

    private fun formatToolCall(call: ToolCall, details: Map<String, Pair<String, JsonObject>>): String {
        val name = details[call.id]?.first ?: call.rawToolName ?: HarnessApiMapper.apiName(call.tool)
        val args = call.args.entries.joinToString(", ") { (key, value) ->
            val rendered = runCatching {
                when {
                    value is JsonPrimitive -> value.jsonPrimitive.contentOrNull.orEmpty()
                    else -> value.toString()
                }
            }.getOrDefault(value.toString())
            "$key=\"${rendered.take(ARG_VALUE_CHAR_LIMIT)}\""
        }
        return "$name(${args})"
    }

    private fun truncateToolResult(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= TOOL_RESULT_CHAR_LIMIT) return trimmed
        return trimmed.take(TOOL_RESULT_CHAR_LIMIT) +
            "…[已截断，省略 ${trimmed.length - TOOL_RESULT_CHAR_LIMIT} 字符]"
    }
}

/** 从消息中提取的累计文件操作（对齐 pi 的 readFiles/modifiedFiles 累计追踪）。 */
data class FileOperations(
    val readFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
) {
    fun mergedWith(other: FileOperations): FileOperations = FileOperations(
        readFiles = (readFiles + other.readFiles).distinct(),
        modifiedFiles = (modifiedFiles + other.modifiedFiles).distinct(),
    )

    fun renderTags(): String = buildString {
        if (readFiles.isNotEmpty()) {
            appendLine("<read-files>")
            readFiles.forEach { appendLine(it) }
            appendLine("</read-files>")
        }
        if (modifiedFiles.isNotEmpty()) {
            appendLine("<modified-files>")
            modifiedFiles.forEach { appendLine(it) }
            appendLine("</modified-files>")
        }
    }.trim()

    companion object {
        private val READ_TOOLS = setOf("read", "download")
        private val MODIFY_TOOLS = setOf("write", "edit")

        /** 从被摘要消息的工具调用中提取文件操作（read/download → 读；write/edit → 改）。 */
        fun extractFrom(messages: List<HarnessMessage>): FileOperations {
            val reads = mutableListOf<String>()
            val modifies = mutableListOf<String>()
            messages.filterIsInstance<ToolCall>().forEach { call ->
                val name = (call.rawToolName ?: HarnessApiMapper.apiName(call.tool)).lowercase()
                val path = runCatching {
                    call.args["path"]?.jsonPrimitive?.contentOrNull
                        ?: call.args["file"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim().orEmpty()
                if (path.isEmpty()) return@forEach
                when {
                    name in READ_TOOLS -> reads += path
                    name in MODIFY_TOOLS -> modifies += path
                }
            }
            return FileOperations(reads.distinct(), modifies.distinct())
        }

        private val TAG_BLOCK = Regex(
            "<(read-files|modified-files)>\\s*([\\s\\S]*?)\\s*</\\1>",
        )

        /** 从上一份结构化摘要中解析既有文件清单，实现跨压缩累计。 */
        fun parseFromSummary(summary: String?): FileOperations {
            if (summary.isNullOrBlank()) return FileOperations()
            var reads = emptyList<String>()
            var modifies = emptyList<String>()
            TAG_BLOCK.findAll(summary).forEach { match ->
                val paths = match.groupValues[2].lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                when (match.groupValues[1]) {
                    "read-files" -> reads = paths
                    "modified-files" -> modifies = paths
                }
            }
            return FileOperations(reads, modifies)
        }
    }
}

/**
 * cache-replay 摘要请求的纯函数组装器：只依赖入参与常量，便于直接单测请求形状。
 *
 * 请求形状：[system] + [摘要层] + 被折叠前缀（与主请求同投影口径）+ 末尾压缩指令。
 * 被折叠前缀与主对话实际发送的字节一致（截断已发生过、召回后缀同口径追加），
 * 这是摘要请求命中 provider KV 缓存的前提。
 */
internal object SummaryReplayRequests {
    const val DEFAULT_SUMMARY_MAX_TOKENS = 4_096
    const val DEFAULT_COMPACTION_BUDGET_TOKENS = 128_000

    fun buildReplayInstruction(hasPreviousSummaries: Boolean): String = buildString {
        appendLine("你是会话压缩器。任务：把本条消息之前的全部对话历史压缩为一份结构化摘要，供同一会话的后续轮次继续使用。")
        appendLine("该摘要将替代以上历史注入后续请求，必须自包含。")
        appendLine()
        appendFormatSection(this, null)
        if (hasPreviousSummaries) {
            appendLine()
            appendLine("注意：上方上下文（系统提示中的摘要层）已包含此前的压缩摘要，请把其中的关键信息合并进新摘要，不要丢失未完成事项与决策。")
        }
        appendLine()
        appendLine("现在只输出摘要本身，不要任何寒暄、说明或代码块包裹。")
    }

    fun appendFormatSection(sb: StringBuilder, previousInline: String?) {
        sb.appendLine("输出格式（严格遵守，只输出摘要本身，不要任何寒暄或代码块包裹）：")
        sb.appendLine("## 目标")
        sb.appendLine("[用户想完成什么]")
        sb.appendLine("## 约束与偏好")
        sb.appendLine("- [用户明确提出的要求、偏好与禁区]")
        sb.appendLine("## 进度")
        sb.appendLine("### 已完成")
        sb.appendLine("- [x] [已完成的任务]")
        sb.appendLine("### 进行中")
        sb.appendLine("- [ ] [当前正在做的事]")
        sb.appendLine("### 受阻")
        sb.appendLine("- [问题与原因，无则省略该小节]")
        sb.appendLine("## 关键决策")
        sb.appendLine("- **[决策]**：[理由]")
        sb.appendLine("## 下一步")
        sb.appendLine("1. [接下来应该做什么]")
        sb.appendLine("## 关键上下文")
        sb.appendLine("- [继续工作必需的数据：路径、命令输出要点、错误信息、版本号等]")
        if (previousInline != null) {
            sb.appendLine()
            sb.appendLine("【此前的压缩摘要】（请把其中的关键信息合并进新摘要，不要丢失未完成事项与决策）：")
            sb.appendLine(previousInline)
        }
    }

    /** 返回 null = 估算超出模型窗口（小窗口模型），应回退独立叙事路径。 */
    fun buildReplayRequest(
        model: ModelConfig,
        previousSummaries: List<String>,
        requestContext: SummaryRequestContext,
    ): List<ApiMessage>? {
        val converted = ContextWindowPolicy.shrinkApiMessagesToByteBudget(
            ImagePayloadCompressor.downscale(
                ApiMessageProjector.project(
                    msgs = requestContext.replayPrefix,
                    toolCallMode = requestContext.toolCallMode,
                    visionEnabled = requestContext.visionEnabled,
                    recallSuffixes = requestContext.recallBlocks,
                ),
            ),
        )
        if (converted.isEmpty()) return null
        val request = buildList {
            if (requestContext.systemPrompt.isNotBlank()) {
                add(ApiMessage(role = "system", content = requestContext.systemPrompt))
            }
            if (requestContext.summaryLayer.isNotBlank()) {
                add(ApiMessage(role = "system", content = requestContext.summaryLayer))
            }
            addAll(converted)
            add(ApiMessage(role = "user", content = buildReplayInstruction(previousSummaries.isNotEmpty())))
        }
        val budgetTokens = ContextWindowPolicy.clampedBudget(
            model.contextTokens,
            DEFAULT_COMPACTION_BUDGET_TOKENS,
            modelId = model.model,
            providerId = model.provider,
        )
        val estimated = estimateRequestTokens(request) + DEFAULT_SUMMARY_MAX_TOKENS
        return if (estimated <= budgetTokens) request else null
    }

    /** 与主请求路径相同的请求 token 估算口径（含 tool_calls 参数与图片）。 */
    private fun estimateRequestTokens(request: List<ApiMessage>): Int = request.sumOf { message ->
        ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
            ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) +
            message.tool_calls.orEmpty().sumOf { call ->
                ContextWindowPolicy.estimateTokens(call.function.name) +
                    ContextWindowPolicy.estimateTokens(call.function.arguments)
            } +
            message.imageUrls.size * ContextWindowPolicy.ESTIMATED_IMAGE_TOKENS
    }
}

/**
 * LLM 结构化压缩摘要器（对齐 pi compaction.ts 的 generateSummary）。
 *
 * 与机械摘要（[top.tianyan.app.harness.ContextWindowPolicy.buildHistorySummary]）的关系：
 * 本类生成 pi 风格的结构化摘要（目标/约束/进度/关键决策/下一步/关键上下文 + 文件清单），
 * 上一次摘要作为迭代上下文传入，天然实现滚动合并；任何失败（网络/解析/空输出）返回 null，
 * 由调用方回退到机械摘要，保证压缩永不因摘要失败而中断。
 *
 * 请求形状（cache-replay，对齐 Reasonix）：优先重放主对话的真实前缀——原 system prompt、
 * 原摘要层、与主请求字节一致的原始消息序列——只在末尾追加一条压缩指令。这样摘要请求的
 * 输入几乎全部命中 provider 侧 KV 缓存（这些前缀字节刚在上一轮主请求里发送过），
 * 长会话压缩的输入成本比独立叙事请求低约一个量级。重放不可行或失败时回退
 * 独立叙事请求（自带 SYSTEM_PROMPT + 序列化文本），保证摘要永不因形状问题失败。
 */
class CompactionSummarizer(
    private val providerClient: ProviderClient,
) {
    /** 结构化摘要提示词（对齐 pi 的 Summary Format）。 */
    internal fun buildPrompt(serialized: String, previousSummaries: List<String>): String {
        val previous = previousSummaries
            .filter { it.isNotBlank() }
            .joinToString("\n\n---\n\n")
            .takeIf { it.isNotEmpty() }
        return buildString {
            appendLine("你是会话压缩器。请把下面的对话历史压缩为一份结构化摘要，供同一会话的后续轮次继续使用。")
            appendLine("摘要将被注入后续请求作为早期历史的唯一替代，必须自包含。")
            appendLine()
            SummaryReplayRequests.appendFormatSection(this, previous)
            appendLine()
            appendLine("【待压缩的对话历史】：")
            appendLine(serialized)
        }
    }

    /**
     * 生成结构化摘要；失败返回 null（调用方回退机械摘要）。
     * 文件操作清单不依赖模型自觉：从工具调用确定性提取，并与上一份摘要累计后追加到末尾。
     */
    suspend fun generateSummary(
        model: ModelConfig,
        messages: List<HarnessMessage>,
        previousSummaries: List<String> = emptyList(),
        requestContext: SummaryRequestContext? = null,
    ): String? {
        if (messages.isEmpty()) return null
        // Cache-replay 优先：重放与主请求字节一致的前缀，输入几乎全部命中 KV 缓存。
        if (requestContext != null && requestContext.replayPrefix.isNotEmpty()) {
            SummaryReplayRequests.buildReplayRequest(model, previousSummaries, requestContext)?.let { replay ->
                requestSummaryText(summaryModelOf(model), replay)?.let { body ->
                    return finalizeSummary(body, messages, previousSummaries)
                }
                // 重放失败（网络/被拒/输出过短）→ 回退独立叙事请求
            }
        }
        val serialized = ConversationText.serialize(messages, maxChars = serializedCharCap(model, previousSummaries))
        if (serialized.isBlank()) return null
        val prompt = buildPrompt(serialized, previousSummaries)
        val body = requestSummaryText(summaryModelOf(model), listOf(
            ApiMessage(role = "system", content = SYSTEM_PROMPT),
            ApiMessage(role = "user", content = prompt),
        )) ?: return null
        return finalizeSummary(body, messages, previousSummaries)
    }

    private suspend fun requestSummaryText(model: ModelConfig, request: List<ApiMessage>): String? {
        val result = runCatching { providerClient.chat(model, request) }.getOrNull() ?: return null
        val body = result.content?.trim().orEmpty()
        return body.takeIf { it.length >= MIN_SUMMARY_CHARS }
    }

    private fun finalizeSummary(
        body: String,
        messages: List<HarnessMessage>,
        previousSummaries: List<String>,
    ): String {
        val files = FileOperations.parseFromSummary(previousSummaries.joinToString("\n\n"))
            .mergedWith(FileOperations.extractFrom(messages))
        val fileTags = files.renderTags()
        return if (fileTags.isEmpty()) body else "$body\n\n$fileTags"
    }

    private fun summaryModelOf(model: ModelConfig): ModelConfig = model.copy(
        // 摘要是一次性请求：输出上限固定为温和值，不透传主对话可能配置的极小 maxTokens
        maxTokens = minOf(model.maxTokens ?: SummaryReplayRequests.DEFAULT_SUMMARY_MAX_TOKENS, SummaryReplayRequests.DEFAULT_SUMMARY_MAX_TOKENS),
        pureChatMode = false,
    )

    /**
     * 摘要请求的序列化字符上限，按目标模型窗口推导。全局 240k 字符上限对 8k 窗口模型
     * 必然溢出——最需要压缩的小预算场景恰恰是 LLM 摘要永远失败、长期降级为机械摘要的场景。
     * 字符/token 取 1.5（CJK 1.8、ASCII 2.5 的保守下界），先扣除系统提示、格式指令与
     * 既有摘要的估算占用，再预留摘要输出空间。
     */
    internal fun serializedCharCap(model: ModelConfig, previousSummaries: List<String>): Int {
        val budgetTokens = ContextWindowPolicy.clampedBudget(
            model.contextTokens,
            SummaryReplayRequests.DEFAULT_COMPACTION_BUDGET_TOKENS,
        )
        val reservedTokens = ContextWindowPolicy.estimateTokens(SYSTEM_PROMPT) +
            ContextWindowPolicy.estimateTokens(buildPrompt("", previousSummaries)) +
            SummaryReplayRequests.DEFAULT_SUMMARY_MAX_TOKENS
        val inputTokens = (budgetTokens - reservedTokens).coerceAtLeast(MIN_PROMPT_INPUT_TOKENS)
        return (inputTokens * SERIALIZED_CHARS_PER_TOKEN).toInt()
            .coerceIn(MIN_SERIALIZED_CHARS_CAP, ConversationText.MAX_SERIALIZED_CHARS)
    }

    private companion object {
        const val MIN_SUMMARY_CHARS = 60

        /** 小窗口模型下摘要输入的最低保障（tokens）；再小就交给机械摘要兜底。 */
        const val MIN_PROMPT_INPUT_TOKENS = 2_000
        const val MIN_SERIALIZED_CHARS_CAP = 3_000
        const val SERIALIZED_CHARS_PER_TOKEN = 1.5f
        const val SYSTEM_PROMPT =
            "你是会话压缩器。任务：把长对话历史压缩为结构化摘要。只输出摘要正文，" +
                "保留全部关键信息（目标、约束、进度、决策、下一步、关键上下文），不要复述无关细节。"
    }
}

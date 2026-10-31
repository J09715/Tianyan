package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure context-budget and historical-folding policy used by the provider mapper and UI. */
object ContextWindowPolicy {
    internal const val RESERVED_OUTPUT_TOKENS = 8_192
    /** Provider tools schema 预留；以 estimateToolDefinitionTokens 实测校准，工具变更后运行一致性测试。 */
    internal const val TOOL_SCHEMA_RESERVE_TOKENS = 7_200
    private const val MAX_SYSTEM_PROMPT_FRACTION = 0.60
    private const val MIN_SYSTEM_PROMPT_TOKENS = 512
    /** 超过此 token 数的用户消息才参与巨型消息截断（普通消息交给折叠线，避免误伤）。 */
    private const val MIN_GIANT_USER_MESSAGE_TOKENS = 2_000
    /** 巨型用户消息截断后至少保留的头部 token 数。 */
    private const val MIN_KEPT_USER_TURN_TOKENS = 800
    /** 与设置页滑块对齐，并为 1M+ 标称窗口保留余量。 */
    const val MAX_CONTEXT_BUDGET = 2_000_000
    /**
     * 发给 Provider 的 JSON 请求体物理上限（字节）。Token 预算看不见 UTF-8/JSON 转义膨胀，
     * 中转 Nginx 默认 `client_max_body_size` 往往只有 1–10MB，超限即 HTTP 413。
     */
    const val REQUEST_BODY_HARD_LIMIT_BYTES = 4 * 1024 * 1024
    /** 当前轮超长工具输出在体积治理时保留的头部/尾部字符。 */
    private const val ACTIVE_TOOL_KEEP_HEAD_CHARS = 6_000
    private const val ACTIVE_TOOL_KEEP_TAIL_CHARS = 1_500
    private const val JSON_FRAMING_PER_MESSAGE = 64
    /**
     * 历史折叠比例默认值（%）。长窗口另有 90% 安全上限，防止估算误差耗尽窗口。
     * 调低该值可让长会话更早折叠以节省 input token 成本。
     */
    const val DEFAULT_FOLDING_RATIO_PERCENT = 100
    /** 折叠线比例下限：低于此值会频繁折叠，历史几乎留不住。 */
    const val MIN_FOLDING_RATIO_PERCENT = 10
    /** 折叠线比例上限。 */
    const val MAX_FOLDING_RATIO_PERCENT = 100

    /**
     * 图片的保守 token 估算（每张）。Claude 实际 ~1600/图，取高值方向安全：
     * 低估会让请求溢出 provider 400，高估只是折叠稍早。
     */
    const val ESTIMATED_IMAGE_TOKENS = 1_600

    /**
     * 历史折叠触发线（token），供 UI 预览与 [computeKeepFromIndex] 共用。
     *
     * 长窗口先留 10% 估算/网关余量，再减输出、工具 schema 和 system 占用。
     * 用户更低的折叠比例仍优先；旧设置 100% 同样受安全余量约束。
     *
     * 返回值可能为负（小预算被预留吃光）——引擎据此走最小保留兜底；UI 预览应 `coerceAtLeast(0)`。
     */
    fun foldingLimitFor(
        budget: Int,
        ratioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        systemTokens: Int = 0,
        reserveTokens: Int? = null,
        /**
         * 本次请求要预留的工具 schema token，用 [toolSchemaReserveTokensFor] 按模式取。
         * 默认值即满预留，漏传的调用点只会多留（折叠略早），不会少留。
         */
        toolSchemaReserveTokens: Int = TOOL_SCHEMA_RESERVE_TOKENS,
    ): Int {
        if (budget <= 0) return 0
        val safeRatio = ratioPercent.coerceIn(MIN_FOLDING_RATIO_PERCENT, if (budget >= 64_000) 90 else MAX_FOLDING_RATIO_PERCENT)
        val scaledBudget = (budget.toLong() * safeRatio / 100L).toInt()
        return scaledBudget -
            systemTokens.coerceAtLeast(0) -
            (reserveTokens ?: RESERVED_OUTPUT_TOKENS) -
            toolSchemaReserveTokens.coerceAtLeast(0)
    }

    /**
     * 按调用模式取工具 schema 预留，模式布尔与 [estimateReservedPromptTokens] 同一组。
     *
     * NATIVE（独立 tools 数组）与 JSON_TEXT（schema 以文本注入 system/instructions）都会把
     * schema 真正塞进请求，必须预留；纯聊天与工具禁用模式下 provider 面没有任何 tools，
     * 预留这段只会白占历史预算、让折叠线无谓提前（这段空间本该留给历史）。
     */
    fun toolSchemaReserveTokensFor(pureChat: Boolean, toolDisabled: Boolean): Int =
        if (pureChat || toolDisabled) 0 else TOOL_SCHEMA_RESERVE_TOKENS

    /**
     * Compaction threshold (in characters) per tool type. `read`/`base` commonly
     * produce legitimately long output, so they get a higher bar; file mutations
     * and listings are compressed aggressively.
     */
    fun compactThresholdFor(toolName: String?): Int = when (toolName?.trim()?.lowercase()) {
        "read" -> 800
        "base", "download" -> 400
        "write", "edit" -> 200
        "process" -> 300
        else -> 240
    }

    /**
     * Tool-aware historical output compaction. Preserves the structurally important
     * parts of each tool family instead of applying one blind head/tail truncation.
     */
    fun compactToolOutput(toolName: String?, args: JsonObject?, output: String, success: Boolean): String {
        val statusLabel = if (success) "成功" else "失败"
        val header = "【历史执行结果·状态:$statusLabel】"
        val name = toolName?.trim()?.lowercase().orEmpty()
        val body = when (name) {
            "read" -> compactRead(args, output)
            "write", "edit" -> output.take(160) + "…[文件操作结果已压缩]"
            "process" -> compactList(output, 4, 2, "列表")
            "base", "download" -> compactCommand(output)
            else -> compactGeneric(output)
        }
        return "$header\n$body"
    }

    private fun compactRead(args: JsonObject?, output: String): String {
        val path = runCatching { args?.get("path")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val pathHint = path?.let { "（文件: $it）" }.orEmpty()
        val lines = output.lines()
        return if (lines.size > 12) {
            lines.take(6).joinToString("\n") +
                "\n... [历史 read 输出已压缩，省略 ${lines.size - 10} 行]$pathHint ...\n" +
                lines.takeLast(4).joinToString("\n")
        } else {
            output.take(600)
        }
    }

    private fun compactCommand(output: String): String {
        val lines = output.lines()
        return if (lines.size > 10) {
            lines.take(4).joinToString("\n") +
                "\n... [历史命令输出已压缩，省略 ${lines.size - 8} 行] ...\n" +
                lines.takeLast(4).joinToString("\n")
        } else {
            output.take(500)
        }
    }

    private fun compactList(output: String, head: Int, tail: Int, label: String): String {
        val lines = output.lines()
        return if (lines.size > head + tail + 2) {
            lines.take(head).joinToString("\n") +
                "\n... [$label 已压缩，省略 ${lines.size - head - tail} 项] ...\n" +
                lines.takeLast(tail).joinToString("\n")
        } else {
            output.take(300)
        }
    }

    private fun compactGeneric(output: String): String {
        val lines = output.lines()
        return if (lines.size > 6) {
            lines.take(3).joinToString("\n") +
                "\n... [历史工具输出已压缩，已略去 ${lines.size - 5} 行日志] ...\n" +
                lines.takeLast(2).joinToString("\n")
        } else {
            output.take(180) + "... [已自动压缩]"
        }
    }

    /**
     * 老轮次工具结果截断时原样保留的最近结果条数（下限兜底）。
     *
     * 主保护是轮次语义：最后一条用户消息之后的所有结果（活跃任务的全部工作轮，
     * 可达 maxToolsPerRound 条并行调用）一律原样。此下限只服务新用户消息到达后的
     * 「继续」式续跑——保留最近几条让模型知道当前状态；更早的结果压缩后头部仍
     * 保留关键状态（如浏览器快照 JSON 开头的 tab/url），全文可 history_read 回读。
     */
    const val KEEP_RECENT_TOOL_RESULTS = 4

    /**
     * 组装请求前的老轮次工具结果截断。
     *
     * 预算驱动的压缩（computeKeepFromIndex）只有越过 token 预算线才触发，
     * 在此之前每个历史轮的大输出（如浏览器快照、长 read）都会原样重复发送：
     * 一轮 2.7KB 的快照随对话增长累积，既烧 token 又稀释注意力。
     *
     * 策略：受保护 = 最后一条用户消息之后的全部结果（当前轮）∪ 最近
     * [keepRecentResults] 条结果（steering 兜底）；其余结果超过
     * [compactThresholdFor] 阈值时按 [compactToolOutput] 语义压缩，并附
     * history_read 指针。不改变消息条数与顺序（NATIVE 协议下丢消息会产生
     * 非法 transcript），只替换输出正文。
     *
     * @param toolCallDetails toolCallId → (工具名, 参数)，用于按工具类型选阈值与压缩形态
     * @return 变换后的消息列表；无命中时原样返回同一实例（避免无谓复制）
     */
    fun truncateStaleToolResults(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>>,
        keepRecentResults: Int = KEEP_RECENT_TOOL_RESULTS,
    ): List<HarnessMessage> {
        if (messages.isEmpty()) return messages
        val lastUserIndex = messages.indexOfLast { it is UserMessage }
        val protectedIds = messages.filterIsInstance<ToolResult>()
            .takeLast(keepRecentResults.coerceAtLeast(0))
            .mapTo(mutableSetOf()) { it.id }
        // 当前工作轮的结果（最后一条用户消息之后）全部保护：模型下一步决策就靠它们，
        // 一轮并行调用可达 maxToolsPerRound 条，固定条数覆盖不了。
        messages.forEachIndexed { index, message ->
            if (index > lastUserIndex && message is ToolResult) protectedIds.add(message.id)
        }
        var changed = false
        val transformed = messages.map { message ->
            if (message !is ToolResult || message.id in protectedIds) return@map message
            val (name, args) = toolCallDetails[message.toolCallId] ?: (null to null)
            if (message.output.length <= compactThresholdFor(name)) return@map message
            changed = true
            message.copy(
                output = compactToolOutput(name, args, message.output, message.success) +
                    "\n[老轮次工具输出已压缩；全文在会话记录中，需要细节时调用 history_read(message_id=\"${message.id}\") 回读]",
            )
        }
        return if (changed) transformed else messages
    }

    /**
     * 与引擎实际请求同口径的「投影」：把 UI 的全量消息投影成**真正会发送的那份**，
     * 供用量面板估算使用。复用历史结果截断与大写入参数缩减，并把工具名映射
     * （[HarnessApiMapper]）一并收敛，避免调用方各写一遍导致口径再次分化。
     *
     * 为何需要它：引擎在压缩判定前先截断老轮次工具结果（见 ApiContextAssembler），
     * 面板若直接拿未截断的全量消息估算，会明显虚高（实测 457.8K vs 实际发送 ~141K，约 3 倍）。
     *
     * @param compactionEnabled 关闭压缩时不做任何截断（用户要原始历史）。
     */
    fun projectForUsage(
        messages: List<HarnessMessage>,
        compactionEnabled: Boolean,
    ): List<HarnessMessage> {
        if (!compactionEnabled) return messages
        val toolCallDetails = messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }
        return HistoricalToolArguments.project(truncateStaleToolResults(messages, toolCallDetails))
    }

    /** Conservative multilingual estimate used when a provider tokenizer is unavailable. */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var ascii = 0
        var punctuation = 0
        text.forEach { ch ->
            when {
                // 全角区（FF00-FFEF，如 ，！？：）与 CJK 区同价：中文标点实际 ~1 token/字，
                // 落入 ASCII 标点桶按 /2.8 估算会对中文上下文系统性低估
                ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xFF00..0xFFEF -> cjk++
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> ascii++
                else -> punctuation++
            }
        }
        return (cjk / 1.8f + ascii / 2.5f + punctuation / 2.8f).toInt().coerceAtLeast(1)
    }

    /**
     * Provider 可见工具 schema 的 token 估算（与 [SystemPromptBuilder] 用量快照同一算法）。
     *
     * 为什么需要它：tools 数组不在这批 ApiMessage 里，任何只统计 messages 的估算
     * （[estimateApiMessages]、[outputBudget]）都必须显式把它加回来，否则真实输入
     * 会比估算多出整个 schema 的量，把折叠线余量悄悄吃掉，最终以 400/413 溢出。
     */
    fun estimateToolDefinitionTokens(tools: List<ApiToolDefinition>): Int = tools.sumOf { tool ->
        estimateTokens(tool.function.name) +
            estimateTokens(tool.function.description) +
            estimateTokens(tool.function.parameters.toString()) +
            4
    }

    /**
     * Central estimate for the provider-facing ApiMessage representation.
     *
     * @param toolSchemaTokens 同一次请求里 tools 数组的 token（见 [estimateToolDefinitionTokens]）。
     *   非 NATIVE 模式或 schema 已内联进 messages 时传 0，避免重复计数。
     */
    fun estimateApiMessages(messages: List<ApiMessage>, toolSchemaTokens: Int = 0): Int =
        toolSchemaTokens.coerceAtLeast(0) + messages.sumOf { message ->
            // Keep a small per-message framing allowance; provider tokenizers count role and
            // content-part markers too, while our fallback tokenizer cannot see them.
            4 + (message.responsesTurn?.payloadBytes?.div(3) ?: 0) + estimateTokens(message.content.orEmpty()) +
                estimateTokens(message.reasoning_content.orEmpty()) +
                message.imageUrls.sumOf { image ->
                    if (image.startsWith("data:image/", ignoreCase = true)) {
                        (image.length / 3).coerceAtLeast(ESTIMATED_IMAGE_TOKENS)
                    } else ESTIMATED_IMAGE_TOKENS
                } +
                message.tool_calls.orEmpty().sumOf { call ->
                    estimateTokens(call.function.name) + estimateTokens(call.function.arguments) + 4
                }
        }

    /** Runtime-safe profile value: null and non-positive values mean provider default. */
    fun normalizeOutputTokens(value: Int?, providerDefault: Int): Int =
        (value?.takeIf { it > 0 } ?: providerDefault).coerceAtLeast(1)
    /**
     * One output budget calculation used by all provider request builders.
     *
     * @param toolSchemaTokens 同次请求的 tools token，必须计入输入占用。
     */
    fun outputBudget(
        configured: Int?,
        providerDefault: Int,
        messages: List<ApiMessage>,
        contextTokens: Int?,
        modelId: String? = null,
        providerId: String? = null,
        toolSchemaTokens: Int = 0,
    ): Int {
        val requested = normalizeOutputTokens(configured, providerDefault)
        val resolvedContext = contextTokens ?: ModelContextWindows.resolve(modelId, providerId)
        val context = resolvedContext?.takeIf { it > 0 }?.coerceIn(1, MAX_CONTEXT_BUDGET)
            ?: return requested
        val available = context - estimateApiMessages(messages, toolSchemaTokens)
        requireOutputHeadroom(available, requested)
        return minOf(requested, available).coerceAtLeast(1)
    }

    const val DEFAULT_SYSTEM_PROMPT_TOKENS = 576
    const val DEFAULT_NATIVE_TOOL_TOKENS = 3_600
    const val DEFAULT_RULES_TOKENS = 1_400
    const val DEFAULT_SUBAGENT_TOKENS = 1_100
    /**
     * Resolve the model context window. Explicit model-profile `contextTokens` wins;
     * otherwise mainstream model metadata is used. Null means the caller should fall
     * back to the global budget.
     */
    fun resolveContextWindow(
        declaredContextTokens: Int?,
        modelId: String?,
        providerId: String? = null,
    ): Int? = declaredContextTokens ?: ModelContextWindows.resolve(modelId, providerId)

    /**
     * Resolve the effective context budget for occupancy and compaction.
     * The final fallback is the user-configured global budget.
     */
    fun resolveBudget(
        profileContextTokens: Int?,
        defaultBudget: Int,
        modelId: String? = null,
        providerId: String? = null,
    ): Int = (resolveContextWindow(profileContextTokens, modelId, providerId) ?: defaultBudget)
        .coerceAtLeast(1)

    /**
     * 会话占用判定（SessionModelSwitcher）与实际请求组装（ApiContextAssembler）共用的
     * 预算钳制：先按当前模型元数据/全局回退取值，再统一钳制到 [1, MAX_CONTEXT_BUDGET]。
     * 两处必须走同一口径，否则会出现"切换模型判定无需压缩、实际请求又压缩"。
     */
    fun clampedBudget(
        profileContextTokens: Int?,
        defaultBudget: Int,
        modelId: String? = null,
        providerId: String? = null,
    ): Int = resolveBudget(profileContextTokens, defaultBudget, modelId, providerId)
        .coerceIn(1, MAX_CONTEXT_BUDGET)

    fun estimateReservedPromptTokens(
        pureChat: Boolean,
        toolDisabled: Boolean,
        skillTokens: Int = 0,
        mcpTokens: Int = 0,
        summaryTokens: Int = 0,
    ): Int {
        if (pureChat) return summaryTokens
        // Native tool schema is reserved separately inside [foldingLimitFor]; do not
        // double-count it here, otherwise model switches and settings previews fold too early.
        val extraToolTokens = if (toolDisabled) 0 else DEFAULT_SUBAGENT_TOKENS
        return DEFAULT_SYSTEM_PROMPT_TOKENS + DEFAULT_RULES_TOKENS + extraToolTokens + skillTokens + mcpTokens + summaryTokens
    }

    /** Estimate the payload after the same token-budget compaction used by [HarnessLoop]. */
    fun estimateEffectiveUsage(
        messages: List<HarnessMessage>,
        budget: Int,
        systemTokens: Int,
        compactionEnabled: Boolean,
        systemPromptTokens: Int = 0,
        toolDefinitionTokens: Int = 0,
        rulesTokens: Int = 0,
        skillsTokens: Int = 0,
        mcpTokens: Int = 0,
        subagentTokens: Int = 0,
        /** 历史折叠线比例（%，默认 [DEFAULT_FOLDING_RATIO_PERCENT]）。透传给 computeKeepFromIndex。 */
        foldingRatioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        /** 工具 schema 预留，透传给 computeKeepFromIndex（见 [toolSchemaReserveTokensFor]）。 */
        toolSchemaReserveTokens: Int = TOOL_SCHEMA_RESERVE_TOKENS,
    ): EffectiveContextUsage {
        val keepFrom = if (compactionEnabled) {
            computeKeepFromIndex(
                messages,
                budget,
                systemTokens,
                foldingRatioPercent = foldingRatioPercent,
                toolSchemaReserveTokens = toolSchemaReserveTokens,
            )
        } else {
            0
        }
        val summarizedTokens = if (keepFrom > 0) {
            estimateTokens(buildHistorySummary(messages.take(keepFrom)))
        } else {
            0
        }
        var conversationTokens = 0
        var toolTokens = 0
        messages.drop(keepFrom).forEach { message ->
            when (message) {
                is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion, is TaskDelivery, is TurnChangesEvent -> Unit
                is UserMessage -> {
                    conversationTokens += estimateTokens(message.text) + message.imageUrls.size * ESTIMATED_IMAGE_TOKENS
                }
                is AssistantText -> {
                    conversationTokens += estimateTokens(assistantTextForContext(message.text)) +
                        estimateTokens(message.reasoning.orEmpty()) + (message.responsesTurn?.payloadBytes?.div(3) ?: 0)
                }
                is ToolCall -> {
                    toolTokens += estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
                }
                is ToolResult -> {
                    toolTokens += estimateTokens(message.output)
                }
            }
        }
        val resolvedSystemPromptTokens = if (systemPromptTokens == 0 && toolDefinitionTokens == 0 &&
            rulesTokens == 0 && skillsTokens == 0 && mcpTokens == 0 && subagentTokens == 0 && systemTokens > 0
        ) {
            systemTokens
        } else {
            systemPromptTokens
        }
        val breakdown = ContextUsageBreakdown(
            systemPromptTokens = resolvedSystemPromptTokens,
            toolDefinitionTokens = toolDefinitionTokens,
            rulesTokens = rulesTokens,
            skillsTokens = skillsTokens,
            mcpTokens = mcpTokens,
            subagentTokens = subagentTokens,
            summarizedTokens = summarizedTokens,
            conversationTokens = conversationTokens + toolTokens,
        )
        val computedTotal = if (breakdown.totalTokens > 0) {
            breakdown.totalTokens
        } else {
            systemTokens + conversationTokens + toolTokens + summarizedTokens
        }
        return EffectiveContextUsage(
            keepFromIndex = keepFrom,
            conversationTokens = conversationTokens,
            toolTokens = toolTokens,
            totalTokens = computedTotal,
            breakdown = breakdown,
        )
    }

    fun computeKeepFromIndex(
        messages: List<HarnessMessage>,
        budget: Int,
        systemTokens: Int,
        /**
         * 压缩时保留的最近 token 上限（对齐 pi keepRecentTokens，默认 0 = 不启用）。
         * 仅在压缩触发（预算边界 > 0）时生效：保留历史不超过该值，更早的进入摘要。
         */
        keepRecentTokens: Int = 0,
        /**
         * 为 LLM 响应预留的 token（对齐 pi reserveTokens）。null = 使用内置默认；
         * 每模型覆盖时替换全局保留值参与预算线计算。
         */
        reserveTokens: Int? = null,
        /**
         * 历史折叠线比例（%，默认 [DEFAULT_FOLDING_RATIO_PERCENT]）。
         * 长窗口最高按 90% 计算，再扣输出、工具与系统预留；更低的用户设置仍优先。
         */
        foldingRatioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        /** 工具 schema 预留，透传给 [foldingLimitFor]（见 [toolSchemaReserveTokensFor]）。 */
        toolSchemaReserveTokens: Int = TOOL_SCHEMA_RESERVE_TOKENS,
    ): Int {
        if (messages.size <= 1) return 0
        if (budget <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        val safeRatio = foldingRatioPercent.coerceIn(MIN_FOLDING_RATIO_PERCENT, MAX_FOLDING_RATIO_PERCENT)
        // 与 [foldingLimitFor] 同一条线：按模型窗口和用户比例计算，再减 system/输出/工具 schema 预留。
        val limit = foldingLimitFor(
            budget = budget,
            ratioPercent = safeRatio,
            systemTokens = systemTokens,
            reserveTokens = reserveTokens,
            toolSchemaReserveTokens = toolSchemaReserveTokens,
        )
        // 小预算模型的 rawLimit 必须能落到 <= 0，才能触发最小保留兜底；
        // 不再对 limit 做下限抬升，否则历史折叠永不触发。
        if (limit <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        var used = 0
        for (index in messages.indices.reversed()) {
            val tokens = tokensOf(messages[index])
            if (used + tokens > limit) {
                // 强制保留最近 2 条（即使某条自身超 limit），避免全折叠导致失忆
                val candidate = (index + 1).coerceIn(0, messages.lastIndex)
                    .coerceAtMost((messages.size - 2).coerceAtLeast(0))
                var boundary = alignKeepFromIndex(messages, candidate)
                // Split-turn（对齐 pi）：单个用户轮次自身超预算时，按用户轮次对齐会把
                // 整个巨型轮次保留下来，kept 仍超限，下一次请求必然溢出。
                // 此刻降级为轮内切割：切点回退到最近的合法边界（assistant 消息或工具调用），
                // 工具调用/结果配对由 closeToolPairs 保证不被拆散。
                if (keptTokens(messages, boundary) > limit) {
                    val splitBoundary = alignSplitTurnBoundary(messages, candidate)
                    if (splitBoundary > 0) boundary = splitBoundary
                }
                return applyKeepRecentCap(messages, boundary, keepRecentTokens, limit)
            }
            used += tokens
        }
        return 0
    }

    /**
     * 压缩触发后的 keepRecentTokens 收紧（对齐 pi：压缩时保留最近 N token，更早的进摘要）。
     * 取预算边界与 keepRecent 边界中更激进者；收紧后若超出预算线则放弃收紧（预算优先）。
     */
    private fun applyKeepRecentCap(
        messages: List<HarnessMessage>,
        keepFrom: Int,
        keepRecentTokens: Int,
        limit: Int,
    ): Int {
        if (keepRecentTokens <= 0 || keepFrom <= 0) return keepFrom
        var used = 0
        for (index in messages.indices.reversed()) {
            used += tokensOf(messages[index])
            if (used > keepRecentTokens) {
                val keepRecentBoundary = alignSplitTurnBoundary(messages, index + 1).coerceAtLeast(1)
                val tightened = maxOf(keepRecentBoundary, keepFrom)
                return if (keptTokens(messages, tightened) > limit) keepFrom else tightened
            }
        }
        return keepFrom
    }

    internal fun tokensOf(message: HarnessMessage): Int = when (message) {
        is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion, is TaskDelivery, is TurnChangesEvent -> 0
        is UserMessage -> estimateTokens(message.text) + message.imageUrls.size * ESTIMATED_IMAGE_TOKENS
        is AssistantText -> estimateTokens(assistantTextForContext(message.text)) +
            estimateTokens(message.reasoning.orEmpty()) + (message.responsesTurn?.payloadBytes?.div(3) ?: 0)
        is ToolResult -> estimateTokens(message.modelOutput())
        is ToolCall -> estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
    }

    private fun keptTokens(messages: List<HarnessMessage>, keepFrom: Int): Int =
        messages.drop(keepFrom).sumOf(::tokensOf)

    /**
     * Split-turn 轮内切割：从 [candidate] 向后回退到最近的合法切点。
     * 合法切点 = user / assistant / tool_call 消息（绝不切在 tool_result 上，
     * 否则结果与其调用分离，产生非法 transcript）。
     */
    private fun alignSplitTurnBoundary(messages: List<HarnessMessage>, candidate: Int): Int {
        var boundary = candidate.coerceIn(0, messages.lastIndex)
        while (boundary > 0 && messages[boundary] !is UserMessage &&
            messages[boundary] !is AssistantText && messages[boundary] !is ToolCall
        ) {
            boundary--
        }
        if (boundary <= 0) return 0
        return closeToolPairs(messages, boundary)
    }

    private fun minimalKeepFromIndex(messages: List<HarnessMessage>): Int {
        val lastUser = messages.indexOfLast { it is UserMessage }
        val candidate = when {
            lastUser >= 0 -> lastUser
            messages.size > 1 -> messages.lastIndex
            else -> 0
        }
        val aligned = alignKeepFromIndex(messages, candidate)
        // A two-message tool pair (or the only user turn) cannot be shortened without
        // producing an invalid provider transcript. Preserve that minimal protocol unit.
        return if (aligned <= 0) 0 else aligned.coerceAtMost(messages.lastIndex)
    }

    /** Prevent an oversized dynamic prompt from consuming the entire context before history. */
    fun fitSystemPrompt(prompt: String, budget: Int): String {
        if (prompt.isBlank() || budget <= 0) return prompt
        val maxTokens = (budget * MAX_SYSTEM_PROMPT_FRACTION).toInt().coerceAtLeast(MIN_SYSTEM_PROMPT_TOKENS)
        if (estimateTokens(prompt) <= maxTokens) return prompt
        val suffix = "\n\n[系统提示因上下文预算受限已截断；请优先遵守以上核心规则]"
        // 截断长度必须按 estimateTokens 二分校准：CJK ≈1.8 字/token、ASCII ≈2.5 字/token，
        // 与固定 4 字符/token 的换算差近 2 倍——中文系统提示按固定比截断后仍会超限 2 倍以上。
        var low = 0
        var high = prompt.length
        var fitted = suffix
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = prompt.take(mid) + suffix
            if (estimateTokens(candidate) <= maxTokens) {
                fitted = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return fitted
    }

    /**
     * 巨型用户消息兜底：折叠线算法只能按消息边界或轮内切割，单条自身超线的用户消息
     * （粘贴长文档/日志）无法折叠，保留尾区恒超预算 → 每次请求必被 provider 400，
     * 且压缩判定每次命中、每次失败，形成稳定失败循环。
     * 对保留区中超大的用户消息做"头尾保留 + 指针标记"的投影级截断——只影响发给
     * provider 的正文，落库 transcript 与 UI 不变，完整内容可用 history_read 找回。
     */
    fun truncateOversizedUserMessages(messages: List<HarnessMessage>, limit: Int): List<HarnessMessage> {
        if (limit <= 0 || messages.isEmpty()) return messages
        val total = messages.sumOf(::tokensOf)
        if (total <= limit) return messages
        var overage = total - limit
        val out = messages.toMutableList()
        // 从最大的用户消息开始截：粘贴的长文档是超限主因，也是唯一可无损压缩的正文
        val candidateIndexes = out.withIndex()
            .filter { it.value is UserMessage && tokensOf(it.value) > MIN_GIANT_USER_MESSAGE_TOKENS }
            .sortedByDescending { tokensOf(it.value) }
            .map { it.index }
        for (index in candidateIndexes) {
            if (overage <= 0) break
            val message = out[index] as UserMessage
            val oldTokens = estimateTokens(message.text)
            val targetTokens = (oldTokens - overage).coerceAtLeast(MIN_KEPT_USER_TURN_TOKENS)
            val fittedText = fitUserText(message.text, targetTokens) ?: continue
            val newTokens = estimateTokens(fittedText)
            if (newTokens >= oldTokens) continue
            overage -= oldTokens - newTokens
            out[index] = message.copy(text = fittedText)
        }
        // 图片兜底：多图消息的体积在图片而非文本，文本截断对它无效——仍超线时按从旧到新
        // 剥离投影中的图片并留标记。图片本体仍在落库 transcript 与 UI 中可见。
        if (overage > 0) {
            for (index in out.indices) {
                if (overage <= 0) break
                val message = out[index] as? UserMessage ?: continue
                if (message.imageUrls.isEmpty()) continue
                val savedTokens = message.imageUrls.size * ESTIMATED_IMAGE_TOKENS
                out[index] = message.copy(
                    imageUrls = emptyList(),
                    text = message.text +
                        "\n\n[…… 本消息携带的 ${message.imageUrls.size} 张图片因上下文预算已从模型上下文省略 ……]",
                )
                overage -= savedTokens
            }
        }
        return out
    }

    /**
     * 请求体物理体积预检：Token 预算看不见 UTF-8/JSON 膨胀与 Base64 图片。
     * 超限时先压缩当前轮超长工具输出（附 history_read 指针），再剥离历史图片。
     * 不改变消息条数与顺序，只替换正文；落库 transcript 与 UI 不受影响。
     */
    fun enforceRequestByteBudget(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>> = messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        },
        maxBytes: Int = REQUEST_BODY_HARD_LIMIT_BYTES,
    ): List<HarnessMessage> {
        if (messages.isEmpty() || maxBytes <= 0) return messages
        var bytes = estimateHarnessPayloadBytes(messages)
        if (bytes <= maxBytes) return messages
        val out = messages.toMutableList()
        var changed = false
        while (bytes > maxBytes) {
            val idx = out.indices
                .mapNotNull { index -> (out[index] as? ToolResult)?.takeIf { it.output.length > ACTIVE_TOOL_KEEP_HEAD_CHARS + ACTIVE_TOOL_KEEP_TAIL_CHARS }?.let { index to it } }
                .maxByOrNull { it.second.output.length }
                ?.first
            if (idx == null) break
            val result = out[idx] as ToolResult
            val (name, args) = toolCallDetails[result.toolCallId] ?: (null to null)
            val compacted = compactActiveToolOutput(name, args, result.output, result.success) +
                "\n[工具输出因请求体体积限制已压缩；全文在会话记录中，需要细节时调用 history_read(message_id=\"${result.id}\") 回读]"
            if (compacted.length >= result.output.length) break
            val updated = result.copy(output = compacted)
            bytes += harnessMessagePayloadBytes(updated) - harnessMessagePayloadBytes(result)
            out[idx] = updated
            changed = true
        }
        if (bytes > maxBytes) {
            for (index in out.indices) {
                if (bytes <= maxBytes) break
                val message = out[index] as? UserMessage ?: continue
                if (message.imageUrls.isEmpty()) continue
                val updated = message.copy(
                    imageUrls = emptyList(),
                    text = message.text +
                        "\n\n[…… 本消息携带的 ${message.imageUrls.size} 张图片因请求体体积限制已从模型上下文省略 ……]",
                )
                bytes += harnessMessagePayloadBytes(updated) - harnessMessagePayloadBytes(message)
                out[index] = updated
                changed = true
            }
        }
        return if (changed) out else messages
    }

    /** Provider 投影后再做一次体积治理，覆盖系统提示与压缩后仍过大的 data URL。 */
    fun shrinkApiMessagesToByteBudget(
        messages: List<ApiMessage>,
        maxBytes: Int = REQUEST_BODY_HARD_LIMIT_BYTES,
    ): List<ApiMessage> {
        if (messages.isEmpty() || maxBytes <= 0) return messages
        var bytes = estimateApiPayloadBytes(messages)
        if (bytes <= maxBytes) return messages
        val out = messages.toMutableList()
        var changed = false
        while (bytes > maxBytes) {
            val idx = out.indices
                .filter { index ->
                    val message = out[index]
                    message.role != "system" &&
                        message.content.orEmpty().length > ACTIVE_TOOL_KEEP_HEAD_CHARS + ACTIVE_TOOL_KEEP_TAIL_CHARS
                }
                .maxByOrNull { out[it].content.orEmpty().length }
            if (idx == null) break
            val message = out[idx]
            val compacted = compactActiveText(message.content.orEmpty())
            if (compacted.length >= message.content.orEmpty().length) break
            val updated = message.copy(content = compacted)
            bytes += apiMessagePayloadBytes(updated) - apiMessagePayloadBytes(message)
            out[idx] = updated
            changed = true
        }
        if (bytes > maxBytes) {
            for (index in out.indices) {
                if (bytes <= maxBytes) break
                val message = out[index]
                if (message.imageUrls.isEmpty()) continue
                val updated = message.copy(
                    imageUrls = emptyList(),
                    content = message.content.orEmpty() +
                        "\n\n[…… ${message.imageUrls.size} 张图片因请求体体积限制已从模型上下文省略 ……]",
                )
                bytes += apiMessagePayloadBytes(updated) - apiMessagePayloadBytes(message)
                out[index] = updated
                changed = true
            }
        }
        for (index in out.indices) {
            if (bytes <= maxBytes) break
            val message = out[index]
            if (message.responsesTurn == null) continue
            val updated = message.copy(responsesTurn = null)
            bytes += apiMessagePayloadBytes(updated) - apiMessagePayloadBytes(message)
            out[index] = updated
            changed = true
        }
        return if (changed) out else messages
    }

    fun estimateHarnessPayloadBytes(messages: List<HarnessMessage>): Int {
        var bytes = 0
        messages.forEach { message -> bytes += harnessMessagePayloadBytes(message) }
        return bytes
    }

    private fun harnessMessagePayloadBytes(message: HarnessMessage): Int =
        JSON_FRAMING_PER_MESSAGE + when (message) {
                is UserMessage -> {
                    jsonTextBytes(message.text) + message.imageUrls.sumOf { it.length }
                }
                is AssistantText -> {
                    jsonTextBytes(assistantTextForContext(message.text)) + jsonTextBytes(message.reasoning.orEmpty()) + (message.responsesTurn?.payloadBytes ?: 0)
                }
                is ToolCall -> {
                    jsonTextBytes(message.args.toString()) + jsonTextBytes(message.reasoning.orEmpty())
                }
                is ToolResult -> jsonTextBytes(message.modelOutput())
                else -> 0
            }

    fun estimateApiPayloadBytes(messages: List<ApiMessage>): Int {
        var bytes = JSON_FRAMING_PER_MESSAGE
        messages.forEach { message -> bytes += apiMessagePayloadBytes(message) }
        return bytes
    }

    private fun apiMessagePayloadBytes(message: ApiMessage): Int =
        JSON_FRAMING_PER_MESSAGE + (message.responsesTurn?.payloadBytes ?: 0) + jsonTextBytes(message.content.orEmpty()) +
            jsonTextBytes(message.reasoning_content.orEmpty()) + message.imageUrls.sumOf { it.length } +
            message.tool_calls.orEmpty().sumOf { call ->
                jsonTextBytes(call.function.name) + jsonTextBytes(call.function.arguments) + JSON_FRAMING_PER_MESSAGE
            }

    private fun compactActiveToolOutput(
        toolName: String?,
        args: JsonObject?,
        output: String,
        success: Boolean,
    ): String {
        val status = if (success) "成功" else "失败"
        val path = runCatching { args?.get("path")?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: runCatching { args?.get("command")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val hint = buildString {
            if (!toolName.isNullOrBlank()) append("工具:$toolName ")
            if (!path.isNullOrBlank()) append("对象:${path.take(180)} ")
        }.trim()
        return "【当前轮执行结果·状态:$status】$hint\n${compactActiveText(output)}"
    }

    private fun compactActiveText(output: String): String {
        val keep = ACTIVE_TOOL_KEEP_HEAD_CHARS + ACTIVE_TOOL_KEEP_TAIL_CHARS
        if (output.length <= keep + 80) return output
        val omitted = output.length - ACTIVE_TOOL_KEEP_HEAD_CHARS - ACTIVE_TOOL_KEEP_TAIL_CHARS
        return output.take(ACTIVE_TOOL_KEEP_HEAD_CHARS) +
            "\n... [请求体体积限制，已省略 $omitted 字符] ...\n" +
            output.takeLast(ACTIVE_TOOL_KEEP_TAIL_CHARS)
    }

    private fun jsonTextBytes(text: String): Int {
        if (text.isEmpty()) return 0
        var extra = 0
        text.forEach { ch ->
            if (ch == '"' || ch == '\\' || ch < ' ') extra++
        }
        return text.toByteArray(Charsets.UTF_8).size + extra
    }

    private fun fitUserText(text: String, targetTokens: Int): String? {
        val marker = "\n\n[…… 消息过长已截断：完整原文保留在会话记录中，需要时可用 history_read 读取 ……]\n\n"
        var keptChars = (targetTokens * 1.5f).toInt().coerceAtLeast(marker.length + 2)
        repeat(6) {
            if (text.length <= keptChars) return null
            val head = keptChars * 3 / 4
            val candidate = text.take(head) + marker + text.takeLast(keptChars - head)
            if (estimateTokens(candidate) <= targetTokens) return candidate
            keptChars = (keptChars * 3 / 4).coerceAtLeast(marker.length + 2)
        }
        return null
    }

    /**
     * Move a token-derived cut to a complete user turn. Cutting at an arbitrary message
     * can split a parallel tool exchange (call1, call2, result1, result2), which produces
     * an invalid provider transcript and can make the model retry the missing tool forever.
     */
    private fun alignKeepFromIndex(messages: List<HarnessMessage>, candidate: Int): Int {
        if (messages.isEmpty()) return 0
        val boundedCandidate = candidate.coerceIn(0, messages.lastIndex)
        val nextUser = (boundedCandidate..messages.lastIndex).firstOrNull { messages[it] is UserMessage }
        val previousUser = (boundedCandidate downTo 0).firstOrNull { messages[it] is UserMessage }
        val boundary = nextUser ?: previousUser ?: boundedCandidate
        return closeToolPairs(messages, boundary)
    }

    /**
     * Keep a tool call/result exchange together: if a kept ToolResult's call sits before
     * the boundary, pull the boundary back to that call. Repeat because moving back can
     * reveal another result from the same parallel tool-call group.
     */
    private fun closeToolPairs(messages: List<HarnessMessage>, boundary: Int): Int {
        var closed = boundary.coerceIn(0, messages.size)
        do {
            val previousBoundary = closed
            messages.subList(closed, messages.size)
                .filterIsInstance<ToolResult>()
                .forEach { result ->
                    val callIndex = messages.indexOfLast {
                        it is ToolCall && it.id == result.toolCallId
                    }
                    if (callIndex in 0 until closed) closed = callIndex
                }
        } while (closed < previousBoundary)
        return closed
    }

    /**
     * Generated image bytes stay in the persisted transcript for UI rendering, but must never be
     * counted as language tokens or echoed into a subsequent provider request.
     */
    fun assistantTextForContext(text: String): String {
        if (!text.contains("data:image/", ignoreCase = true) &&
            !text.contains("\"b64_json\"", ignoreCase = true)
        ) return text

        val out = StringBuilder(minOf(text.length, 4_096))
        var cursor = 0
        while (cursor < text.length) {
            val dataStart = text.indexOf("data:image/", cursor, ignoreCase = true)
            val jsonKeyStart = text.indexOf("\"b64_json\"", cursor, ignoreCase = true)
            val nextStart = listOf(dataStart, jsonKeyStart).filter { it >= 0 }.minOrNull()
            if (nextStart == null) {
                out.append(text, cursor, text.length)
                break
            }
            if (nextStart == dataStart) {
                val markdownStart = text.lastIndexOf("![", dataStart).takeIf { start ->
                    start >= cursor && text.indexOf("](", start).let { it in start until dataStart }
                }
                val htmlStart = text.lastIndexOf("<img", dataStart, ignoreCase = true).takeIf { it >= cursor }
                val mediaStart = listOfNotNull(markdownStart, htmlStart).maxOrNull() ?: dataStart
                out.append(text, cursor, mediaStart)
                out.append("[助手生成了一张图片；图片二进制已从模型上下文中省略]")
                val end = text.indexOfAny(charArrayOf(')', '"', '\'', '>', ' ', '\n', '\r', '\t'), dataStart)
                cursor = if (end >= 0) end + 1 else text.length
            } else {
                out.append(text, cursor, jsonKeyStart)
                out.append("\"b64_json\":\"[图片二进制已省略]\"")
                val colon = text.indexOf(':', jsonKeyStart + 10)
                val valueStart = if (colon >= 0) text.indexOf('"', colon + 1) else -1
                val valueEnd = if (valueStart >= 0) text.indexOf('"', valueStart + 1) else -1
                cursor = if (valueEnd >= 0) valueEnd + 1 else text.length
            }
        }
        return out.toString()
    }

    fun buildHistorySummary(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>> = messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        },
    ): String {
        if (messages.isEmpty()) return ""
        val firstRequest = messages.filterIsInstance<UserMessage>().firstOrNull()?.text
            ?.replace('\n', ' ')?.take(240)
        val recentRequests = messages.filterIsInstance<UserMessage>().takeLast(8)
            .map { it.text.replace('\n', ' ').take(320) }
        val toolStates = messages.filterIsInstance<ToolResult>().takeLast(24).map { result ->
            val name = toolCallDetails[result.toolCallId]?.first ?: "tool"
            val args = toolCallDetails[result.toolCallId]?.second
            val output = if (result.output.length > compactThresholdFor(name)) {
                compactToolOutput(name, args, result.output, result.success)
            } else {
                result.output
            }
            val command = args?.get("command")?.jsonPrimitive?.contentOrNull
                ?: args?.get("path")?.jsonPrimitive?.contentOrNull
            "$name:${if (result.success) "成功" else "失败"} " +
                command.orEmpty().take(480) + " " + output.replace('\n', ' ').take(720)
        }
        val lastAssistant = messages.filterIsInstance<AssistantText>().lastOrNull()?.text
            ?.let(::assistantTextForContext)
            ?.replace('\n', ' ')?.take(600)
        val textMessages = messages.mapNotNull {
            when (it) {
                is UserMessage -> it.text
                is AssistantText -> assistantTextForContext(it.text)
                else -> null
            }
        }
        val constraints = textMessages.asSequence()
            .flatMap { it.lineSequence() }
            .filter { line -> CONSTRAINT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }
            .map { it.trim().replace(WHITESPACE_REGEX, " ").take(220) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeLast(16)
        val decisions = textMessages.asSequence()
            .flatMap { it.lineSequence() }
            .filter { line -> DECISION_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }
            .map { it.trim().replace(WHITESPACE_REGEX, " ").take(220) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeLast(16)
        val files = textMessages.asSequence()
            .flatMap { text -> FILE_PATH_REGEX.findAll(text.take(4000)).map { it.value.take(180) } }
            .distinct()
            .toList()
            .takeLast(24)
        val failures = messages.filterIsInstance<ToolResult>().filter { !it.success }
            .takeLast(12)
            .map { it.output.replace('\n', ' ').take(480) }
        val unresolved = recentRequests.takeLast(4)
        return buildString {
            appendLine("[早期历史摘要，共折叠 ${messages.size} 条消息]")
            firstRequest?.takeIf { it.isNotBlank() }?.let { appendLine("初始目标：$it") }
            if (toolStates.isNotEmpty()) appendLine("关键工具状态（新→旧）：${toolStates.asReversed().joinToString(" | ")}")
            if (failures.isNotEmpty()) appendLine("失败根因线索（新→旧）：${failures.asReversed().joinToString(" | ")}")
            if (constraints.isNotEmpty()) appendLine("用户硬约束：${constraints.joinToString(" | ")}")
            if (decisions.isNotEmpty()) appendLine("关键决定：${decisions.joinToString(" | ")}")
            if (files.isNotEmpty()) appendLine("涉及文件：${files.joinToString(" | ")}")
            if (recentRequests.isNotEmpty()) appendLine("近期用户要求（新→旧）：${recentRequests.asReversed().joinToString(" | ")}")
            if (unresolved.isNotEmpty()) appendLine("未解决事项：${unresolved.joinToString(" | ")}")
            lastAssistant?.takeIf { it.isNotBlank() }?.let { append("最近阶段结论：$it") }
        }.take(MAX_INCREMENTAL_SUMMARY_CHARS)
    }

    /**
     * Detect dynamic content patterns in a system prompt that would invalidate KV prefix-cache
     * on every turn, losing the ~90% discount DeepSeek and similar providers offer on cached
     * tokens. Returns a list of human-readable descriptions of what was found, or an empty list
     * when the prompt appears cache-stable.
     *
     * Use this in debug builds or CI to audit system prompt construction:
     * ```kotlin
     * val issues = ContextWindowPolicy.detectDynamicSystemPromptPatterns(systemPrompt)
     * if (issues.isNotEmpty()) Log.w("CacheStability", "Dynamic system prompt: $issues")
     * ```
     *
     * The check is intentionally lightweight — it pattern-matches common injection forms.
     * A negative result does not guarantee cache stability; it only means no obvious dynamic
     * content was detected.
     */
    fun detectDynamicSystemPromptPatterns(prompt: String): List<String> {
        if (prompt.isBlank()) return emptyList()
        val issues = mutableListOf<String>()
        // ISO 8601 date: 2026-09-05 or 2026/09/05
        if (Regex("""\b20\d{2}[-/]\d{2}[-/]\d{2}\b""").containsMatchIn(prompt)) {
            issues += "ISO 日期（YYYY-MM-DD）"
        }
        // Time of day: 14:30 or 14:30:05
        if (Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""").containsMatchIn(prompt)) {
            issues += "时间（HH:MM）"
        }
        // Unix timestamp (10-13 digit integer, typically used as currentTimeMillis or epochSeconds)
        if (Regex("""\b1[6-9]\d{8}\b|\b17\d{8}\b|\b1[6-9]\d{11}\b|\b17\d{11}\b""")
                .containsMatchIn(prompt)) {
            issues += "UNIX 时间戳"
        }
        // Chinese date patterns like "2026年9月5日"
        if (Regex("""\d{4}年\d{1,2}月\d{1,2}日""").containsMatchIn(prompt)) {
            issues += "中文日期（YYYY年M月D日）"
        }
        // Battery/charge level patterns: "电量: 83%" or "battery: 83%"
        if (Regex("""(?:电量|battery)[^\n]{0,20}?\d{1,3}%""", RegexOption.IGNORE_CASE)
                .containsMatchIn(prompt)) {
            issues += "实时电量百分比"
        }
        return issues
    }

    private val CONSTRAINT_MARKERS = listOf("必须", "不得", "禁止", "不能", "严禁", "要求", "must", "never", "should not")
    private val DECISION_MARKERS = listOf("决定", "采用", "改为", "选择", "方案", "decision", "use", "采用")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val MAX_INCREMENTAL_SUMMARY_CHARS = 8_000
    private val FILE_PATH_REGEX = Regex("(?:/workspace|/sdcard|[A-Za-z]:[\\\\/])[^\\s,，。；;，)\\]]+")
}

/**
 * 上下文 Token 用量明细（8 维细分模型，用于高保真还原现代化 Agent 上下文可视化面板）。
 */
data class ContextUsageBreakdown(
    val systemPromptTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val rulesTokens: Int = 0,
    val skillsTokens: Int = 0,
    val mcpTokens: Int = 0,
    val subagentTokens: Int = 0,
    val summarizedTokens: Int = 0,
    val conversationTokens: Int = 0,
) {
    val totalTokens: Int
        get() = systemPromptTokens +
            toolDefinitionTokens +
            rulesTokens +
            skillsTokens +
            mcpTokens +
            subagentTokens +
            summarizedTokens +
            conversationTokens
}

data class EffectiveContextUsage(
    val keepFromIndex: Int,
    val conversationTokens: Int,
    val toolTokens: Int,
    val totalTokens: Int,
    val breakdown: ContextUsageBreakdown = ContextUsageBreakdown(),
)

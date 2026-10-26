package top.tianyan.app.harness.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.ContextBudgetPlanner
import top.tianyan.app.harness.ImagePayloadCompressor
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.MentionExtractor
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.compaction.SummaryRequestContext
import top.tianyan.app.harness.prompt.MemoryRecallSelector
import top.tianyan.app.harness.prompt.SystemPromptBuilder
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.diagnostics.ContextGovernanceTrace
import top.tianyan.app.harness.diagnostics.ContextStage

/**
 * API 请求上下文组装器：把会话实时消息投影成提供商协议消息列表。
 *
 * 从原 HarnessLoop.apiMessages 迁移而来，负责：
 * - 系统提示词注入（非纯净聊天模式；逐轮可变内容已全部外移，见下）
 * - 用户轮前缀块的持久化（recall_context entry：记忆召回 + 任务计划看板，每轮只算一次）
 * - 上下文压缩摘要的头部注入（预算驱动的滑动窗口折叠）
 * - NATIVE / JSON_TEXT 两种工具调用协议的消息形态转换（经 [ApiMessageProjector]）
 * - 视觉能力关闭时剥离图片输入
 *
 * Prefix cache 稳定性契约：system prompt 内不再含逐轮变化的内容（recall 已移到
 * user 轮后缀、路由规则块与技能按全会话累计），相邻两轮请求的 system 消息字节级一致；
 * 变化只出现在本轮新增的消息上（本就未进入缓存）。
 */
class ApiContextAssembler(
    private val compactionManager: CompactionManager,
    private val settingsDataStore: AgentPreferences,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val sessionStore: SessionTreeStore,
    private val memoryRecallSelector: MemoryRecallSelector,
    private val agentApprovalRepository: AgentApprovalRepository,
    private val operationCoordinator: OperationCoordinator,
    private val plugins: top.tianyan.app.harness.plugins.HarnessPluginRegistry? = null,
    private val secretRedactor: top.tianyan.app.core.security.SecretRedactor? = null,
    private val environments: top.tianyan.app.harness.environment.SessionExecutionEnvironments? = null,
    /** 上下文管家（DS Bot steward）：压缩触发时判断「换段 vs 窗口压缩」；null = 禁用。 */
    private val compactionSteward: top.tianyan.app.harness.compaction.CompactionSteward? = null,
) {
    suspend fun assemble(
        sessId: String,
        model: ModelConfig,
        workspacePath: String,
        projectTypeOverride: String = "",
        thinkingMode: Boolean = false,
        sessionRunMode: String? = null,
        trace: ContextGovernanceTrace? = null,
    ): List<ApiMessage> {
        val compactionEnabled = runCatching { settingsDataStore.contextCompactionEnabled.first() }.getOrDefault(true)
        // 「历史折叠线比例」：让历史在预算的一部分处就开始折叠。
        // 与面板同源读取同一个偏好，保证两侧折叠决策一致。
        val foldingRatioPercent = runCatching { settingsDataStore.contextFoldingRatioPercent.first() }
            .getOrDefault(ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT)
        // 与 SessionModelSwitcher 共用 clampedBudget：占用判定与实际请求必须是同一预算口径
        val budgetTokens = ContextWindowPolicy.clampedBudget(
            model.contextTokens,
            runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(128_000),
            modelId = model.model,
            providerId = model.provider,
        )
        val toolCallMode = model.effectiveToolCallMode
        // 折叠线的工具 schema 预留按模式取：不外发 tools 的会话不该白留这 5,600 token
        val toolSchemaReserveTokens = ContextWindowPolicy.toolSchemaReserveTokensFor(
            pureChat = model.pureChatMode,
            toolDisabled = toolCallMode == ToolCallMode.DISABLED,
        )

        val inspected = compactionManager.inspect(sessId)
        trace?.begin(inspected, budgetTokens, compactionEnabled)
        var compactedContext = inspected.context()
        var msgs = compactedContext.messages

        // 用户轮前缀块（低权威背景资料：记忆召回 + 任务计划看板）：只对最新用户轮计算一次并
        // 持久化到会话树（appendRecallBlock 幂等），保存的块不重算；请求按预算整块保留或省略。
        // 这取代了旧的「system prompt 尾部注入」——记忆召回与计划看板都是逐轮变化的内容，
        // 放在 system prompt 里会让整段前缀每轮/每步失效。
        // 持久化失败时放弃挂载：未持久化的字节进投影会导致下一轮组装漂移。
        if (!model.pureChatMode) {
            val latestUser = msgs.filterIsInstance<UserMessage>().lastOrNull()
            if (latestUser != null && latestUser.id !in compactedContext.recallBlocks) {
                val block = try {
                    memoryRecallSelector.turnPrefixBlock(
                        projectOwnerId = workspacePath.trim().trimEnd('/'),
                        sessionId = sessId,
                        userMessage = latestUser.text,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    ""
                }
                if (block.isNotBlank() && sessionStore.appendRecallBlock(sessId, latestUser.id, block)) {
                    compactedContext = compactedContext.copy(
                        recallBlocks = compactedContext.recallBlocks + (latestUser.id to block),
                    )
                }
            }
        }

        // 技能 @提及按全会话累计（一旦提及，规则常驻本会话）：避免「下一轮未提及→章节
        // 撤出」造成的 system prompt 漂移。MCP @ 提及已不裁剪 tools 数组也不动提示词
        // （只写能力事件，见 HarnessProviderRunner.resolveEffectiveModel）。
        val mentionedNames = msgs.filterIsInstance<UserMessage>()
            .flatMapTo(mutableSetOf()) { MentionExtractor.parse(it.text) }
        val userMessageTexts = msgs.filterIsInstance<UserMessage>().map { it.text }

        val rawSystemPrompt = if (!model.pureChatMode) {
            // 运行意图与 ToolExecutor 同口径解析：会话级优先，缺失才回落全局默认
            // （RunMode.fromId 对 null 返回 BUILD，因此必须先做 null 判别再回落）。
            val runMode = sessionRunMode?.let(RunMode::fromId)
                ?: runCatching { agentApprovalRepository.currentRunMode() }.getOrDefault(RunMode.BUILD)
            systemPromptBuilder.build(
                workspacePath,
                toolCallMode,
                mentionedNames,
                sessId,
                projectTypeOverride,
                userMessageTexts,
                runMode,
            )
        } else {
            ""
        }
        val systemPrompt = ContextWindowPolicy.fitSystemPrompt(rawSystemPrompt, budgetTokens)
        var failureMemory = if (model.pureChatMode || toolCallMode == ToolCallMode.DISABLED) "" else operationCoordinator.failureMemoryBlock(sessId)
        var pluginContext = if (model.pureChatMode || toolCallMode == ToolCallMode.DISABLED || secretRedactor == null || plugins == null) "" else plugins.context(
            top.tianyan.app.harness.plugins.PluginContextRequest(sessId, workspacePath),
            top.tianyan.app.harness.environment.executionOutputRedactor(secretRedactor, environments, null, settingsDataStore, sessId, workspacePath),
        )

        val rawFailureMemory = failureMemory
        val rawPluginContext = pluginContext
        var selectedRecall = compactedContext.recallBlocks
        fun allocateBackground() {
            if (!compactionEnabled) return
            val lastUser = msgs.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            val plan = ContextBudgetPlanner.plan(budgetTokens, systemPrompt, compactedContext.summaryLayer,
                msgs.drop(lastUser).sumOf(ContextWindowPolicy::tokensOf), model.compactionReserveTokens,
                toolSchemaReserveTokens, foldingRatioPercent, msgs.filterIsInstance<UserMessage>()
                    .mapNotNull { user -> compactedContext.recallBlocks[user.id]?.let { user.id to it } }.toMap(),
                rawFailureMemory, rawPluginContext)
            selectedRecall = plan.recall
            failureMemory = plan.failureMemory
            pluginContext = plan.pluginContext
            trace?.allocations(plan.allocations)
            trace?.contribution("recall", selectedRecall.values)
            trace?.contribution("failure_memory", listOf(failureMemory))
            trace?.contribution("plugin_context", listOf(pluginContext))
        }
        allocateBackground()

        // 召回后缀的 token 计入预算占用：它们会随 user 消息进入 provider 请求
        fun recallTokens() = ContextWindowPolicy.estimateTokens(selectedRecall.values.joinToString("\n")) +
            ContextWindowPolicy.estimateTokens(failureMemory) + ContextWindowPolicy.estimateTokens(pluginContext)
        trace?.contribution("system", listOf(systemPrompt))
        trace?.contribution("recall", selectedRecall.values)
        trace?.contribution("failure_memory", listOf(failureMemory))
        trace?.contribution("plugin_context", listOf(pluginContext))

        return buildList {
            if (systemPrompt.isNotEmpty()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }

            // 老轮次工具结果与写入参数缩减（先于压缩判定）：预算线未越过时，大输出（浏览器快照、
            // 长 read）仍会原样重复发送。最近若干条原样保留，更老的超过按工具阈值即压缩并附
            // history_read 指针——只影响发给 Provider 的正文，落库 transcript 与 UI 不变。
            // 关闭上下文压缩 = 用户要原始历史，此时同样不截断。
            // 顺序必须在压缩判定之前：只需截断即可回到预算线内的会话，不应再触发整段压缩
            // （一次额外 LLM 调用 + 历史永久降级为摘要）。
            if (compactionEnabled) {
                msgs = ContextWindowPolicy.projectForUsage(msgs, compactionEnabled = true).also { trace?.messages(ContextStage.HISTORY, msgs, it) }
            }

            // 预算驱动的滑动窗口：从最近一轮往回累加 token，超出预算则更早的历史进入压缩态。
            // 是否裁剪原文只由真实 token 预算决定，不再按用户轮次阈值强制折叠。
            // 每模型压缩预算覆盖（pi 式 modelOverrides）：keepRecent 收紧 + reserve 预留。
            val computedKeepFromIndex = if (compactionEnabled) {
                ContextWindowPolicy.computeKeepFromIndex(
                    msgs,
                    budgetTokens,
                    ContextWindowPolicy.estimateTokens(systemPrompt) +
                        ContextWindowPolicy.estimateTokens(compactedContext.summaryLayer) +
                        recallTokens(),
                    keepRecentTokens = model.compactionKeepRecentTokens ?: 0,
                    reserveTokens = model.compactionReserveTokens,
                    foldingRatioPercent = foldingRatioPercent,
                    toolSchemaReserveTokens = toolSchemaReserveTokens,
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                // 上下文管家（DS Bot steward）：任务告一段落 → 换段（只保留最后一轮用户消息起的
                // 原文，其余折叠为交接摘要，前缀重置为 system+摘要+当前轮）；仍在进行 → 维持
                // 窗口压缩。判定失败一律退化为现状。被折叠原文仍在会话树，可 history_search。
                var keepFromIndex = computedKeepFromIndex
                if (compactionSteward != null && compactionEnabled) {
                    val recent = top.tianyan.app.harness.compaction.CompactionSteward.recentTranscript(msgs)
                    if (compactionSteward.shouldSwitchSegment(model, recent)) {
                        keepFromIndex = top.tianyan.app.harness.compaction.CompactionSteward
                            .switchKeepFromIndex(msgs, computedKeepFromIndex)
                        trace?.contribution("steward", listOf("switch segment: keepFrom=$keepFromIndex"))
                    }
                }
                // LLM 结构化压缩摘要（pi 式）：当前模型生成，失败回退机械摘要。
                // summaryContext 让摘要请求重放主对话的 system + 摘要层 + 原始消息前缀，
                // 命中 provider KV 缓存（cache-replay 形状，对齐 Reasonix）。
                compactedContext = compactionManager.compact(
                    sessId,
                    compactedContext,
                    keepFromIndex,
                    model = model,
                    summaryContext = SummaryRequestContext(
                        systemPrompt = systemPrompt,
                        summaryLayer = compactedContext.summaryLayer,
                        toolCallMode = toolCallMode,
                        visionEnabled = model.capabilities.images,
                        recallBlocks = selectedRecall,
                        // 被折叠区域的 provider 可见形态：截断已发生过，与主对话实际发送的字节一致
                        replayPrefix = msgs.take(computedKeepFromIndex),
                    ),
                ).also { trace?.messages(ContextStage.COMPACTION, msgs, it.messages) }
                msgs = compactedContext.messages
                allocateBackground()
                // compact 返回的保留窗口来自原始 transcript（未截断），重放一次截断，
                // 保证与压缩判定时同一口径。
                if (compactionEnabled) {
                    msgs = ContextWindowPolicy.projectForUsage(msgs, compactionEnabled = true).also { trace?.messages(ContextStage.RETAINED_HISTORY, msgs, it) }
                }
            }
            if (compactionEnabled) {
                // 巨型用户消息兜底（与 computeKeepFromIndex 同一条折叠线，foldingLimitFor
                // 内部自钳比例）：单条自身超线的用户消息（粘贴长文档/日志）无法按边界折叠，
                // kept 恒超预算 → 每轮请求必被 provider 400。对保留区超大用户消息做
                // 投影级头尾截断，落库 transcript 与 UI 不受影响。
                msgs = ContextWindowPolicy.truncateOversizedUserMessages(
                    msgs,
                    ContextWindowPolicy.foldingLimitFor(
                        budget = budgetTokens,
                        ratioPercent = foldingRatioPercent,
                        systemTokens = ContextWindowPolicy.estimateTokens(systemPrompt) +
                            ContextWindowPolicy.estimateTokens(compactedContext.summaryLayer) +
                            recallTokens(),
                        reserveTokens = model.compactionReserveTokens,
                        toolSchemaReserveTokens = toolSchemaReserveTokens,
                    ),
                ).also { trace?.messages(ContextStage.USER_LIMIT, msgs, it) }
            }
            // 物理字节预检独立于 token 折叠：关闭压缩时历史仍可能把 Nginx/中转撑到 HTTP 413。
            msgs = ImagePayloadCompressor.downscaleHarness(msgs).also { trace?.messages(ContextStage.IMAGE_DOWNSCALE, msgs, it) }
            msgs = ContextWindowPolicy.enforceRequestByteBudget(msgs, toolCallDetailsOf(msgs)).also { trace?.messages(ContextStage.BYTE_LIMIT, msgs, it) }
            val summaryLayer = compactedContext.summaryLayer
            trace?.contribution("summary", listOf(summaryLayer))
            if (summaryLayer.isNotBlank()) {
                add(
                    ApiMessage(
                        role = "system",
                        content = summaryLayer,
                    ),
                )
            }
            val projectedMessages = ApiMessageProjector.project(
                msgs = msgs,
                toolCallMode = toolCallMode,
                visionEnabled = model.capabilities.images,
                recallSuffixes = selectedRecall,
            ).let { it + listOf(failureMemory, pluginContext).filter(String::isNotBlank).map { text -> ApiMessage(role = "user", content = text) } }
            // Split-Turn 压缩后保留段可能从 assistant / tool_call 中途开始。补一条合成 user
            // 消息作为角色桥接，满足 provider 的角色交替与「首条消息必须是 user」约束
            // （Anthropic 尤其严格），并提示模型不要重复已折叠的步骤。
            if (summaryLayer.isNotBlank() && projectedMessages.isNotEmpty() && projectedMessages.first().role != "user") {
                add(ApiMessage(role = "user", content = SPLIT_TURN_BRIDGE_MESSAGE))
            }
            addAll(
                ContextWindowPolicy.shrinkApiMessagesToByteBudget(
                    ImagePayloadCompressor.downscale(projectedMessages),
                ).also { trace?.api(projectedMessages, it) },
            )
        }.also { trace?.finish(it) }
    }

    private fun toolCallDetailsOf(msgs: List<HarnessMessage>) =
        msgs.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }
}

/** Split-Turn 压缩后的角色桥接消息：当保留段从 assistant / tool_call 中途开始时插入。 */
private const val SPLIT_TURN_BRIDGE_MESSAGE =
    "[系统说明] 为避免单个超长任务轮次撑爆上下文，更早的步骤已折叠为上方摘要。以下从当前任务中途继续，请勿重复已完成的工作。"

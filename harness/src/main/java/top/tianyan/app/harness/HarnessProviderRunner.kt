package top.tianyan.app.harness

import android.content.Context
import top.tianyan.app.core.database.HarnessSessionEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.tianyan.app.harness.metrics.RunMetrics
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.effects.RetryPolicy
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.projection.SessionStateMirrors
import top.tianyan.app.harness.session.ApiContextAssembler
import kotlin.time.Duration.Companion.milliseconds
import top.tianyan.app.harness.diagnostics.RequestDiagnosticsStore

/** 模型能力选择、流式请求重试及助手回复持久化；不持有会话调度状态。 */
class HarnessProviderRunner(
    private val providerClient: ProviderClient,
    private val requestDiagnostics: RequestDiagnosticsStore,
    private val messageStore: SessionTreeStore,
    private val operationCoordinator: OperationCoordinator,
    private val stateMirrors: SessionStateMirrors,
    private val messageProjector: SessionMessageProjector,
    private val toolMountResolver: McpToolMountResolver,
    private val agentEventLogger: AgentEventLogger,
    private val contextAssembler: ApiContextAssembler,
    private val compactionManager: CompactionManager,
    private val context: Context,
) {
    suspend fun clearDiagnostics(sessionId: String) = requestDiagnostics.removeSessionDurably(sessionId)

    /**
     * 解析本轮有效模型：用户 @ 过的 MCP 服务会把启用工具挂载进 provider tools（会话内稳定），
     * 其余能力仍走 use_capability；挂载与能力事件逻辑在 [McpToolMountResolver]，这里保持薄委托。
     */
    suspend fun resolveEffectiveModel(sessId: String, model: ModelConfig): ModelConfig =
        toolMountResolver.resolve(sessId, messageProjector.messagesFlow(sessId).value, model)

    /** 流式调用 + 限流/网络退避重试。恢复不了的失败以 Failed 终态返回；取消与超过重试上限的原样抛出 */
    suspend fun callProviderWithRetry(
        sessId: String,
        model: ModelConfig,
        sessionEntity: HarnessSessionEntity?,
        sessionWorkspace: String,
        operationId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        retryPolicy: RetryPolicy,
        metrics: RunMetrics,
    ): TurnProviderOutcome {
        val streamText = StreamBuffer()
        val streamReasoning = StreamBuffer(maxChars = ProviderClient.MAX_STREAM_REASONING_CHARS)
        var streamed: ChatResult? = null
        // 干净快照基线：每次网络重发前彻底清空本轮缓冲区并删除流式气泡，
        // 避免半截字符/残缺 JSON 拼接到下一次重试的增量之后污染上下文。
        fun resetStreamBaseline() {
            streamText.clear()
            streamReasoning.clear()
            messageProjector.remove(sessId, assistantId)
        }
        suspend fun settleFailedStream() {
            stateMirrors.setThinkingLive(sessId, false)
            if (streamText.length > 0) {
                persistAssistant(
                    sessId, assistantId, assistantAt, streamText.toString(),
                    streamReasoning.toString().ifBlank { null },
                    totalMs = now() - startedAt, operationId = operationId, round = round,
                )
            } else {
                messageProjector.remove(sessId, assistantId)
            }
        }

        var requestModel = model.copy(redTeamEnabled = sessionEntity?.redTeamMode == "red_team") // 红队会话才挂 red_team
        var outputBudgetReduced = false
        var outputLimitRecoveries = 0
        var netRetry = 0
        val governance = top.tianyan.app.harness.diagnostics.ContextGovernanceTrace()
        suspend fun assembleFor(requestModel: ModelConfig) = withContext(top.tianyan.app.harness.bot.TaskBudgetContext(sessId)) { contextAssembler.assemble(
            sessId = sessId,
            model = requestModel,
            workspacePath = sessionWorkspace,
            projectTypeOverride = sessionEntity?.projectType.orEmpty(),
            thinkingMode = stateMirrors.requestThinkingMode(sessId),
            sessionRunMode = sessionEntity?.runMode, trace = governance,
        ) }
        var requestMessages = assembleFor(model)
        var imageStripped = false
        var overflowRecovered = false
        // 与请求构造同口径：messages + provider 可见 tools schema（仅 NATIVE 模式独立于 messages）。
        // 漏掉 schema 会低估真实输入规模，余量被吃光后以 400/413 溢出。
        val toolSchemaTokens =
            if (model.capabilities.nativeTools) {
                ContextWindowPolicy.estimateToolDefinitionTokens(ProviderClient.buildDynamicTools(requestModel, requestModel.redTeamEnabled))
            } else {
                0
            }
        val estimatedRequestTokens = estimateRetryRequestTokens(requestMessages) + toolSchemaTokens
        val maxNetworkRetries = maxNetworkRetriesFor(estimatedRequestTokens, retryPolicy.maxRetries)
        val maxAttempts = maxNetworkRetries + 1
        if (maxNetworkRetries < retryPolicy.maxRetries) {
            agentEventLogger.log(sessId, "LargeContextRetryPolicy", "估算输入约 $estimatedRequestTokens tokens，大上下文网络重试限制为 $maxNetworkRetries 次")
        }
        var requestAttempt = 0
        while (streamed == null) {
            try {
                agentEventLogger.logModelAttempt(sessId, requestModel, requestMessages.size, netRetry + 1, maxAttempts)
                stateMirrors.setStatus(sessId, "等待模型首个响应（${netRetry + 1}/$maxAttempts）")
                operationCoordinator.providerIntent(
                    operationId = operationId,
                    effectId = assistantId,
                    round = round,
                    attempt = netRetry + 1,
                    maxAttempts = maxAttempts,
                )
                streamed = withContext(top.tianyan.app.harness.session.ProviderActivityContext(stateMirrors.providerActivity, sessId)) {
                    providerClient.chatStream(
                        requestModel,
                        requestMessages,
                        onRequest = { protocol, body, bytes, secrets ->
                            requestDiagnostics.record(sessId, operationId, round, ++requestAttempt, protocol, body, bytes, secrets, governance.report(bytes))
                            stateMirrors.providerActivity.begin(sessId, operationId, requestAttempt)
                        },
                        onReasoning = { chunk ->
                            withinStreamHandling {
                                if (chunk.isNotEmpty()) stateMirrors.recordModelOutput(sessId, "thinking")
                                streamReasoning.append(chunk)
                                stateMirrors.setThinkingLive(sessId, true)
                                stateMirrors.recordThinkingObserved(sessId)
                                streamReasoning.publishIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                                    messageProjector.streamReasoning(sessId, assistantId, assistantAt, it)
                                }
                            }
                        },
                        onToolProgress = { progress ->
                            withinStreamHandling {
                                stateMirrors.recordModelOutput(sessId, "tool_arguments")
                                stateMirrors.setThinkingLive(sessId, false)
                                stateMirrors.setStatus(sessId, progress.statusText())
                            }
                        },
                        sessionKey = sessId,
                    ) { chunk ->
                        withinStreamHandling {
                            if (chunk.isNotEmpty()) stateMirrors.recordModelOutput(sessId)
                            stateMirrors.setStatus(sessId, "回复中")
                            streamText.append(chunk)
                            streamText.publishSnapshotIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                                messageProjector.streamText(sessId, assistantId, assistantAt, it)
                            }
                        }
                    }
                }
                withinStreamHandling {
                    if (streamReasoning.length > 0) {
                        messageProjector.streamReasoning(sessId, assistantId, assistantAt, streamReasoning.toString())
                    }
                    if (streamText.length > 0) {
                        messageProjector.streamText(sessId, assistantId, assistantAt, streamText.snapshot())
                    }
                }
            } catch (cancellation: CancellationException) {
                persistCancelledPartial(sessId, assistantId, assistantAt, streamText, streamReasoning, startedAt, operationId, round)
                withContext(NonCancellable) {
                    agentEventLogger.log(sessId, "Cancelled", "用户主动取消执行，保留已生成内容 ${streamText.length} 字符")
                }
                throw cancellation
            } catch (handling: StreamChunkHandlingException) {
                // 本地流式处理异常：原样终止，严禁当作网络故障重发（否则重复文字/半截 JSON 会污染上下文）。
                stateMirrors.setThinkingLive(sessId, false)
                agentEventLogger.log(
                    sessId,
                    "StreamChunkHandling",
                    "流式增量处理失败（本地异常，禁止网络重发）：${handling.message}",
                    handling,
                )
                if (streamText.length > 0) {
                    persistAssistant(
                        sessId,
                        assistantId,
                        assistantAt,
                        streamText.toString(),
                        streamReasoning.toString().ifBlank { null },
                        totalMs = now() - startedAt,
                        operationId = operationId,
                        round = round,
                    )
                } else {
                    messageProjector.remove(sessId, assistantId)
                }
                return TurnProviderOutcome.Failed(
                    "流式响应处理失败（本地异常，未自动重试）：${friendly(handling.cause ?: handling)}",
                )
            } catch (rateLimit: LlmRateLimitException) {
                stateMirrors.providerActivity.retry(sessId, "rate_limit")
                currentCoroutineContext().ensureActive()
                if (rateLimit.quotaExhausted) {
                    stateMirrors.setThinkingLive(sessId, false)
                    agentEventLogger.log(sessId, "QuotaExhausted", rateLimit.message.orEmpty(), rateLimit)
                    // 移除空的流式气泡；错误通过 error state 展示，不写入消息历史，避免下一轮注入模型上下文
                    messageProjector.remove(sessId, assistantId)
                    val detail = rateLimit.message?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                    return TurnProviderOutcome.Failed("模型服务商额度已耗尽，无法继续执行。请充值、切换可用模型或更新 API Key。$detail")
                }
                netRetry++
                if (netRetry > maxNetworkRetries) {
                    settleFailedStream()
                    throw rateLimit
                }
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                val waitSeconds = rateLimit.retryAfterSeconds ?: (netRetry * RETRY_BACKOFF_SEC).coerceAtMost(60L)
                stateMirrors.setStatus(sessId, "请求受限，${waitSeconds} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                agentEventLogger.log(sessId, "RateLimitRetry", "限流退避 ${waitSeconds}s，重试 $netRetry/$maxNetworkRetries", rateLimit)
                resetStreamBaseline()
                for (remaining in waitSeconds downTo 1L) {
                    currentCoroutineContext().ensureActive()
                    stateMirrors.setStatus(sessId, "请求受限，${remaining} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                    delay(1000L.milliseconds)
                }
            } catch (outputLimit: LlmOutputLimitException) {
                currentCoroutineContext().ensureActive()
                stateMirrors.setThinkingLive(sessId, false)
                val recovered = if (outputLimitRecoveries < OUTPUT_LIMIT_MAX_RECOVERIES && !outputBudgetReduced) {
                    outputLimitRecoveries++
                    stateMirrors.providerActivity.retry(sessId, "output_limit")
                    stateMirrors.setStatus(sessId, "输出达到上限，正在调整预算后重试")
                    recoverFromOutputLimit(sessId, requestModel, requestMessages, toolSchemaTokens,
                        compactionManager, agentEventLogger)
                } else null
                if (recovered != null) {
                    requestModel = recovered
                    agentEventLogger.log(sessId, "OutputLimitRecovery",
                        "输出截断，预留 ${recovered.maxTokens} tokens 后重新生成", outputLimit)
                    metrics.streamRetry()
                    resetStreamBaseline()
                    requestMessages = assembleFor(requestModel)
                        .map { if (imageStripped) it.copy(imageUrls = emptyList()) else it }
                    continue
                }
                settleFailedStream()
                return TurnProviderOutcome.Failed("模型输出达到 token 上限，自动恢复后仍未完成。请增加输出预算或拆分任务后重试")
            } catch (invalidOutputTokens: LlmInvalidOutputTokensException) {
                stateMirrors.providerActivity.retry(sessId, "invalid_output_tokens")
                currentCoroutineContext().ensureActive()
                if (outputBudgetReduced) throw invalidOutputTokens
                outputBudgetReduced = true
                val current = ContextWindowPolicy.normalizeOutputTokens(requestModel.maxTokens, 8_192)
                val reduced = (current / 2).coerceAtLeast(1)
                requestModel = requestModel.copy(maxTokens = reduced)
                agentEventLogger.log(
                    sessId,
                    "OutputBudgetReduction",
                    "Provider 拒绝输出预算，严格降额至 $reduced 后重试",
                    invalidOutputTokens,
                )
                resetStreamBaseline()
            } catch (contextOverflow: LlmContextOverflowException) {
                stateMirrors.providerActivity.retry(sessId, "context_overflow")
                currentCoroutineContext().ensureActive()
                stateMirrors.setThinkingLive(sessId, false)
                // 必须位于 IOException 之前：LlmContextOverflowException 本身继承 IOException。
                // 若先进入通用网络重试分支，413 / context_length_exceeded 只会原样重发，
                // 永远到不了 overflow -> compact -> replay 的自愈闭环。
                if (!overflowRecovered) {
                    overflowRecovered = true
                    stateMirrors.setStatus(sessId, "上下文超限，正在紧急压缩历史后重试")
                    agentEventLogger.log(
                        sessId,
                        "ContextOverflowRecovery",
                        "识别到上下文超限，执行紧急压缩：${contextOverflow.message}",
                        contextOverflow,
                    )
                    val recovered = recoverFromContextOverflow(sessId, model, compactionManager, agentEventLogger)
                    if (recovered != null) {
                        agentEventLogger.log(
                            sessId,
                            "ContextOverflowRecovery",
                            "紧急压缩完成：折叠 ${recovered.first} 条，保留 ${recovered.second} 条，重新组装请求重试",
                        )
                        resetStreamBaseline()
                        requestMessages = assembleFor(requestModel)
                        continue
                    }
                }
                val pendingImages = requestMessages.sumOf { it.imageUrls.size }
                if (!imageStripped && pendingImages > 0) {
                    imageStripped = true
                    // 故意不调 assembleFor 重算：走到这里说明紧急压缩已失败/无效（持久层消息仍带图片），
                    // 重新组装会把图片原样带回，立即再次超限。内存剥离是唯一能真正降低请求体积的手段
                    requestMessages = requestMessages.map { it.copy(imageUrls = emptyList()) }
                    agentEventLogger.log(
                        sessId,
                        "ContextOverflowImageStrip",
                        "紧急压缩后仍超限，剥离 $pendingImages 张图片降级重试",
                        contextOverflow,
                    )
                    resetStreamBaseline()
                    continue
                }
                // 与 ModelError 路径一致：已流式产出的半截内容先落库保留，避免静默丢弃
                settleFailedStream()
                agentEventLogger.log(
                    sessId,
                    "ContextOverflowUnrecoverable",
                    "上下文超限且压缩/剥离图片均无法恢复",
                    contextOverflow,
                )
                return TurnProviderOutcome.Failed(
                    "上下文超出模型窗口，自动压缩后仍无法恢复。" +
                        "可以让模型用 compress 工具手动压缩，或切换到更大上下文窗口的模型后重试。",
                )
            } catch (empty: LlmEmptyResponseException) {
                stateMirrors.providerActivity.retry(sessId, "empty_response")
                // 必须位于 IOException 之前：LlmEmptyResponseException 继承 IOException。
                // 空响应多为上游瞬时异常（网关提前断流、配额抖动），原样重发通常即可恢复；
                // 超出预算则明确失败并给出原始响应首部——绝不返回"什么都没有"，
                // 否则上层会走"无工具调用 → 收尾 → Outcome=completed"，前台零提示。
                currentCoroutineContext().ensureActive()
                if (!overflowRecovered && shouldCompactEmptyResponse(
                        estimateRetryRequestTokens(requestMessages) + toolSchemaTokens, requestModel,
                    )
                ) {
                    overflowRecovered = true
                    stateMirrors.setThinkingLive(sessId, false)
                    stateMirrors.setStatus(sessId, "长上下文返回空响应，正在压缩历史后重试")
                    val recovered = recoverFromContextOverflow(sessId, requestModel, compactionManager, agentEventLogger)
                    agentEventLogger.log(sessId, "EmptyResponseContextRecovery",
                        "长上下文空响应压缩结果：${recovered ?: "无可折叠历史"}", empty)
                    if (recovered != null) {
                        metrics.streamRetry()
                        resetStreamBaseline()
                        requestMessages = assembleFor(requestModel)
                        continue
                    }
                }
                requestMessages = stripEmptyResponseImages(++netRetry, EMPTY_RESPONSE_MAX_RETRIES, requestMessages, sessId, agentEventLogger, empty)
                if (netRetry > EMPTY_RESPONSE_MAX_RETRIES) {
                    stateMirrors.setThinkingLive(sessId, false)
                    messageProjector.remove(sessId, assistantId)
                    agentEventLogger.log(sessId, "EmptyModelResponse", empty.message.orEmpty(), empty)
                    // 文案已按长度受控（正文 + 截断首部），此处不套 friendly() 的 200 字截断，
                    // 否则尾部的原始响应首部会被整段砍掉，等于失去唯一线索。
                    return TurnProviderOutcome.Failed(
                        (if (overflowRecovered) "已尝试自动压缩上下文，但模型仍返回空响应。\n" else "") +
                            empty.message.orEmpty().ifBlank { ProviderClient.EMPTY_RESPONSE_MESSAGE },
                    )
                }
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                stateMirrors.setStatus(sessId, "模型返回空响应，正在重试（$netRetry/$EMPTY_RESPONSE_MAX_RETRIES）")
                agentEventLogger.log(sessId, "EmptyModelResponseRetry", "第 $netRetry 次空响应，重试上限 $EMPTY_RESPONSE_MAX_RETRIES")
                resetStreamBaseline()
                delay(retryPolicy.delayForRetry(netRetry).milliseconds)
            } catch (io: IOException) {
                stateMirrors.providerActivity.retry(sessId, "network_io")
                // 用户取消会主动关闭 socket，通常以 IOException 形式抛出：先按取消语义保留已生成内容，再传播取消。
                if (!currentCoroutineContext().isActive) {
                    persistCancelledPartial(sessId, assistantId, assistantAt, streamText, streamReasoning, startedAt, operationId, round)
                    withContext(NonCancellable) {
                        agentEventLogger.log(sessId, "Cancelled", "用户主动取消执行，保留已生成内容 ${streamText.length} 字符")
                    }
                }
                currentCoroutineContext().ensureActive()
                io.firstResponseTimeoutOrNull()?.let { return failFirstResponseTimeout(sessId, assistantId, it) }
                netRetry++
                // 瞬态故障（断线 / 超时 / TLS 中断 / 上游 5xx）与请求体大小、上下文规模无关，
                // 至少保留 TRANSIENT_MAX_RETRIES 次重试，不被大上下文降级压到 1 次——
                // 否则长会话一次 503（如 Cloudflare 524）就会让整轮失败，用户只能手动接续。
                val transient = isTransientFailure(io)
                val retryBudget = effectiveRetryBudget(maxNetworkRetries, io)
                // netRetry 表示「这是第几次失败」；净重试预算为 retryBudget 次，故第 retryBudget+1 次失败即放弃。
                // 原写法 "重试 $netRetry/$retryBudget" 会被误读成「已执行第 retryBudget 次重试、仍在继续」。
                agentEventLogger.log(
                    sessId,
                    "NetworkRetry",
                    "网络中断，第 $netRetry 次失败（重试上限 $retryBudget 次" +
                        (if (transient) "，瞬态故障不受大上下文降级" else "") +
                        "，${retryPolicy.delayForRetry(netRetry)}ms 后重试）：${io.message}",
                    io,
                )
                if (netRetry > retryBudget) {
                    settleFailedStream()
                    throw io
                }
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                stateMirrors.setStatus(sessId, "网络中断，自动重发中（第 $netRetry 次失败，上限 $retryBudget）")
                resetStreamBaseline()
                delay(retryPolicy.delayForRetry(netRetry).milliseconds)
            } catch (throwable: Throwable) {
                stateMirrors.setThinkingLive(sessId, false)
                // 模型不支持图片输入（HTTP 400）时，剥离全部图片降级重试一次，避免整轮中断
                val lowerMsg = throwable.message.orEmpty().lowercase()
                val pendingImages = requestMessages.sumOf { it.imageUrls.size }
                if (!imageStripped && pendingImages > 0 &&
                    ("do not support image" in lowerMsg || "does not support image" in lowerMsg ||
                        "image input" in lowerMsg || "supports image" in lowerMsg ||
                        "image not supported" in lowerMsg ||
                        "不支持图片" in lowerMsg || "不支持图像" in lowerMsg ||
                        "图片输入" in lowerMsg || "图像输入" in lowerMsg)
                ) {
                    imageStripped = true
                    requestMessages = requestMessages.map { it.copy(imageUrls = emptyList()) }
                    agentEventLogger.log(
                        sessId, "VisionFallback",
                        "模型不支持图片输入，已剥离 $pendingImages 张图片降级重试", throwable,
                    )
                    resetStreamBaseline()
                    continue
                }
                agentEventLogger.log(sessId, "ModelError", "LLM 调用失败: ${throwable.message}", throwable)
                settleFailedStream()
                return TurnProviderOutcome.Failed(friendly(throwable))
            }
        }
        // 仅在本轮请求真正成功后记录估算值，与 HarnessLoop 里 recordUsage 的计量轮次对齐
        metrics.recordEstimatedInput(estimateRetryRequestTokens(requestMessages) + toolSchemaTokens)
        messageProjector.endStreaming(sessId)
        return TurnProviderOutcome.Success(streamed, streamText.toString())
    }

    /** 落库助手回复或原生输出锚点；两者都没有时只结算 usage。 */
    suspend fun persistAssistantOutput(
        sessId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        operationId: String,
        result: ChatResult,
        effectiveModel: ModelConfig,
        displayText: String,
        hasToolCalls: Boolean,
    ) {
        if (displayText.isNotEmpty() || result.responsesTurn != null) {
            persistAssistant(
                sessId,
                assistantId,
                assistantAt,
                displayText,
                result.reasoningContent,
                totalMs = if (!hasToolCalls) now() - startedAt else null,
                reasoningMs = result.reasoningMs,
                responsesTurn = result.responsesTurn,
                operationId = operationId,
                round = round,
                usage = result.usage,
                model = effectiveModel,
            )
        } else {
            val usageEntity = result.usage.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = null,
                    provider = effectiveModel.provider,
                    modelId = effectiveModel.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, null, usage = usageEntity, round = round)
        }
    }

    private suspend fun persistAssistant(
        sessId: String,
        id: String,
        createdAt: Long,
        text: String,
        reasoning: String? = null,
        totalMs: Long? = null,
        reasoningMs: Long? = null,
        operationId: String? = null,
        round: Int = 0,
        usage: ChatUsage? = null,
        model: ModelConfig? = null,
        responsesTurn: ResponsesTurn? = null,
    ) {
        val message = AssistantText(
            id = id,
            createdAt = createdAt,
            text = text,
            reasoning = reasoning,
            totalMs = totalMs,
            reasoningMs = reasoningMs,
            responsesTurn = responsesTurn,
            modelId = model?.model,
            providerId = model?.provider,
            promptTokens = usage?.inputTokens?.takeIf { it > 0 }?.toInt(),
            completionTokens = usage?.outputTokens?.takeIf { it > 0 }?.toInt(),
            cachedTokens = usage?.cacheReadTokens?.takeIf { it > 0 }?.toInt(),
        )
        if (operationId != null) {
            val usageEntity = usage?.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = id,
                    provider = model?.provider,
                    modelId = model?.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, message, usage = usageEntity, round = round)
        } else {
            messageStore.append(sessId, message)
        }
        messageProjector.publishPersisted(sessId, message)
    }

    /** 用户主动停止时保留已流式内容；协程已取消，落库需 NonCancellable，失败退回删除半截气泡。 */
    private suspend fun persistCancelledPartial(
        sessId: String,
        assistantId: String,
        assistantAt: Long,
        streamText: StreamBuffer,
        streamReasoning: StreamBuffer,
        startedAt: Long,
        operationId: String,
        round: Int,
    ) {
        val keptChars = streamText.length
        stateMirrors.setThinkingLive(sessId, false)
        withContext(NonCancellable) {
            if (keptChars > 0) {
                runCatching {
                    persistAssistant(
                        sessId, assistantId, assistantAt,
                        streamText.toString(),
                        streamReasoning.toString().ifBlank { null },
                        totalMs = now() - startedAt,
                        operationId = operationId,
                        round = round,
                    )
                }.onFailure { messageProjector.remove(sessId, assistantId) }
            } else {
                messageProjector.remove(sessId, assistantId)
            }
        }
    }
    private fun now(): Long = System.currentTimeMillis()
    private fun friendly(throwable: Throwable): String =
        throwable.message?.take(200) ?: throwable::class.simpleName.orEmpty()

    /** 首个响应看门狗到点：不按网络中断重试，直接给出与上游错误区分开的说明。 */
    private suspend fun failFirstResponseTimeout(
        sessId: String,
        assistantId: String,
        error: FirstResponseTimeoutException,
    ): TurnProviderOutcome.Failed {
        stateMirrors.setThinkingLive(sessId, false)
        messageProjector.remove(sessId, assistantId)
        val seconds = (error.waitedMs / 1_000L).toInt().coerceAtLeast(1)
        val text = context.getString(R.string.harness_first_response_timeout, seconds)
        agentEventLogger.log(sessId, "FirstResponseTimeout", text, error)
        return TurnProviderOutcome.Failed(text)
    }

    companion object {
        private const val OUTPUT_LIMIT_MAX_RECOVERIES = 1
        private const val EMPTY_RESPONSE_MAX_RETRIES = 2

        internal fun emergencyFoldBudget(model: ModelConfig): Int = providerEmergencyFoldBudget(model)
        internal fun maxNetworkRetriesFor(estimatedRequestTokens: Int, configuredRetries: Int): Int =
            providerMaxNetworkRetriesFor(estimatedRequestTokens, configuredRetries)
        internal fun effectiveRetryBudget(largeContextRetries: Int, throwable: Throwable): Int =
            providerEffectiveRetryBudget(largeContextRetries, throwable)
        internal fun isTransientFailure(throwable: Throwable): Boolean = providerIsTransientFailure(throwable)

        const val RETRY_BACKOFF_MS = 1_000L
        const val RETRY_BACKOFF_SEC = 2L
    }
}

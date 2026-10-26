package top.tianyan.app.harness.dual

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.subagent.SubagentLaneRunner

/**
 * 双智能体调度中枢（Dual-Agent Coordinator）。
 *
 * 核心设计（借鉴 DeepSeek-Reasonix v2）：
 * 1. 物理会话隔离：Planner 运行于纯净无工具 Lane，Executor 运行于独立的单步 Lane；
 * 2. 异构模型协同：Planner 使用推理模型深思熟虑，Executor 使用极速模型精准执行；
 * 3. 前缀缓存保护：海量工具日志停留在 Executor 内部，仅提炼紧凑交付物回传 Planner；
 * 4. DAG 依赖感知与并行调度：识别多工序依赖拓扑，无依赖步骤通过协程并发执行，提速 2~3 倍；
 * 5. 单步超时熔断：移动端单步限制 120 秒超时熔断，杜绝进程挂死；
 * 6. 事件总线透出：通过 HarnessEventBus 广播步骤状态变更，赋能 UI 步骤流实时呈现。
 */
class DualAgentCoordinator(
    private val providerClient: ProviderClient,
    private val laneRunner: SubagentLaneRunner,
    private val promptBuilder: PlannerPromptBuilder,
    private val sessionRepository: HarnessSessionRepository,
    private val eventBus: HarnessEventBus? = null,
) {

    /** Production tool entry point used by ToolExecutor. */
    suspend fun executeFromTool(
        args: JsonObject,
        sessionId: String,
        workspace: String,
    ): Pair<Boolean, String> {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(prompt.isNotBlank()) { "缺少参数：prompt" }
        val session = sessionRepository.findById(sessionId)
        val inheritedProfileId = session?.modelId
        val inheritedVariant = session?.modelVariant
        val plannerSelection = args["planner_model"]?.jsonPrimitive?.contentOrNull
        val executorSelection = args["executor_model"]?.jsonPrimitive?.contentOrNull
        val maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_TOOL_STEPS) ?: DEFAULT_MAX_STEPS
        val plannerModel = providerClient.resolveRequestedModel(plannerSelection, inheritedProfileId, inheritedVariant)
        val executorModel = providerClient.resolveRequestedModel(executorSelection, inheritedProfileId, inheritedVariant)
        return when (val outcome = execute(
            sessionId = sessionId,
            userPrompt = prompt,
            workspace = workspace,
            plannerModel = plannerModel,
            executorModel = executorModel,
            maxSteps = maxSteps,
        )) {
            is DualAgentOutcome.Success -> true to buildString {
                appendLine(outcome.finalReport)
                appendLine()
                append("双智能体执行完成：${outcome.plan.count { it.status == StepStatus.COMPLETED }}/${outcome.plan.size} 步，")
                append("${outcome.totalToolCalls} 次工具调用，${outcome.totalRounds} 轮规划。")
            }
            is DualAgentOutcome.Failed -> false to buildString {
                appendLine(outcome.message)
                if (outcome.plan.isNotEmpty()) {
                    append("当前进度：${outcome.plan.count { it.status == StepStatus.COMPLETED }}/${outcome.plan.size} 步完成。")
                }
            }
        }
    }

    suspend fun execute(
        sessionId: String,
        userPrompt: String,
        workspace: String,
        plannerModel: ModelConfig,
        executorModel: ModelConfig,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        onPlanUpdated: (List<PlanStep>) -> Unit = {},
        onStatusUpdate: (String) -> Unit = {},
    ): DualAgentOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val executionId = UUID.randomUUID().toString()
        val steps = mutableListOf<PlanStep>()
        var totalToolCalls = 0
        var roundCount = 0

        // Planner 上下文：保持极简与增量 append-only
        val plannerSystem = promptBuilder.buildSystemPrompt(workspace)
        val plannerMessages = mutableListOf<ApiMessage>(
            ApiMessage(role = "system", content = plannerSystem),
            ApiMessage(role = "user", content = userPrompt),
        )

        // Planner 纯规划模式：完全关闭工具调用注入
        val effectivePlannerModel = plannerModel.copy(
            pureChatMode = true,
            toolCallMode = ToolCallMode.DISABLED,
        )

        while (roundCount < maxSteps) {
            roundCount++
            onStatusUpdate("规划者 (Planner) 正在深度推演…（轮次 $roundCount/$maxSteps）")

            // 1. 调用 Planner 生成决策
            val plannerResult = runCatching {
                providerClient.chat(effectivePlannerModel, plannerMessages)
            }.getOrElse { throwable ->
                return@withContext DualAgentOutcome.Failed(
                    message = "Planner 模型调用失败：${throwable.message}",
                    plan = steps,
                    failedStep = steps.lastOrNull { it.status == StepStatus.RUNNING },
                )
            }

            val plannerText = plannerResult.content.orEmpty()
            plannerMessages.add(ApiMessage(role = "assistant", content = plannerText))

            // 2. 解析 Planner 决策
            val decision = parsePlannerDecision(plannerText, steps)

            when (decision) {
                is PlannerDecision.Finish -> {
                    val incomplete = steps.filter { it.status != StepStatus.COMPLETED }
                    if (incomplete.isNotEmpty()) {
                        plannerMessages.add(
                            ApiMessage(
                                role = "user",
                                content = "计划仍有未完成工序：${incomplete.joinToString { "${it.id}(${it.status})" }}。" +
                                    "不得提前 FINISH；请继续执行就绪工序或使用 REPLAN 修复依赖。",
                            ),
                        )
                        continue
                    }
                    onPlanUpdated(steps)
                    onStatusUpdate("所有步骤已完成，任务已交付！")
                    return@withContext DualAgentOutcome.Success(
                        finalReport = decision.finalReport.ifBlank { plannerText },
                        plan = steps,
                        totalRounds = roundCount,
                        totalToolCalls = totalToolCalls,
                        totalDurationMs = System.currentTimeMillis() - startedAt,
                    )
                }

                is PlannerDecision.Replan -> {
                    onStatusUpdate("Planner 调整了执行方案：${decision.reason}")
                    // REPLAN 按 id 保留旧步骤状态：解析器生成的步骤恒为 PENDING，直接清空重置会让
                    // 已完成步骤被重复执行（写文件等副作用重复发生）。
                    // COMPLETED 必须保留；FAILED 不保留——配合 EXECUTE_STEP 的 FAILED 可重试语义，
                    // REPLAN 即是 Planner 对失败步骤的修复意图，失败步骤应按新指令重置为 PENDING 重新执行。
                    val previousStatus = steps.associate { it.id to it.status }
                    val previousSummary = steps.associate { it.id to it.resultSummary }
                    steps.clear()
                    steps.addAll(decision.newSteps.map { step ->
                        if (previousStatus[step.id] == StepStatus.COMPLETED) {
                            step.copy(status = StepStatus.COMPLETED, resultSummary = previousSummary[step.id])
                        } else {
                            step
                        }
                    })
                    onPlanUpdated(steps)
                    steps.forEach { emitProgress(sessionId, it, it.status) }

                    // 调度当前已就绪的步骤波次
                    val executedBatch = executeReadyBatch(
                        sessionId = sessionId,
                        executionId = executionId,
                        workspace = workspace,
                        steps = steps,
                        executorModel = executorModel,
                        onPlanUpdated = onPlanUpdated,
                        onStatusUpdate = onStatusUpdate,
                    )
                    totalToolCalls += executedBatch.sumOf { it.second.toolCallsCount }
                    feedBatchResultsToPlanner(plannerMessages, executedBatch, steps)
                }

                is PlannerDecision.InitializePlan -> {
                    onStatusUpdate("Planner 已制订全局步骤拆解（共 ${decision.plan.size} 项工序）")
                    steps.clear()
                    steps.addAll(decision.plan)
                    onPlanUpdated(steps)
                    decision.plan.forEach { emitProgress(sessionId, it, StepStatus.PENDING) }

                    // 调度首轮已就绪的并发步骤波次
                    val executedBatch = executeReadyBatch(
                        sessionId = sessionId,
                        executionId = executionId,
                        workspace = workspace,
                        steps = steps,
                        executorModel = executorModel,
                        onPlanUpdated = onPlanUpdated,
                        onStatusUpdate = onStatusUpdate,
                    )
                    totalToolCalls += executedBatch.sumOf { it.second.toolCallsCount }
                    feedBatchResultsToPlanner(plannerMessages, executedBatch, steps)
                }

                is PlannerDecision.ExecuteStep -> {
                    val currentStep = decision.step
                    val stepIndex = steps.indexOfFirst { it.id == currentStep.id }.let {
                        if (it >= 0) it else { steps.add(currentStep); steps.lastIndex }
                    }
                    val previous = steps[stepIndex]
                    steps[stepIndex] = when (previous.status) {
                        // COMPLETED 不可重跑：保留旧状态与摘要，避免写文件等副作用被重复执行
                        StepStatus.COMPLETED -> currentStep.copy(status = previous.status, resultSummary = previous.resultSummary)
                        // FAILED 可重试：Planner 重新下发同 id 步骤即重试意图，重置为 PENDING 重新执行
                        StepStatus.FAILED -> currentStep.copy(status = StepStatus.PENDING, resultSummary = null)
                        else -> currentStep.copy(status = previous.status, resultSummary = previous.resultSummary)
                    }
                    onPlanUpdated(steps)

                    // 所有 Planner 指令统一进入 DAG 就绪队列；禁止绕过未完成依赖直接执行。
                    val executedBatch = executeReadyBatch(
                        sessionId = sessionId,
                        executionId = executionId,
                        workspace = workspace,
                        steps = steps,
                        executorModel = executorModel,
                        onPlanUpdated = onPlanUpdated,
                        onStatusUpdate = onStatusUpdate,
                    )
                    totalToolCalls += executedBatch.sumOf { it.second.toolCallsCount }
                    feedBatchResultsToPlanner(plannerMessages, executedBatch, steps)
                }
            }
        }

        DualAgentOutcome.Failed(
            message = "已达到最大规划步数上限（$maxSteps 步），未能全部完成",
            plan = steps,
            failedStep = steps.lastOrNull { it.status != StepStatus.COMPLETED },
        )
    }

    /**
     * 识别当前拓扑中所有前置依赖已满足的就绪步骤，并在并发上限内并行执行。
     */
    private suspend fun executeReadyBatch(
        sessionId: String,
        executionId: String,
        workspace: String,
        steps: MutableList<PlanStep>,
        executorModel: ModelConfig,
        onPlanUpdated: (List<PlanStep>) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): List<Pair<PlanStep, StepExecutionResult>> = withContext(Dispatchers.IO) {
        // 先做级联失败：上一轮遗留的 FAILED 步骤会让其依赖者永不就绪，若不标记会活锁空转烧完 maxSteps
        cascadeFailures(sessionId, steps)
        val completedStepIds = steps.filter { it.status == StepStatus.COMPLETED }.map { it.id }.toSet()
        val readySteps = steps.filter { it.isReady(completedStepIds) }

        if (readySteps.isEmpty()) {
            return@withContext emptyList()
        }

        // 限制移动端单批并发上限（默认 3 个步骤并发）
        val batch = readySteps.take(MAX_CONCURRENT_STEPS)

        // 标记为执行中
        for (step in batch) {
            val idx = steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) steps[idx] = steps[idx].copy(status = StepStatus.RUNNING)
        }
        onPlanUpdated(steps)

        // 并发执行本批次步骤
        val results = batch.map { step ->
            async {
                executeSingleStep(sessionId, executionId, workspace, step, executorModel, onStatusUpdate)
            }
        }.awaitAll()

        // 写回内存状态
        for ((updatedStep, _) in results) {
            val idx = steps.indexOfFirst { it.id == updatedStep.id }
            if (idx >= 0) steps[idx] = updatedStep
        }
        // 本批失败步骤级联标记其（传递）依赖者，保证步骤集尽快全部终态
        cascadeFailures(sessionId, steps)
        onPlanUpdated(steps)

        results
    }

    /**
     * 级联失败：某步骤 FAILED 后，把所有（传递）依赖它的 PENDING 步骤标记为 FAILED，
     * 避免依赖者永不就绪导致主循环空转烧完 maxSteps。复用 FAILED 而非新增 BLOCKED 状态，
     * 保持 StepStatus 枚举序列化与既有事件消费方兼容；级联失败的步骤可由 Planner
     * 通过 EXECUTE_STEP（FAILED 可重试）重新执行。
     */
    private fun cascadeFailures(sessionId: String, steps: MutableList<PlanStep>) {
        val failedIds = steps.filter { it.status == StepStatus.FAILED }.mapTo(hashSetOf()) { it.id }
        if (failedIds.isEmpty()) return
        var changed = true
        while (changed) {
            changed = false
            for (index in steps.indices) {
                val step = steps[index]
                if (step.status == StepStatus.PENDING && step.dependencies.any { it in failedIds }) {
                    val cascaded = step.copy(status = StepStatus.FAILED, resultSummary = "前置工序失败，已级联标记为失败")
                    steps[index] = cascaded
                    failedIds.add(step.id)
                    emitProgress(sessionId, cascaded, StepStatus.FAILED)
                    changed = true
                }
            }
        }
    }

    /**
     * 执行单步工序，包含 120 秒超时熔断与 EventBus 事件通知。
     */
    private suspend fun executeSingleStep(
        sessionId: String,
        executionId: String,
        workspace: String,
        step: PlanStep,
        executorModel: ModelConfig,
        onStatusUpdate: (String) -> Unit,
    ): Pair<PlanStep, StepExecutionResult> {
        onStatusUpdate("执行者 (Executor) 正在执行：${step.title}")
        emitProgress(sessionId, step, StepStatus.RUNNING)

        val stepLaneName = "dual:$executionId:executor:${step.id}"
        val executorPrompt = buildString {
            appendLine("请严格执行以下单步工序并汇报成果：")
            appendLine("【步骤标题】: ${step.title}")
            appendLine("【详细指令】: ${step.instruction}")
            if (step.expectedOutcome.isNotBlank()) {
                appendLine("【预期成果】: ${step.expectedOutcome}")
            }
        }

        val startedAt = System.currentTimeMillis()
        val stepResult = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            try {
                laneRunner.run(
                    sessionId = sessionId,
                    laneName = stepLaneName,
                    prompt = executorPrompt,
                    workspace = workspace,
                    modelConfig = executorModel,
                )
            } catch (cancellation: CancellationException) {
                // 外部取消必须向上传播：runCatching 会连 CancellationException 一起吞成 null，
                // 导致取消被误判为步骤 FAILED。真正的步骤超时由 withTimeoutOrNull 自身捕获并返回 null，
                // 两者不能混淆。
                throw cancellation
            } catch (throwable: Throwable) {
                null
            }
        }

        val duration = System.currentTimeMillis() - startedAt
        val isSuccess = stepResult?.success == true
        val summary = when {
            stepResult == null -> "执行超时（已超出 ${STEP_TIMEOUT_MS / 1000} 秒）或遭遇系统异常中断"
            stepResult.summary.isNotBlank() -> stepResult.summary
            isSuccess -> "执行完成"
            else -> "执行中断或遇到异常"
        }

        val newStatus = if (isSuccess) StepStatus.COMPLETED else StepStatus.FAILED
        val updatedStep = step.copy(
            status = newStatus,
            resultSummary = summary,
        )
        emitProgress(sessionId, updatedStep, newStatus)

        val execResult = StepExecutionResult(
            stepId = step.id,
            success = isSuccess,
            summary = summary,
            diffStat = null,
            toolCallsCount = stepResult?.toolCallCount ?: 0,
            durationMs = duration,
        )
        return updatedStep to execResult
    }

    private fun feedBatchResultsToPlanner(
        plannerMessages: MutableList<ApiMessage>,
        batchResults: List<Pair<PlanStep, StepExecutionResult>>,
        allSteps: List<PlanStep>,
    ) {
        val feedbackText = buildString {
            if (batchResults.isEmpty()) {
                appendLine("【提示】当前计划中暂无满足前置依赖的可执行工序。")
                appendLine("请审视当前步骤状态并使用 REPLAN 或 FINISH 调整：")
            } else {
                appendLine("【本轮并发工序执行汇报（共 ${batchResults.size} 项）】")
                for ((step, res) in batchResults) {
                    appendLine("- 工序 ${step.id}（${step.title}）：${if (res.success) "✅ 成功" else "❌ 失败"}")
                    appendLine("  成果摘要: ${res.summary}")
                }
            }

            val allCompleted = allSteps.isNotEmpty() && allSteps.all { it.status == StepStatus.COMPLETED }
            if (allCompleted) {
                appendLine("所有计划工序已全部完成！请评估成果并交付最终报告（FINISH）。")
            } else {
                val remaining = allSteps.count { it.status == StepStatus.PENDING }
                val failed = allSteps.count { it.status == StepStatus.FAILED }
                appendLine("当前进度：剩余待执行 $remaining 项，失败 $failed 项。")
                appendLine("请决定下一步：继续下发 EXECUTE_STEP、调整方案 REPLAN 或提前交付 FINISH。")
            }
        }
        plannerMessages.add(ApiMessage(role = "user", content = feedbackText))
    }

    private fun emitProgress(sessionId: String, step: PlanStep, status: StepStatus) {
        eventBus?.emit(
            HarnessEvent.PlanStepProgress(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                stepId = step.id,
                title = step.title,
                status = status.name,
                dependencies = step.dependencies,
                resultSummary = step.resultSummary,
            )
        )
    }

    internal fun parsePlannerDecision(text: String, currentSteps: List<PlanStep>): PlannerDecision =
        PlannerProtocolParser.parse(text, currentSteps)

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val MAX_TOOL_STEPS = 30
        const val MAX_CONCURRENT_STEPS = 3
        const val STEP_TIMEOUT_MS = 120_000L
    }
}


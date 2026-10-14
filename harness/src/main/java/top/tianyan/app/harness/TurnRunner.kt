package top.tianyan.app.harness

import javax.inject.Inject
import javax.inject.Singleton

sealed interface TurnProviderOutcome {
    data class Success(val result: ChatResult, val streamText: String) : TurnProviderOutcome
    data class Failed(val message: String) : TurnProviderOutcome
}

sealed interface TurnOutcome {
    data object Complete : TurnOutcome
    data class Continue(
        val effectiveToolCallCount: Int,
        val toolsHadSuccess: Boolean,
        val followUpCount: Int = 0,
        /**
         * 交付闸门/声称审计要求模型补做的动作说明。
         * 非空表示本轮「继续」不是因为有工具调用，而是因为闸门否决了收尾，
         * HarnessLoop 需要把它写成一条会话内消息，让模型看到自己为什么还没结束。
         */
        val gateHint: String? = null,
    ) : TurnOutcome
    data class Failed(
        val message: String,
        /**
         * 是否值得自动重试一轮。
         *
         * 上游零输出（completionTokens==0 且无工具调用/无正文/无 reasoning）通常是
         * 网关瞬时断流或服务端抽风——同一个请求再发一次大概率就有内容。这类失败
         * 标记为可重试，由 HarnessLoop 决定是否补发（每会话限一次，避免死循环）。
         * 其他失败（无法解析的工具调用、真实空响应且上游确实回了 token）不重试。
         */
        val retryable: Boolean = false,
    ) : TurnOutcome
}

/**
 * One Agent turn: provider call -> protocol normalization -> durable assistant publication ->
 * follow-up/tool branch. Validation, approval and execution are supplied as one effect port so
 * this state machine stays deterministic while the production port retains its security policy.
 */
@Singleton
class TurnRunner @Inject constructor(
    private val normalizer: ProviderResponseNormalizer,
) {
    suspend fun run(
        toolsEnabled: Boolean,
        callProvider: suspend () -> TurnProviderOutcome,
        observeResponse: suspend (NormalizedProviderResponse) -> Unit = {},
        persistAssistant: suspend (NormalizedProviderResponse) -> Unit,
        consumeFollowUps: suspend () -> Int,
        enforceToolLimit: suspend (List<ApiToolCallSpec>, ChatResult) -> List<ApiToolCallSpec>,
        executeTools: suspend (List<ApiToolCallSpec>, ChatResult) -> Boolean,
        /**
         * 收尾前的交付闸门：入参是本轮助手的可见正文，返回非空即表示「不要收尾，让模型补做」。
         * 返回 null 表示放行完成。默认实现始终放行——不注入闸门时行为与改动前一致。
         */
        gateBeforeComplete: suspend (String) -> String? = { null },
    ): TurnOutcome {
        val provider = callProvider()
        if (provider is TurnProviderOutcome.Failed) return TurnOutcome.Failed(provider.message)
        provider as TurnProviderOutcome.Success

        val normalized = normalizer.normalize(provider.result, provider.streamText, toolsEnabled)
        observeResponse(normalized)
        persistAssistant(normalized)

        if (normalized.toolCalls.isEmpty() && normalized.hasUnresolvedMarkers) {
            return TurnOutcome.Failed(
                "模型返回了无法解析的文本工具调用；已停止，避免把未执行的工具请求误判为完成",
            )
        }
        // 空响应判定必须把「只输出思考」也算作有内容。
        //
        // 推理模型（DeepSeek-R1 类）有时只产出 reasoningContent 而没有正文，
        // 界面上能看到「推理思考过程」整段内容，但 displayText 是空的。
        // 早先这里只看 displayText，于是这类正常回复被误判成「模型返回了空响应」——
        // 用户看到的是自己明明有回复却报执行失败。
        val hasReasoningOnly = provider.result.reasoningContent?.isNotBlank() == true
        if (normalized.toolCalls.isEmpty() && normalized.displayText.isBlank() && !hasReasoningOnly) {
            // 诊断信息帮助区分两种截然不同的故障：
            //   · 上游零输出（completionTokens==0 且流首帧即结束）：网关断流/服务端瞬时故障，
            //     报「未收到任何输出 token」，用户能直接判断是上游问题而非自己配置错；
            //   · 上游回了很多 token 但都在我们拿不到的分片里（罕见协议错位）：报原始文案。
            val usage = provider.result.usage
            val completionTokens = usage.outputTokens
            val zeroOutput = completionTokens <= 0L
            val failure = if (zeroOutput) {
                "执行失败：模型未产出任何可展示内容（上游未返回输出 token）；" +
                    "如持续出现，请检查模型端点是否正常、切换模型或稍后重试"
            } else {
                "模型返回了空响应；本轮未收到可展示的答复或工具调用"
            }
            return TurnOutcome.Failed(failure, retryable = zeroOutput)
        }
        if (normalized.toolCalls.isEmpty()) {
            val followUpCount = consumeFollowUps()
            if (followUpCount > 0) {
                return TurnOutcome.Continue(
                    effectiveToolCallCount = 0,
                    toolsHadSuccess = true,
                    followUpCount = followUpCount,
                )
            }
            // 闸门必须在 follow-up 之后、Complete 之前：有排队消息时本来就会继续，
            // 此时再叠加闸门提示只会重复催促。
            val gateHint = gateBeforeComplete(normalized.displayText)
            if (gateHint != null) {
                return TurnOutcome.Continue(
                    effectiveToolCallCount = 0,
                    toolsHadSuccess = true,
                    gateHint = gateHint,
                )
            }
            return TurnOutcome.Complete
        }

        val effectiveCalls = enforceToolLimit(normalized.toolCalls, normalized.result)
        val toolsHadSuccess = executeTools(effectiveCalls, normalized.result)
        return TurnOutcome.Continue(
            effectiveToolCallCount = effectiveCalls.size,
            toolsHadSuccess = toolsHadSuccess,
        )
    }
}

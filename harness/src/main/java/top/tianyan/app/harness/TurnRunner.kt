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
    data class Failed(val message: String) : TurnOutcome
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
            return TurnOutcome.Failed("模型返回了空响应；本轮未收到可展示的答复或工具调用")
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

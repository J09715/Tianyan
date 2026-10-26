package top.tianyan.app.harness

import top.tianyan.app.harness.core.AgentTurnRunner
import top.tianyan.app.harness.core.ProviderTurnResult
import top.tianyan.app.harness.core.TurnLifecycle
import top.tianyan.app.harness.core.TurnOutcome

sealed interface TurnProviderOutcome {
    data class Success(val result: ChatResult, val streamText: String) : TurnProviderOutcome
    data class Failed(val message: String) : TurnProviderOutcome
}

/**
 * Protocol adapter for the pure Kotlin turn interpreter. HTTP results/textual tool protocols
 * remain here; publication barriers, finalization and continuation live in harness:core.
 */
class TurnRunner(
    private val normalizer: ProviderResponseNormalizer,
    private val groupFreshness: top.tianyan.app.harness.groupchat.GroupReplyFreshness? = null,
) {
    private val core = AgentTurnRunner()

    suspend fun run(
        toolsEnabled: Boolean,
        callProvider: suspend () -> TurnProviderOutcome,
        observeResponse: suspend (NormalizedProviderResponse) -> Unit = {},
        persistAssistant: suspend (NormalizedProviderResponse) -> Unit,
        consumeFollowUps: suspend () -> Int,
        enforceToolLimit: suspend (List<ApiToolCallSpec>, ChatResult) -> List<ApiToolCallSpec>,
        executeTools: suspend (List<ApiToolCallSpec>, ChatResult) -> Boolean,
        remainingRounds: Int = Int.MAX_VALUE,
        lifecycle: TurnLifecycle<NormalizedProviderResponse>? = null,
        sessionId: String? = null,
    ): TurnOutcome = kotlinx.coroutines.withContext(sessionId?.let { top.tianyan.app.harness.bot.TaskBudgetContext(it) } ?: kotlin.coroutines.EmptyCoroutineContext) { core.run(
        callProvider = {
            sessionId?.let { groupFreshness?.refresh(it) }
            when (val provider = callProvider()) {
                is TurnProviderOutcome.Failed -> ProviderTurnResult.Failed(provider.message)
                is TurnProviderOutcome.Success -> ProviderTurnResult.Success(
                    normalizer.normalize(provider.result, provider.streamText, toolsEnabled),
                )
            }
        },
        observeResponse = observeResponse,
        persistAssistant = persistAssistant,
        consumeFollowUps = {
            consumeFollowUps() + (sessionId?.let { groupFreshness?.refresh(it, beforeCompletion = true) } ?: 0)
        },
        enforceToolLimit = { calls, response -> enforceToolLimit(calls, response.result) },
        executeTools = { calls, response -> executeTools(calls, response.result) },
        remainingRounds = remainingRounds,
        lifecycle = lifecycle,
    ) }
}

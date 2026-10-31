package top.tianyan.app.harness.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One platform-independent turn. Publication callbacks must return only after durable commit.
 * Tool validation, approvals, concurrency and protocol mapping remain in the effect adapters.
 */
class AgentTurnRunner {
    suspend fun <Call, Response : TurnResponse<Call>> run(
        callProvider: suspend () -> ProviderTurnResult<Response>,
        persistAssistant: suspend (Response) -> Unit,
        consumeFollowUps: suspend () -> Int,
        enforceToolLimit: suspend (List<Call>, Response) -> List<Call>,
        executeTools: suspend (List<Call>, Response) -> Boolean,
        observeResponse: suspend (Response) -> Unit = {},
        remainingRounds: Int = Int.MAX_VALUE,
        lifecycle: TurnLifecycle<Response>? = null,
    ): TurnOutcome {
        currentCoroutineContext().ensureActive()
        if (remainingRounds <= 0) return TurnOutcome.RoundLimit()
        lifecycle?.prepareRequest()
        currentCoroutineContext().ensureActive()
        val provider = callProvider()
        currentCoroutineContext().ensureActive()
        if (provider is ProviderTurnResult.Failed) {
            val outcome = TurnOutcome.Failed(provider.message)
            lifecycle?.finishTurn(CompletedTurn(null, outcome))
            currentCoroutineContext().ensureActive()
            return outcome
        }
        provider as ProviderTurnResult.Success
        val response = provider.response
        observeResponse(response)
        currentCoroutineContext().ensureActive()
        persistAssistant(response)
        // A callback may finish a commit in NonCancellable; never start tools after cancellation.
        currentCoroutineContext().ensureActive()
        response.failureMessage?.let { failure ->
            val outcome = TurnOutcome.Failed(failure)
            lifecycle?.finishTurn(CompletedTurn(response, outcome))
            currentCoroutineContext().ensureActive()
            return outcome
        }

        val calls = if (response.toolCalls.isEmpty()) emptyList() else {
            enforceToolLimit(response.toolCalls, response).also {
                currentCoroutineContext().ensureActive()
            }
        }
        val toolsHadSuccess = if (response.toolCalls.isEmpty()) true else executeTools(calls, response)
        currentCoroutineContext().ensureActive()
        val completed = if (response.toolCalls.isEmpty()) TurnOutcome.Complete else {
            TurnOutcome.Continue(calls.size, toolsHadSuccess)
        }
        val decision = lifecycle?.finishTurn(CompletedTurn(response, completed))
        currentCoroutineContext().ensureActive()
        if (decision == TurnDecision.END) return TurnOutcome.Complete
        if (response.toolCalls.isNotEmpty()) {
            return if (remainingRounds == 1) TurnOutcome.RoundLimit(calls.size, toolsHadSuccess)
            else completed
        }

        val followUpCount = consumeFollowUps()
        currentCoroutineContext().ensureActive()
        require(followUpCount >= 0) { "Follow-up count must not be negative" }
        if (followUpCount == 0 && decision != TurnDecision.CONTINUE) return TurnOutcome.Complete
        return if (remainingRounds == 1) TurnOutcome.RoundLimit() else {
            TurnOutcome.Continue(0, toolsHadSuccess = true, followUpCount = followUpCount)
        }
    }
}

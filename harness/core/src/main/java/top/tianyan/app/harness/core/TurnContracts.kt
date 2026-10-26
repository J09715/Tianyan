package top.tianyan.app.harness.core

/** Provider adapters retain protocol details; the interpreter only needs calls and validity. */
interface TurnResponse<out Call> {
    val toolCalls: List<Call>
    val failureMessage: String? get() = null
}

sealed interface ProviderTurnResult<out Response> {
    data class Success<Response>(val response: Response) : ProviderTurnResult<Response>
    data class Failed(val message: String) : ProviderTurnResult<Nothing>
}

sealed interface TurnOutcome {
    data object Complete : TurnOutcome
    data class Continue(
        val effectiveToolCallCount: Int,
        val toolsHadSuccess: Boolean,
        val followUpCount: Int = 0,
    ) : TurnOutcome

    /** The committed turn used its segment budget; the caller decides whether to resume. */
    data class RoundLimit(
        val effectiveToolCallCount: Int = 0,
        val toolsHadSuccess: Boolean = true,
    ) : TurnOutcome

    data class Failed(val message: String) : TurnOutcome
}

/** A decision applies to this turn once; ordinary tools/queued input can satisfy CONTINUE. */
enum class TurnDecision { CONTINUE, END }

/** No response is available if the provider failed before returning a normalized message. */
data class CompletedTurn<Response>(val response: Response?, val outcome: TurnOutcome)

/**
 * Awaited control hooks, inspired by pi v1.1.0 prepareRequest/finishTurn.
 * These are execution barriers, not best-effort telemetry subscribers.
 * Exceptions propagate. Cancellation propagates without running hooks in NonCancellable.
 */
interface TurnLifecycle<Response> {
    /** Runs before the provider request, after the caller has selected and committed input. */
    suspend fun prepareRequest() = Unit

    /**
     * Runs after assistant/tool publication, before follow-up consumption and return.
     * Failed responses are reported too, but their decisions cannot resume or complete a run.
     * An unconditional CONTINUE can loop; the caller's round budget remains authoritative.
     */
    suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision? = null
}

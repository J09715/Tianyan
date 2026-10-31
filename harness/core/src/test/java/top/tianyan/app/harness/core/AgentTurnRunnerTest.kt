package top.tianyan.app.harness.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnRunnerTest {
    private data class Response(
        override val toolCalls: List<String> = emptyList(),
        override val failureMessage: String? = null,
    ) : TurnResponse<String>

    private val runner = AgentTurnRunner()

    private suspend fun turn(
        response: Response = Response(),
        persist: suspend (Response) -> Unit = {},
        execute: suspend (List<String>, Response) -> Boolean = { _, _ -> true },
        followUps: suspend () -> Int = { 0 },
        lifecycle: TurnLifecycle<Response>? = null,
        rounds: Int = 2,
        provider: suspend () -> ProviderTurnResult<Response> = { ProviderTurnResult.Success(response) },
    ) = runner.run(
        callProvider = provider, persistAssistant = persist, executeTools = execute,
        enforceToolLimit = { calls, _ -> calls }, consumeFollowUps = followUps,
        lifecycle = lifecycle, remainingRounds = rounds,
    )

    @Test
    fun `tools cannot start until assistant commit completes`() = runTest {
        val committed = CompletableDeferred<Unit>()
        var executed = false
        val run = async {
            turn(Response(listOf("write")), persist = { committed.await() },
                execute = { _, _ -> executed = true; true })
        }
        runCurrent()
        assertFalse(executed)
        assertFalse(run.isCompleted)
        committed.complete(Unit)
        assertEquals(TurnOutcome.Continue(1, true), run.await())
        assertTrue(executed)
    }

    @Test
    fun `failed assistant commit prevents tools queues and finalization`() = runTest {
        val failure = IllegalStateException("database unavailable")
        val actual = runCatching {
            turn(Response(listOf("write")), persist = { throw failure },
                execute = { _, _ -> error("must not execute") },
                followUps = { error("must not consume") },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision? =
                        error("must not finalize an uncommitted response")
                })
        }.exceptionOrNull()
        assertSame(failure, actual)
    }

    @Test
    fun `cancellation during non cancellable publication cannot start tools`() = runTest {
        val committed = CompletableDeferred<Unit>()
        var executed = false
        val run = async {
            turn(Response(listOf("write")), persist = { withContext(NonCancellable) { committed.await() } },
                execute = { _, _ -> executed = true; true })
        }
        runCurrent()
        run.cancel()
        committed.complete(Unit)
        run.join()
        assertTrue(run.isCancelled)
        assertFalse(executed)
    }

    @Test
    fun `prepare request is awaited before the first provider call`() = runTest {
        val prepared = CompletableDeferred<Unit>()
        var called = false
        val run = async {
            turn(provider = { called = true; ProviderTurnResult.Success(Response()) },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun prepareRequest() { prepared.await() }
                })
        }
        runCurrent()
        assertFalse(called)
        prepared.complete(Unit)
        assertEquals(TurnOutcome.Complete, run.await())
    }

    @Test
    fun `finalization awaits every tool result before polling follow ups`() = runTest {
        val events = mutableListOf<String>()
        val settled = CompletableDeferred<Unit>()
        val run = async {
            turn(Response(listOf("read")), persist = { events += "assistant committed" },
                execute = { _, _ -> settled.await(); events += "tools committed"; true },
                followUps = { error("tool continuation must not consume follow ups") },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision? {
                        events += "finished"
                        assertEquals(TurnOutcome.Continue(1, true), turn.outcome)
                        return TurnDecision.CONTINUE
                    }
                })
        }
        runCurrent()
        assertEquals(listOf("assistant committed"), events)
        settled.complete(Unit)
        assertEquals(TurnOutcome.Continue(1, true), run.await())
        assertEquals(listOf("assistant committed", "tools committed", "finished"), events)
    }

    @Test
    fun `end decision waits for finalization and preserves queued input`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val run = async {
            turn(followUps = { error("END must preserve follow ups") },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision {
                        finished.await()
                        return TurnDecision.END
                    }
                })
        }
        runCurrent()
        assertFalse(run.isCompleted)
        finished.complete(Unit)
        assertEquals(TurnOutcome.Complete, run.await())
    }

    @Test
    fun `continue decision requests one turn and queued follow ups satisfy it`() = runTest {
        val lifecycle = object : TurnLifecycle<Response> {
            override suspend fun finishTurn(turn: CompletedTurn<Response>) = TurnDecision.CONTINUE
        }
        assertEquals(TurnOutcome.Continue(0, true), turn(lifecycle = lifecycle))
        assertEquals(TurnOutcome.Continue(0, true, 2), turn(lifecycle = lifecycle, followUps = { 2 }))
        assertEquals(TurnOutcome.RoundLimit(), turn(lifecycle = lifecycle, rounds = 1))
    }

    @Test
    fun `failed response cannot be completed or continued by finalization`() = runTest {
        for (decision in TurnDecision.entries) {
            var finished = false
            val result = turn(Response(failureMessage = "invalid tool protocol"),
                followUps = { error("must preserve follow ups") },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision {
                        finished = true
                        assertEquals(TurnOutcome.Failed("invalid tool protocol"), turn.outcome)
                        return decision
                    }
                })
            assertTrue(finished)
            assertEquals(TurnOutcome.Failed("invalid tool protocol"), result)
        }
    }

    @Test
    fun `provider failure finalizes without publishing or consuming input`() = runTest {
        val result = turn(provider = { ProviderTurnResult.Failed("offline") },
            persist = { error("must not publish") }, followUps = { error("must not consume") },
            lifecycle = object : TurnLifecycle<Response> {
                override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision {
                    assertEquals(null, turn.response)
                    return TurnDecision.CONTINUE
                }
            })
        assertEquals(TurnOutcome.Failed("offline"), result)
    }

    @Test
    fun `exhausted budget cannot prepare request or touch any effects`() = runTest {
        val result = turn(rounds = 0, provider = { error("must not request") },
            lifecycle = object : TurnLifecycle<Response> {
                override suspend fun prepareRequest() = error("must not prepare")
            })
        assertEquals(TurnOutcome.RoundLimit(), result)
    }

    @Test
    fun `tool commit failure cannot run finalization`() = runTest {
        val failure = IllegalStateException("tool result commit failed")
        val actual = runCatching {
            turn(Response(listOf("write")), execute = { _, _ -> throw failure },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision? =
                        error("must not finalize")
                })
        }.exceptionOrNull()
        assertSame(failure, actual)
    }

    @Test
    fun `finalization failure cannot consume follow ups`() = runTest {
        val failure = IllegalStateException("finalization failed")
        val actual = runCatching {
            turn(followUps = { error("must preserve queued input") },
                lifecycle = object : TurnLifecycle<Response> {
                    override suspend fun finishTurn(turn: CompletedTurn<Response>): TurnDecision? = throw failure
                })
        }.exceptionOrNull()
        assertSame(failure, actual)
    }
}

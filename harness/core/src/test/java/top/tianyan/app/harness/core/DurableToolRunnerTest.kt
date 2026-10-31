package top.tianyan.app.harness.core

import kotlinx.coroutines.CancellationException
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
class DurableToolRunnerTest {
    @Test
    fun `intent and result commits are both awaited`() = runTest {
        val intent = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val run = async {
            DurableToolRunner.run(
                commitIntent = { intent.await(); events += "intent" },
                execute = { events += "execute"; "written" },
                commitResult = { result.await(); events += it },
                executionFailure = { error("must not convert") },
            )
        }
        runCurrent()
        assertTrue(events.isEmpty())
        intent.complete(Unit)
        runCurrent()
        assertEquals(listOf("intent", "execute"), events)
        assertFalse(run.isCompleted)
        result.complete(Unit)
        assertEquals("written", run.await())
        assertEquals(listOf("intent", "execute", "written"), events)
    }

    @Test
    fun `intent commit failure cannot become a tool result or execute a command`() = runTest {
        val failure = IllegalStateException("intent database failure")
        val actual = runCatching {
            DurableToolRunner.run(
                commitIntent = { throw failure },
                execute = { error("must not execute") },
                commitResult = { _: String -> error("must not settle") },
                executionFailure = { error("must not convert infrastructure failure") },
            )
        }.exceptionOrNull()
        assertSame(failure, actual)
    }

    @Test
    fun `result commit failure escapes without converting or reexecuting`() = runTest {
        val failure = IllegalStateException("result database failure")
        var executions = 0
        val actual = runCatching {
            DurableToolRunner.run(
                commitIntent = {}, execute = { executions++; "written" },
                commitResult = { throw failure },
                executionFailure = { error("must not convert infrastructure failure") },
            )
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(1, executions)
    }

    @Test
    fun `ordinary execution failure is durably reported to the model`() = runTest {
        val failure = IllegalArgumentException("file missing")
        var persisted: String? = null
        val result = DurableToolRunner.run(
            commitIntent = {}, execute = { throw failure }, commitResult = { persisted = it },
            executionFailure = { assertSame(failure, it); "failed: file missing" },
        )
        assertEquals("failed: file missing", result)
        assertEquals(result, persisted)
    }

    @Test
    fun `execution cancellation remains cancellation for recovery`() = runTest {
        val cancellation = CancellationException("user stopped")
        val actual = runCatching {
            DurableToolRunner.run(
                commitIntent = {}, execute = { throw cancellation },
                commitResult = { _: String -> error("recovery owns the dangling intent") },
                executionFailure = { error("must not convert cancellation") },
            )
        }.exceptionOrNull()
        assertSame(cancellation, actual)
    }

    @Test
    fun `cancellation after intent commit cannot execute even if commit ignores cancellation`() = runTest {
        val committed = CompletableDeferred<Unit>()
        var executed = false
        val run = async {
            DurableToolRunner.run(
                commitIntent = { withContext(NonCancellable) { committed.await() } },
                execute = { executed = true; "written" }, commitResult = {},
                executionFailure = { error("must not convert") },
            )
        }
        runCurrent()
        run.cancel()
        committed.complete(Unit)
        run.join()
        assertFalse(executed)
        assertTrue(run.isCancelled)
    }

    @Test
    fun `cancellation during result commit cannot return successful completion`() = runTest {
        val committed = CompletableDeferred<Unit>()
        var returned = false
        val run = async {
            DurableToolRunner.run(
                commitIntent = {}, execute = { "written" },
                commitResult = { withContext(NonCancellable) { committed.await() } },
                executionFailure = { error("must not convert") },
            )
            returned = true
        }
        runCurrent()
        run.cancel()
        committed.complete(Unit)
        run.join()
        assertFalse(returned)
        assertTrue(run.isCancelled)
    }
}

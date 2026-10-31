package top.tianyan.app.runtime.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProcessRegistryLifecycleTest {
    @Test fun serviceRegistrationRetainsItsCapturedDistribution() = scenario { f ->
        val process = f.registry.start("tool-service:debian:gateway", ShellCommand("fake"), "gateway", distroId = "debian")
        assertEquals("debian", process.distroId)
        assertSame(process, f.registry.list().single())
    }

    @Test fun queuedInstanceStopCannotFollowAReplacementWithTheSameId() = scenario { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.start("service").apply { beforeClose = { entered.complete(Unit); release.await() } }
        val owned = f.registry.list().single()
        val replacing = async(start = CoroutineStart.UNDISPATCHED) { f.start("service") }
        entered.await()
        val staleStop = async(start = CoroutineStart.UNDISPATCHED) { f.registry.stopInstance(owned) }
        release.complete(Unit)
        val replacement = replacing.await()
        assertTrue(staleStop.await())
        assertTrue(replacement.isAlive)
        assertSame(replacement, f.registry.list().single().session)
        f.registry.stopAll()
    }
    @Test fun exactInstanceStopRejectsCopiedHandlesAndCannotStopAReplacement() = scenario { f ->
        f.start("service")
        val old = f.registry.list().single()
        assertFalse(f.registry.stopInstance(old.copy()))
        assertTrue(old.session.isAlive)
        val replacement = f.start("service")
        assertTrue(f.registry.stopInstance(old))
        assertTrue(replacement.isAlive)
        assertSame(replacement, f.registry.list().single().session)
        assertTrue(f.registry.stopInstance(f.registry.list().single()))
        assertTrue(f.registry.list().isEmpty())
    }

    @Test fun exactInstanceStopKeepsFailedCleanupForRetry() = scenario { f ->
        val session = f.start("service").apply { failures = 1 }
        val owned = f.registry.list().single()
        assertTrue(failure { f.registry.stopInstance(owned) } is ProcessCleanupException)
        assertSame(owned, f.registry.list().single())
        assertTrue(f.registry.stopInstance(owned))
        assertEquals(2, session.closes)
        assertTrue(f.registry.list().isEmpty())
    }
    @Test fun failedReplacementRetainsOldProcessAndDoesNotLaunchNewOne() = scenario { f ->
        val old = f.start("service")
        old.failures = 1
        val error = failure { f.start("service") }
        assertTrue(error is ProcessCleanupException)
        assertSame(old, f.registry.list().single().session)
        assertEquals(1, f.opens)
        val replacement = f.start("service")
        assertEquals(2, old.closes)
        assertNotSame(old, replacement)
        f.registry.stopAll()
        assertEquals(2, old.closes)
        assertEquals(1, replacement.closes)
    }

    @Test fun bulkStopAttemptsAllAndRetriesOnlyFailedProcesses() = scenario { f ->
        val first = f.start("a").apply { failures = 1 }
        val second = f.start("b").apply { failures = 1 }
        val third = f.start("c")
        val error = failure { f.registry.stopAll() } as ProcessCleanupException
        assertEquals(2, error.failures.size)
        assertEquals(1, error.suppressed.size)
        assertEquals(setOf("a", "b"), f.registry.list().map { it.id }.toSet())
        assertEquals(listOf(1, 1, 1), listOf(first.closes, second.closes, third.closes))
        f.registry.stopAll()
        assertEquals(listOf(2, 2, 1), listOf(first.closes, second.closes, third.closes))
        assertTrue(f.registry.list().isEmpty())
    }

    @Test fun deadProcessCleanupReleasesResourcesAndKeepsFailuresForRetry() = scenario { f ->
        val failed = f.start("a").apply { alive = false; failures = 1 }
        val dead = f.start("b").apply { alive = false }
        val live = f.start("c")
        failure { f.registry.cleanupDeadProcesses() }
        assertEquals(setOf("a", "c"), f.registry.list().map { it.id }.toSet())
        assertEquals(1, failed.closes)
        assertEquals(1, dead.closes)
        assertEquals(0, live.closes)
        assertEquals(1, f.registry.cleanupDeadProcesses())
        assertEquals(2, failed.closes)
        assertEquals(0, f.registry.cleanupDeadProcesses())
    }

    @Test fun stoppingWaitsForLogReaderFinallyBeforeClosingSession() = scenario { f ->
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.nextOutput = flow {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { draining.complete(Unit); release.await() }
            }
        }
        val session = f.start("service")
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.registry.stop("service") }
        draining.await()
        assertFalse(stop.isCompleted)
        assertEquals(0, session.closes)
        assertEquals(1, f.registry.list().size)
        release.complete(Unit)
        assertTrue(stop.await())
        assertEquals(1, session.closes)
        assertTrue(f.registry.getLogs("service").last().contains("已停止"))
    }

    @Test fun canceledStopStillDrainsAndCannotRaceReplacement() = scenario { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = f.start("service").apply {
            beforeClose = { entered.complete(Unit); release.await() }
        }
        val stop = launch(start = CoroutineStart.UNDISPATCHED) { f.registry.stop("service") }
        entered.await()
        stop.cancel()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { f.start("service") }
        assertEquals(1, f.opens)
        assertFalse(stop.isCompleted)
        release.complete(Unit)
        stop.join()
        val new = replacement.await()
        assertEquals(1, old.closes)
        assertEquals(0, new.closes)
        assertSame(new, f.registry.list().single().session)
    }

    @Test fun canceledBulkStopStillAttemptsEveryProcess() = scenario { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sessions = listOf(f.start("a"), f.start("b"), f.start("c"))
        sessions.forEach { session -> session.beforeClose = { entered.complete(Unit); release.await() } }
        val stop = launch(start = CoroutineStart.UNDISPATCHED) { f.registry.stopAll() }
        entered.await()
        stop.cancel()
        release.complete(Unit)
        stop.join()
        assertEquals(listOf(1, 1, 1), sessions.map { it.closes })
        assertTrue(f.registry.list().isEmpty())
    }

    @Test fun canceledStartupClosesLateResultAndRetainsFailedRollback() = scenario { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeOpen = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        f.nextFailures = 1
        val start = launch(start = CoroutineStart.UNDISPATCHED) { f.start("service") }
        entered.await()
        start.cancel()
        release.complete(Unit)
        start.join()
        val session = f.sessions.single()
        assertEquals(1, session.closes)
        assertSame(session, f.registry.list().single().session)
        assertTrue(f.registry.stop("service"))
        assertEquals(2, session.closes)
        assertTrue(f.registry.list().isEmpty())
    }

    @Test fun startupFailurePreservesOriginalErrorAndRollbackFailure() = scenario { f ->
        val primary = IllegalArgumentException("pid lookup failed")
        f.nextPidFailure = primary
        f.nextFailures = 1
        val error = failure { f.start("service") }
        assertSame(primary, error)
        assertTrue(error.suppressed.single() is ProcessCleanupException)
        f.registry.stopAll()
        assertEquals(2, f.sessions.single().closes)
    }

    @Test fun sameToolLogsCannotReceiveOldGenerationAfterReplacement() = scenario { f ->
        val finish = CompletableDeferred<Unit>()
        f.nextOutput = flow {
            emit(TerminalOutput(TerminalStream.STDOUT, "old\n"))
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { finish.complete(Unit) }
            }
        }
        f.start("service", "tool")
        f.nextOutput = flow {
            assertTrue(finish.isCompleted)
            emit(TerminalOutput(TerminalStream.STDOUT, "new\n"))
            awaitCancellation()
        }
        f.start("service", "tool")
        val logs = f.registry.getLogs("tool")
        assertEquals("new", logs.last())
        assertEquals(1, logs.count { it.contains("已停止") })
        assertEquals(logs, f.registry.getLogs("service"))
    }

    @Test fun concurrentStopsReleaseSuccessfulGenerationOnce() = scenario { f ->
        val session = f.start("service")
        val first = async { f.registry.stop("service") }
        val second = async { f.registry.stop("service") }
        assertEquals(setOf(true, false), setOf(first.await(), second.await()))
        assertEquals(1, session.closes)
    }

    @Test fun stopQueuedDuringReplacementCannotStopNewGeneration() = scenario { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = f.start("service").apply {
            beforeClose = { entered.complete(Unit); release.await() }
        }
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { f.start("service") }
        entered.await()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.registry.stop("service") }
        release.complete(Unit)
        val new = replacement.await()
        assertFalse(stop.await())
        assertEquals(1, old.closes)
        assertEquals(0, new.closes)
        assertSame(new, f.registry.list().single().session)
    }

    @Test fun canceledRegistryScopeRejectsNewAllocation() = scenario { f ->
        f.scope.cancel()
        failure { f.start("service") }
        assertEquals(0, f.opens)
        assertTrue(f.registry.list().isEmpty())
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            try { test(fixture) } finally {
                withContext(NonCancellable) { fixture.registry.stopAll(); fixture.scope.cancel() }
            }
        }
    }

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("Expected failure")
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessions = mutableListOf<Session>()
        var opens = 0
        var beforeOpen: suspend () -> Unit = {}
        var nextFailures = 0
        var nextPidFailure: Throwable? = null
        var nextOutput: Flow<TerminalOutput> = flow { awaitCancellation() }
        val registry = ProcessRegistryImpl(RegistrySessionFactory { _, _, _ ->
            opens++
            beforeOpen()
            Session(nextOutput, nextPidFailure).also { it.failures = nextFailures; sessions += it }
        }, scope)
        suspend fun start(id: String, toolId: String? = null): Session =
            registry.start(id, ShellCommand("fake"), toolId).session as Session
    }

    private class Session(
        override val output: Flow<TerminalOutput>,
        private val pidFailure: Throwable?,
    ) : LinuxSession {
        var alive = true
        var closes = 0
        var failures = 0
        var beforeClose: suspend () -> Unit = {}
        override val isAlive get() = alive
        override val pid: Long? get() { pidFailure?.let { throw it }; return null }
        override suspend fun close() {
            closes++
            beforeClose()
            if (failures > 0) { failures--; throw IllegalStateException("close failed") }
            alive = false
        }
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
    }
}

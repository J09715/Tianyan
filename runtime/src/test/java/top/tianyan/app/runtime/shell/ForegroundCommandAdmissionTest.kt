package top.tianyan.app.runtime.shell

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundCommandAdmissionTest {
    private class FakeExecutor : ShellExecutor {
        var receivedTimeoutMs: Long? = null
        override suspend fun execute(command: List<String>, workingDirectory: File?, environment: Map<String, String>,
            timeoutMs: Long, onOutput: ((String) -> Unit)?): CommandResult {
            receivedTimeoutMs = timeoutMs
            return CommandResult(0, "ok", "", 7)
        }
    }
    private val executor = FakeExecutor()
    private val heavy = ShellCommand("gradle build", "/workspace/one", timeoutMs = 20)
    private fun result() = CommandResult(0, "ok", "", 7)

    @Test fun differentWorkspacesShareFifoSlotWhileLightQueriesProceed() = runBlocking {
        withTimeout(5_000) {
            val gate = ForegroundCommandAdmission()
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                gate.execute(heavy, executor) { started += "first"; release.await(); result() }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                gate.execute(heavy.copy(workingDirectory = "/workspace/two"), executor) { started += "second"; result() }
            }
            val third = async(start = CoroutineStart.UNDISPATCHED) {
                gate.execute(heavy.copy(workingDirectory = "/workspace/three"), executor) { started += "third"; result() }
            }
            val query = gate.execute(ShellCommand("git status"), executor) { started += "query"; result() }
            assertEquals(0L, query.resourceWaitMs)
            assertEquals(listOf("first", "query"), started)
            release.complete(Unit)
            awaitAll(first, second, third)
            assertEquals(listOf("first", "query", "second", "third"), started)
        }
    }

    @Test fun cancelledWaiterNeverExecutesOrConsumesTheNextPermit() = runBlocking {
        withTimeout(5_000) {
            val gate = ForegroundCommandAdmission()
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.execute(heavy, executor) { release.await(); result() } }
            var cancelledRan = false
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                gate.execute(heavy, executor) { cancelledRan = true; result() }
            }
            cancelled.cancelAndJoin()
            val next = async(start = CoroutineStart.UNDISPATCHED) { gate.execute(heavy, executor) { result() } }
            release.complete(Unit)
            first.await(); next.await()
            assertFalse(cancelledRan)
        }
    }

    @Test fun activeCancellationRetainsSlotUntilTeardownFinishes() = runBlocking {
        withTimeout(5_000) {
            val gate = ForegroundCommandAdmission()
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.execute(heavy, executor) {
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); cleanupRelease.await() } }
                }
            }
            first.cancel()
            cleanupEntered.await()
            var nextRan = false
            val next = async(start = CoroutineStart.UNDISPATCHED) { gate.execute(heavy, executor) { nextRan = true; result() } }
            gate.execute(ShellCommand("pwd"), executor) { result() }
            assertFalse(nextRan)
            cleanupRelease.complete(Unit)
            first.join(); next.await()
            assertTrue(nextRan)
        }
    }

    @Test fun waitIsSeparateFromExecutionTimeoutAndCaptureStartsAfterAdmission() = runBlocking {
        withTimeout(5_000) {
            val clock = AtomicLong(0)
            val gate = ForegroundCommandAdmission(clock::get)
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.execute(heavy, executor) { release.await(); result() } }
            CommandOutputCapture().use { capture ->
                var allocated = false
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    gate.execute(heavy.copy(outputCapture = capture), executor) {
                        allocated = true
                        assertSame(capture, currentCoroutineContext()[CommandOutputCapture])
                        executor.execute(listOf("fake"), timeoutMs = heavy.timeoutMs)
                    }
                }
                assertFalse(allocated)
                assertNull(currentCoroutineContext()[CommandOutputCapture])
                clock.set(100_000_000)
                release.complete(Unit)
                first.await()
                val observed = next.await()
                assertEquals(100L, observed.resourceWaitMs)
                assertEquals(7L, observed.durationMs)
                assertEquals(20L, executor.receivedTimeoutMs)
            }
        }
    }

    @Test fun ordinaryFailureReleasesSlotAndDoesNotChangeOutcome() = runBlocking {
        val gate = ForegroundCommandAdmission()
        val failure = IllegalStateException("fake failure")
        assertSame(failure, runCatching { gate.execute(heavy, executor) { throw failure } }.exceptionOrNull())
        val failed = gate.execute(heavy, executor) { result().copy(exitCode = 1, stderr = "build failed") }
        assertFalse(failed.isSuccess)
        assertEquals("build failed", failed.stderr)
        assertTrue(gate.execute(heavy, executor) { result() }.isSuccess)
    }

    @Test fun concurrentCallersNeverOverlapMemoryHeavyExecutions() = runBlocking {
        withTimeout(5_000) {
            val gate = ForegroundCommandAdmission()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            List(30) {
                async(Dispatchers.Default) {
                    gate.execute(heavy, executor) {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        try { yield(); result() } finally { active.decrementAndGet() }
                    }
                }
            }.awaitAll()
            assertEquals(1, peak.get())
            assertEquals(0, active.get())
        }
    }

    @Test fun failedProcessTeardownBlocksHeavyAllocationUntilExplicitCleanupSucceeds() = runBlocking {
        withTimeout(10_000) {
            val process = FakeSessionProcess().apply { forcedExit = false }
            var starts = 0
            val processes = ProcessShellExecutor({ emptyMap() }) { _, _, _ ->
                starts++; if (starts == 1) process else FakeSessionProcess().also { it.exit() }
            }
            val gate = ForegroundCommandAdmission()
            val first = launch { gate.execute(heavy, processes) { processes.execute(listOf("fake"), timeoutMs = 10_000) } }
            withContext(Dispatchers.IO) { assertTrue(process.stdout.entered.await(3, TimeUnit.SECONDS)) }
            assertFalse(processes.hasUnsettled())
            first.cancelAndJoin()
            assertTrue(processes.hasUnsettled())
            val rejected = runCatching { gate.execute(heavy, processes) { processes.execute(listOf("fake")) } }.exceptionOrNull()
            assertTrue(rejected is IllegalStateException)
            assertEquals(1, starts)
            process.forcedExit = true
            processes.closePending()
            assertFalse(processes.hasUnsettled())
            assertTrue(gate.execute(heavy, processes) { processes.execute(listOf("fake")) }.isSuccess)
            assertEquals(2, starts)
        }
    }

    @Test fun retainedTeardownSelfHealsOnNextHeavyCommandOnceProcessIsGone() = runBlocking {
        withTimeout(10_000) {
            val process = FakeSessionProcess().apply { forcedExit = false }
            var starts = 0
            val processes = ProcessShellExecutor({ emptyMap() }) { _, _, _ ->
                starts++; if (starts == 1) process else FakeSessionProcess().also { it.exit() }
            }
            val gate = ForegroundCommandAdmission()
            val first = launch { gate.execute(heavy, processes) { processes.execute(listOf("fake"), timeoutMs = 10_000) } }
            withContext(Dispatchers.IO) { assertTrue(process.stdout.entered.await(3, TimeUnit.SECONDS)) }
            first.cancelAndJoin()
            assertTrue(processes.hasUnsettled())

            // 残留进程此刻已消亡：下一次重型命令准入即自动重试收尾并放行，不必等用户手动清理
            process.forcedExit = true
            assertTrue(gate.execute(heavy, processes) { processes.execute(listOf("fake")) }.isSuccess)
            assertFalse(processes.hasUnsettled())
            assertEquals(2, starts)
        }
    }
}

package top.tianyan.app.runtime.shell

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProcessLinuxSessionLifecycleTest {
    @Test fun repeatedCloseReleasesProcessStreamsAndCallbackOnce() = scenario { f ->
        f.session.close()
        val inputCloses = f.process.stdout.closes.get()
        f.session.close()
        assertEquals(1, f.process.destroys.get())
        assertEquals(1, f.process.waits.get())
        assertEquals(1, f.process.stdin.closes.get())
        assertEquals(inputCloses, f.process.stdout.closes.get())
        assertEquals(1, f.process.stderr.closes.get())
        assertEquals(1, f.cleanups)
        assertFalse(f.session.isAlive)
    }

    @Test fun cleanupFailureIsReportedAndOnlyFailedCallbackIsRetried() = scenario { f ->
        f.cleanupFailures = 1
        val error = failure { f.session.close() } as ProcessCleanupException
        assertEquals("cleanup failed", error.failures.single().message)
        val inputCloses = f.process.stdout.closes.get()
        f.session.close()
        assertEquals(1, f.process.destroys.get())
        assertEquals(1, f.process.waits.get())
        assertEquals(inputCloses, f.process.stdout.closes.get())
        assertEquals(1, f.process.stdin.closes.get())
        assertEquals(2, f.cleanups)
    }

    @Test fun registryRetainsSessionWhenUnderlyingCleanupCallbackFails() = scenario { f ->
        val registry = ProcessRegistryImpl(RegistrySessionFactory { _, _, _ -> f.session }, f.scope)
        registry.start("service", ShellCommand("fake"))
        f.cleanupFailures = 1
        failure { registry.stop("service") }
        assertSame(f.session, registry.list().single().session)
        assertEquals(1, f.cleanups)
        registry.stopAll()
        assertTrue(registry.list().isEmpty())
        assertEquals(2, f.cleanups)
        assertEquals(1, f.process.destroys.get())
    }

    @Test fun gracefulTimeoutForcesTerminationAndConfirmsExit() = scenario { f ->
        f.process.gracefulExit = false
        f.session.close()
        assertEquals(1, f.process.destroys.get())
        assertEquals(1, f.process.forces.get())
        assertEquals(2, f.process.waits.get())
        assertEquals(1, f.cleanups)
    }

    @Test fun forcedTimeoutKeepsCleanupPendingUntilProcessReallyExits() = scenario { f ->
        f.process.gracefulExit = false
        f.process.forcedExit = false
        assertTrue(failure { f.session.close() }.message!!.contains("仍未退出"))
        assertTrue(f.process.alive)
        assertEquals(0, f.cleanups)
        assertEquals(0, f.process.stdin.closes.get())
        f.process.forcedExit = true
        f.session.close()
        assertEquals(2, f.process.forces.get())
        assertEquals(1, f.cleanups)
    }

    @Test fun forcedTimeoutReapsDescendantsWhileRootIsStillReachable() = scenario(childPid = 4242L) { f ->
        f.process.gracefulExit = false
        f.process.forcedExit = false
        assertTrue(failure { f.session.close() }.message!!.contains("仍未退出"))
        assertEquals(listOf(4242L), f.reaper.reaped)
        f.process.forcedExit = true
        f.session.close()
        assertEquals(1, f.reaper.reaped.size)
        assertEquals(1, f.cleanups)
    }

    @Test fun forcedTerminationAlsoSignalsTheChildsProcessGroup() = scenario(childPid = 4242L) { f ->
        // 网关自带进程组（host + server 同组）：只杀根会留下整组，必须 TERM → KILL 收尾。
        f.process.gracefulExit = false
        f.process.forcedExit = false
        f.reaper.group = 1133L
        assertTrue(failure { f.session.close() }.message!!.contains("仍未退出"))
        assertEquals(listOf(1133L to false, 1133L to true), f.reaper.groupSignals)
        f.process.forcedExit = true
        f.session.close()
    }

    @Test fun gracefulExitStillReapsDescendantsProotLeftBehind() = scenario(childPid = 4242L) { f ->
        // 网关真正的监听者是 tracer 的孙进程；tracer 优雅退出并不保证它把孙进程一并带走，
        // 否则停止后端口依旧被占，下一次启动必然失败。
        f.reaper.descendants = listOf(5000L, 5001L)
        f.session.close()
        assertTrue(f.reaper.reaped.isEmpty())
        assertEquals(listOf(listOf(5000L, 5001L)), f.reaper.detached)
    }

    @Test fun failedWaitCanBeRetriedWithoutSkippingReaderDrain() = scenario { f ->
        val error = IllegalStateException("wait failed")
        f.process.waitFailure = error
        val reported = failure { f.session.close() }
        assertEquals(error.message, reported.message)
        assertTrue(generateSequence(reported) { it.cause }.any { it === error })
        assertEquals(0, f.cleanups)
        f.session.close()
        assertEquals(1, f.process.destroys.get())
        assertEquals(2, f.process.waits.get())
        assertEquals(1, f.cleanups)
    }

    @Test fun callbackRunsAfterBlockingReaderFinallyHasDrained() = scenario { f ->
        f.process.stdout.closeGate = CountDownLatch(1)
        val close = async { f.session.close() }
        f.process.stdout.closeEntered.awaitReady()
        assertFalse(close.isCompleted)
        assertEquals(0, f.cleanups)
        assertEquals(0, f.process.stdin.closes.get())
        f.process.stdout.closeGate.countDown()
        close.await()
        assertEquals(1, f.cleanups)
    }

    @Test fun canceledCloseStillCompletesAndNextCloseDoesNotRepeatCleanup() = scenario { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeCleanup = { entered.complete(Unit); release.await() }
        val close = launch { f.session.close() }
        entered.await()
        close.cancel()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        close.join()
        f.session.close()
        assertEquals(1, f.cleanups)
        assertEquals(1, f.process.destroys.get())
    }

    @Test fun sessionCloseDoesNotCancelSuppliedScopeOrSiblingActivity() = scenario { f ->
        val sibling = f.scope.launch { kotlinx.coroutines.awaitCancellation() }
        f.session.close()
        assertTrue(f.scope.coroutineContext[Job]!!.isActive)
        assertTrue(sibling.isActive)
        sibling.cancel()
    }

    @Test fun streamFailuresAttemptOtherStepsAndRetryOnlyFailedStreams() = scenario { f ->
        f.process.stdin.closeFailures = 1
        f.process.stderr.closeFailures = 1
        val error = failure { f.session.close() } as ProcessCleanupException
        assertEquals(2, error.failures.size)
        assertEquals(1, error.suppressed.size)
        assertEquals(1, f.cleanups)
        f.session.close()
        assertEquals(2, f.process.stdin.closes.get())
        assertEquals(2, f.process.stderr.closes.get())
        assertEquals(1, f.cleanups)
    }

    @Test fun concurrentCloseSerializesFailedCleanupAndRetry() = scenario { f ->
        f.cleanupFailures = 1
        val first = async { runCatching { f.session.close() }.exceptionOrNull() }
        val second = async { runCatching { f.session.close() }.exceptionOrNull() }
        assertEquals(1, listOf(first.await(), second.await()).count { it is ProcessCleanupException })
        assertEquals(2, f.cleanups)
        assertEquals(1, f.process.destroys.get())
    }

    @Test fun cleanupCallbackCannotRecursivelyCloseItsOwnSession() = scenario { f ->
        f.beforeCleanup = { f.session.close() }
        val error = failure { f.session.close() } as ProcessCleanupException
        assertTrue(error.failures.single().message!!.contains("不能再次关闭"))
        f.beforeCleanup = {}
        f.session.close()
        assertEquals(2, f.cleanups)
        assertEquals(1, f.process.destroys.get())
    }

    @Test fun parentCancellationClosesOutputAndOwnershipCanStillReleaseProcess() = scenario { f ->
        f.scope.cancel()
        f.session.close()
        assertTrue(f.session.output.toList().isEmpty())
        assertEquals(1, f.cleanups)
    }

    @Test fun naturalExitCompletesOutputAndStillReleasesSessionResources() = scenario { f ->
        f.process.exit()
        assertTrue(f.session.output.toList().isEmpty())
        f.session.close()
        assertEquals(0, f.process.destroys.get())
        assertEquals(1, f.process.waits.get())
        assertEquals(1, f.cleanups)
    }

    @Test fun canceledScopeRejectsProductionLaunchBeforeProcessBuilderRuns() = scenario { f ->
        f.scope.cancel()
        val error = failure {
            ProcessLinuxSession(listOf("nonexistent-test-program"), scope = f.scope)
        }
        assertTrue(error is kotlinx.coroutines.CancellationException)
    }

    @Test fun closedSessionRejectsFurtherInputAndResizeCallback() = scenario { f ->
        f.session.close()
        f.session.write(byteArrayOf(1, 2))
        f.session.interrupt()
        f.session.resize(80, 24)
        assertEquals(0, f.process.stdin.writes.get())
        assertEquals(0, f.resizes)
    }

    private fun scenario(childPid: Long? = null, test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(10_000) {
            val fixture = Fixture(childPid)
            try {
                fixture.process.stdout.entered.awaitReady()
                test(fixture)
            } finally {
                fixture.process.stdout.closeGate.countDown()
                fixture.process.exit()
                fixture.beforeCleanup = {}
                fixture.cleanupFailures = 0
                withContext(NonCancellable) { fixture.session.close(); fixture.scope.cancel() }
            }
        }
    }

    private suspend fun CountDownLatch.awaitReady() = withContext(Dispatchers.IO) {
        assertTrue("Timed out waiting for fake process", await(3, TimeUnit.SECONDS))
    }

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("Expected failure")
    }

    private class Fixture(childPid: Long? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val process = FakeSessionProcess()
        val reaper = FakeReaper()
        var cleanups = 0
        var cleanupFailures = 0
        var resizes = 0
        var beforeCleanup: suspend () -> Unit = {}
        val session = ProcessLinuxSession(process, scope = scope, reaper = reaper, childPid = childPid,
            resizeCallback = { _, _ -> resizes++ }, cleanupCallback = {
                cleanups++
                beforeCleanup()
                if (cleanupFailures > 0) { cleanupFailures--; error("cleanup failed") }
            })
    }

    /** 记录被收的根进程，避免单元测试触碰 /proc 与 android.os.Process。 */
    private class FakeReaper : ProcessTreeReaper {
        val reaped = mutableListOf<Long>()
        val detached = mutableListOf<List<Long>>()
        val groupSignals = mutableListOf<Pair<Long, Boolean>>()
        var descendants: List<Long> = emptyList()
        var group: Long? = null
        override fun forkTid(): Int? = null
        override fun childOfThread(tid: Int): Long? = null
        override fun descendantsOf(rootPid: Long): List<Long> = descendants
        override fun processGroupOf(pid: Long): Long? = group
        override fun signalGroup(processGroup: Long, force: Boolean): Boolean {
            groupSignals += processGroup to force
            return true
        }
        override fun reap(rootPid: Long): Int { reaped += rootPid; return 1 }
        override fun reapDetached(descendants: List<Long>): Int { detached += descendants; return descendants.size }
    }
}

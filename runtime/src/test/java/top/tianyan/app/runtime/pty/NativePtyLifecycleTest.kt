package top.tianyan.app.runtime.pty

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.InteractiveSessionRegistry
import top.tianyan.app.runtime.shell.ProcessCleanupException

class NativePtyLifecycleTest {
    @Test fun repeatedCloseReapsAndClosesEachOwnedResourceOnce() = scenario { f ->
        f.session.close()
        val reaps = f.backend.reaps.get()
        f.session.close()
        assertEquals(listOf(1), f.backend.signals)
        assertEquals(reaps, f.backend.reaps.get())
        assertEquals(1, f.backend.closes.get())
        assertEquals(1, f.cleanups)
        assertFalse(f.session.isAlive)
    }
    @Test fun naturalExitPreservesSplitUtf8AndDoesNotSignalAReapedPid() = scenario { f ->
        val bytes = "天衍".toByteArray()
        f.backend.chunks += bytes.copyOfRange(0, 2)
        f.backend.chunks += bytes.copyOfRange(2, bytes.size)
        f.backend.alive = false
        assertEquals("天衍", f.session.output.toList().joinToString("") { it.text })
        assertFalse(f.session.isAlive)
        f.session.close()
        assertTrue(f.backend.signals.isEmpty())
        assertEquals(1, f.cleanups)
    }
    @Test fun gracefulTimeoutEscalatesAndConfirmsReap() = scenario { f ->
        f.backend.gracefulExit = false
        f.session.close()
        assertEquals(listOf(1, 9), f.backend.signals)
        assertFalse(f.backend.alive)
        assertEquals(1, f.cleanups)
    }
    @Test fun failedTerminationRetainsChildAndDefersDependentCleanup() = scenario { f ->
        f.backend.gracefulExit = false
        f.backend.forcedExit = false
        val error = failure { f.session.close() } as ProcessCleanupException
        assertTrue(error.failures.single().message!!.contains("仍未退出"))
        assertTrue(f.session.isAlive)
        assertEquals(1, f.backend.closes.get())
        assertEquals(0, f.cleanups)
        f.backend.forcedExit = true
        f.session.close()
        assertEquals(1, f.backend.closes.get())
        assertEquals(1, f.cleanups)
    }
    @Test fun signalFailureStillReleasesDescriptorAndRemainsRetryable() = scenario { f ->
        f.backend.signalFailures = 1
        failure { f.session.close() }
        assertEquals(0, f.cleanups)
        assertEquals(1, f.backend.closes.get())
        f.session.close()
        assertEquals(1, f.backend.closes.get())
        assertEquals(1, f.cleanups)
    }
    @Test fun descriptorAndCallbackFailuresAreBothRetainedAndRetried() = scenario { f ->
        f.backend.closeFailures = 1
        f.cleanupFailures = 1
        val error = failure { f.session.close() } as ProcessCleanupException
        assertEquals(2, error.failures.size)
        assertEquals(1, error.suppressed.size)
        f.session.close()
        assertEquals(listOf(1), f.backend.signals)
        assertEquals(2, f.backend.closes.get())
        assertEquals(2, f.cleanups)
    }
    @Test fun successfulDescriptorIsNotClosedAgainWhenCallbackNeedsRetry() = scenario { f ->
        f.cleanupFailures = 1
        failure { f.session.close() }
        f.session.close()
        assertEquals(1, f.backend.closes.get())
        assertEquals(2, f.cleanups)
    }
    @Test fun descriptorCloseWaitsForBlockedReadToReturn() = scenario { f ->
        // Wait for the next read to enter a controlled native call.
        f.backend.readGate = CountDownLatch(1)
        f.backend.readBlocked.awaitReady()
        val close = async { f.session.close() }
        f.backend.signalled.awaitReady()
        assertEquals(0, f.backend.closes.get())
        assertEquals(0, f.cleanups)
        f.backend.readGate.countDown()
        close.await()
        assertEquals(1, f.backend.closes.get())
    }
    @Test fun pendingWriteDrainsBeforeDescriptorCanBeReused() = scenario { f ->
        f.backend.writeGate = CountDownLatch(1)
        val writing = async { f.session.write(byteArrayOf(1, 2, 3)) }
        f.backend.writeEntered.awaitReady()
        val close = async { f.session.close() }
        f.backend.signalled.awaitReady()
        assertEquals(0, f.backend.closes.get())
        f.backend.writeGate.countDown()
        writing.await()
        close.await()
        assertEquals(1, f.backend.closes.get())
    }
    @Test fun nonblockingAndPartialWritesDoNotDropInput() = scenario { f ->
        f.backend.zeroWrites = 1
        f.backend.partialWrite = 2
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        f.session.write(data)
        assertEquals(data.toList(), f.backend.written)
    }
    @Test fun canceledCloseStillFinishesCleanupAndLeavesParentScopeActive() = scenario { f ->
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
        assertTrue(f.scope.coroutineContext[Job]!!.isActive)
    }
    @Test fun concurrentCloseDoesNotReleaseTwice() = scenario { f ->
        awaitAll(async { f.session.close() }, async { f.session.close() })
        assertEquals(1, f.backend.closes.get())
        assertEquals(1, f.cleanups)
    }
    @Test fun callbackCannotRecursivelyCloseSession() = scenario { f ->
        f.beforeCleanup = { f.session.close() }
        val error = failure { f.session.close() } as ProcessCleanupException
        assertTrue(error.failures.single().message!!.contains("不能递归"))
        f.beforeCleanup = {}
        f.session.close()
        assertEquals(2, f.cleanups)
    }
    @Test fun runtimeRegistryRetainsFailedNativeOwnership() = scenario { f ->
        val registry = InteractiveSessionRegistry()
        registry.track(f.session, "ubuntu")
        f.cleanupFailures = 1
        failure { registry.closeAll() }
        assertTrue(registry.hasSessions())
        registry.closeAll()
        assertFalse(registry.hasSessions())
        assertEquals(1, f.backend.closes.get())
    }
    @Test fun closedSessionDoesNotAccessReusedDescriptor() = scenario { f ->
        f.session.close()
        f.session.write(byteArrayOf(1))
        f.session.resize(80, 24)
        f.session.interrupt()
        assertTrue(f.backend.written.isEmpty())
        assertEquals(0, f.backend.resizes.get())
    }
    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(8_000) {
            val fixture = Fixture()
            try { fixture.backend.readEntered.awaitReady(); test(fixture) } finally {
                fixture.backend.readGate.countDown()
                fixture.backend.writeGate.countDown()
                fixture.backend.alive = false
                fixture.beforeCleanup = {}
                fixture.cleanupFailures = 0
                fixture.backend.closeFailures = 0
                withContext(NonCancellable) { fixture.session.close(); fixture.scope.cancel() }
            }
        }
    }
    private suspend fun CountDownLatch.awaitReady() = withContext(Dispatchers.IO) {
        assertTrue(await(3, TimeUnit.SECONDS))
    }
    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("Expected failure")
    }
    private class Fixture {
        val backend = FakeNativePtyBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var cleanups = 0
        var cleanupFailures = 0
        var beforeCleanup: suspend () -> Unit = {}
        val session = NativePtySession(10, 42, backend, scope = scope, shutdownTimeoutMs = 30,
            cleanupCallback = {
                cleanups++
                beforeCleanup()
                if (cleanupFailures-- > 0) error("cleanup failed")
            })
    }
}

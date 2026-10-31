package top.tianyan.app.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ProcessCleanupException
import top.tianyan.app.runtime.shell.TerminalOutput

class InteractiveSessionRegistryTest {
    @Test fun failedCloseRetainsOwnershipEvenWhenBackendIsNotAlive() = runBlocking {
        val registry = InteractiveSessionRegistry()
        val session = Session().apply { failures = 1 }
        val tracked = registry.track(session, "ubuntu")
        failure { tracked.close() }
        assertFalse(tracked.isAlive)
        assertTrue(registry.hasSessions())
        registry.closeAll()
        assertFalse(registry.hasSessions())
        assertEquals(2, session.closes)
    }

    @Test fun repeatedAndConcurrentCloseReleaseSuccessfulSessionOnce() = runBlocking {
        val registry = InteractiveSessionRegistry()
        val session = Session()
        val tracked = registry.track(session, "ubuntu")
        val first = async { tracked.close() }
        val second = async { tracked.close() }
        first.await()
        second.await()
        tracked.close()
        registry.closeAll()
        assertEquals(1, session.closes)
        assertFalse(registry.hasSessions())
    }

    @Test fun bulkCloseAttemptsAllAndRetriesOnlyFailedSessions() = runBlocking {
        val registry = InteractiveSessionRegistry()
        val first = Session().apply { failures = 1 }
        val second = Session().apply { failures = 1 }
        val third = Session()
        listOf(first, second, third).forEach { registry.track(it, "ubuntu") }
        val error = failure { registry.closeAll() } as ProcessCleanupException
        assertEquals(2, error.failures.size)
        assertEquals(listOf(1, 1, 1), listOf(first, second, third).map { it.closes })
        registry.closeAll()
        assertEquals(listOf(2, 2, 1), listOf(first, second, third).map { it.closes })
        assertFalse(registry.hasSessions())
    }

    @Test fun distroCleanupKeepsOtherDistributionSessions() = runBlocking {
        val registry = InteractiveSessionRegistry()
        val ubuntu = Session()
        val alpine = Session()
        registry.track(ubuntu, "ubuntu")
        registry.track(alpine, "alpine")
        registry.closeAll("ubuntu")
        assertEquals(1, ubuntu.closes)
        assertEquals(0, alpine.closes)
        assertTrue(registry.hasSessions())
        registry.closeAll()
        assertEquals(1, alpine.closes)
    }

    @Test fun canceledCloseStillReleasesBeforeRemovingOwnership() = runBlocking {
        withTimeout(5_000) {
            val registry = InteractiveSessionRegistry()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session = Session().apply { beforeClose = { entered.complete(Unit); release.await() } }
            val tracked = registry.track(session, "ubuntu")
            val close = launch(start = CoroutineStart.UNDISPATCHED) { tracked.close() }
            entered.await()
            close.cancel()
            assertTrue(registry.hasSessions())
            assertFalse(close.isCompleted)
            release.complete(Unit)
            close.join()
            assertFalse(registry.hasSessions())
            assertEquals(1, session.closes)
        }
    }

    @Test fun recursivelyClosingTrackedSessionFailsWithoutDeadlocking() = runBlocking {
        withTimeout(5_000) {
            val registry = InteractiveSessionRegistry()
            val session = Session()
            val tracked = registry.track(session, "ubuntu")
            session.beforeClose = { tracked.close() }
            assertTrue(failure { tracked.close() }.message!!.contains("不能递归关闭"))
            assertTrue(registry.hasSessions())
            session.beforeClose = {}
            tracked.close()
            assertFalse(registry.hasSessions())
        }
    }

    @Test fun runtimeCleanupAttemptsBackgroundEvenIfInteractiveCleanupFails() = runBlocking {
        val interactive = IllegalStateException("interactive cleanup failed")
        val background = IllegalStateException("background cleanup failed")
        var backgroundAttempted = false
        val error = failure {
            closeRuntimeResources(listOf(
                { throw interactive },
                { backgroundAttempted = true; throw background },
            ))
        } as ProcessCleanupException
        assertTrue(backgroundAttempted)
        assertEquals(listOf(interactive, background), error.failures)
        assertSame(interactive, error.cause)
        assertSame(background, error.suppressed.single())
    }

    @Test fun canceledBulkRuntimeCleanupStillAttemptsRemainingResources() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var secondReleased = false
            val close = launch(start = CoroutineStart.UNDISPATCHED) {
                closeRuntimeResources(listOf(
                    { entered.complete(Unit); release.await() },
                    { secondReleased = true },
                ))
            }
            entered.await()
            close.cancel()
            release.complete(Unit)
            close.join()
            assertTrue(secondReleased)
        }
    }

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("Expected failure")
    }

    private class Session : LinuxSession {
        var closes = 0
        var failures = 0
        var beforeClose: suspend () -> Unit = {}
        override val isAlive = false
        override val output = emptyFlow<TerminalOutput>()
        override suspend fun close() {
            closes++
            beforeClose()
            if (failures > 0) { failures--; error("cleanup failed") }
        }
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
    }
}

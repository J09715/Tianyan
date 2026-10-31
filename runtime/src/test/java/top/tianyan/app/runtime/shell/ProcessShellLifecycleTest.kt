package top.tianyan.app.runtime.shell

import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.environment.ExecutionEnvironment
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentId

class ProcessShellLifecycleTest {
    @Test fun successDrainsReadersAndDetachesOwnership() = runBlocking {
        val process = FakeSessionProcess().also { it.exit() }
        val executor = executor(process)
        val owned = mutableListOf<suspend () -> Unit>()
        val result = withContext(binding(owned)) { executor.execute(listOf("fake")) }
        assertEquals(0, result.exitCode)
        assertFalse(executor.hasPending())
        assertTrue(owned.isEmpty())
        val streamCloses = process.stdin.closes.get()
        executor.closePending()
        assertEquals(streamCloses, process.stdin.closes.get())
    }
    @Test fun canceledCommandConfirmsTerminationBeforeReturning() = runBlocking {
        val process = FakeSessionProcess()
        val executor = executor(process)
        val running = launch { executor.execute(listOf("fake")) }
        awaitReader(process)
        running.cancelAndJoin()
        assertFalse(process.alive)
        assertEquals(1, process.forces.get())
        assertEquals(1, process.waits.get())
        assertFalse(executor.hasPending())
    }
    @Test fun canceledTeardownFailureRemainsInBothExecutorAndEnvironment() = runBlocking {
        val process = FakeSessionProcess().apply { forcedExit = false }
        val executor = executor(process)
        val owned = mutableListOf<suspend () -> Unit>()
        val running = launch(binding(owned)) { executor.execute(listOf("fake")) }
        awaitReader(process)
        assertFalse(executor.hasUnsettled())
        running.cancelAndJoin()
        assertTrue(executor.hasPending())
        assertTrue(executor.hasUnsettled())
        assertEquals(1, owned.size)
        process.forcedExit = true
        owned.single().invoke()
        assertFalse(executor.hasPending())
        assertTrue(owned.isEmpty())
        assertFalse(executor.hasUnsettled())
        assertEquals(2, process.forces.get())
    }
    @Test fun teardownFailureReapsProcessTreeBeforeRetainingResources() = runBlocking {
        val process = FakeSessionProcess().apply { forcedExit = false }
        val reaper = FakeReaper()
        val executor = ProcessShellExecutor({ emptyMap() }, reaper = reaper) { _, _, _ -> process }
        val running = launch { executor.execute(listOf("fake")) }
        awaitReader(process)
        running.cancelAndJoin()
        assertEquals(listOf(4242L), reaper.reaped)
        assertTrue(executor.hasUnsettled())
        process.forcedExit = true
        executor.closePending()
        assertFalse(executor.hasPending())
        assertEquals(1, reaper.reaped.size)
    }
    @Test fun closedEnvironmentRejectsCommandBeforeHostAllocation() = runBlocking {
        var starts = 0
        val executor = ProcessShellExecutor({ emptyMap() }) { _, _, _ -> starts++; FakeSessionProcess() }
        val failure = runCatching {
            withContext(ExecutionEnvironmentContext(world()) { error("owner closed") }) { executor.execute(listOf("fake")) }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, starts)
        assertFalse(executor.hasPending())
    }
    @Test fun timeoutCannotReportCompletionWhileProcessIsStillRunning() = runBlocking {
        val process = FakeSessionProcess().apply { forcedExit = false }
        val executor = executor(process)
        val failure = runCatching { executor.execute(listOf("fake"), timeoutMs = 20) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(executor.hasPending())
        assertTrue(process.alive)
        process.forcedExit = true
        executor.closePending()
        assertFalse(executor.hasPending())
    }
    @Test fun bulkRetryAttemptsEveryFailedForegroundProcess() = runBlocking {
        val first = FakeSessionProcess().apply { forcedExit = false }
        val second = FakeSessionProcess().apply { forcedExit = false }
        val processes = java.util.concurrent.ConcurrentLinkedQueue(listOf(first, second))
        val executor = ProcessShellExecutor({ emptyMap() }) { _, _, _ -> processes.remove() }
        val runs = List(2) { launch { executor.execute(listOf("fake")) } }
        awaitReader(first)
        awaitReader(second)
        runs.forEach { it.cancel() }
        runs.joinAll()
        first.forcedExit = true
        assertNotNull(runCatching { executor.closePending() }.exceptionOrNull())
        assertFalse(first.alive)
        assertTrue(second.alive)
        second.forcedExit = true
        executor.closePending()
        assertEquals(2, first.forces.get())
        assertEquals(3, second.forces.get())
        assertFalse(executor.hasPending())
    }
    private fun executor(process: FakeSessionProcess) = ProcessShellExecutor({ emptyMap() }) { _, _, _ -> process }

    /** 固定返回 tid/子进程 pid，记录被收的根进程，避免单元测试触碰 /proc 与 android.os.Process。 */
    private class FakeReaper : ProcessTreeReaper {
        val reaped = mutableListOf<Long>()
        val detached = mutableListOf<List<Long>>()
        override fun forkTid(): Int? = 7
        override fun childOfThread(tid: Int): Long? = if (tid == 7) 4242L else null
        override fun descendantsOf(rootPid: Long): List<Long> = emptyList()
        override fun reap(rootPid: Long): Int { reaped += rootPid; return 1 }
        override fun reapDetached(descendants: List<Long>): Int { detached += descendants; return descendants.size }
    }
    private suspend fun awaitReader(process: FakeSessionProcess) = withContext(Dispatchers.IO) {
        assertTrue(process.stdout.entered.await(3, TimeUnit.SECONDS))
    }
    private fun binding(owned: MutableList<suspend () -> Unit>) = ExecutionEnvironmentContext(world()) { release ->
        owned += release
        AutoCloseable { owned.remove(release) }
    }
    private fun world() = Proxy.newProxyInstance(ExecutionEnvironment::class.java.classLoader,
        arrayOf(ExecutionEnvironment::class.java)) { _, method, _ ->
        if (method.name == "getId") ExecutionEnvironmentId("test", "linux", "/workspace", "owner")
        else error("Unexpected local fallback ${method.name}")
    } as ExecutionEnvironment
}

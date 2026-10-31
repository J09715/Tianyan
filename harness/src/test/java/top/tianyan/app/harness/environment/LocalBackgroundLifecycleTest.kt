package top.tianyan.app.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.core.ResourceCleanupException
import top.tianyan.app.harness.core.SessionResourceScope
import top.tianyan.app.runtime.environment.ExecutionCommands
import top.tianyan.app.runtime.shell.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalBackgroundLifecycleTest {
    private class Process : LinuxSession {
        var closes = 0
        var failures = 0
        override var isAlive = true
        override val output = emptyFlow<TerminalOutput>()
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {
            closes++
            if (failures-- > 0) error("process close")
            isAlive = false
        }
    }
    private class Fixture {
        val owner = SessionResourceScope()
        val processes = mutableListOf<Process>()
        val running = mutableMapOf<String, ManagedProcess>()
        val stops = mutableListOf<ManagedProcess?>()
        var beforeStart: suspend (String) -> Unit = {}
        var beforeStop: suspend (String) -> Unit = {}
        val commands = object : ExecutionCommands {
            override suspend fun execute(command: ShellCommand) = CommandResult(0, "", "", 0)
            override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess {
                beforeStart(id)
                return ManagedProcess(id, 1, Process().also { processes += it }).also { running[id] = it }
            }
            override suspend fun stopBackground(id: String): Boolean {
                beforeStop(id)
                val process = running[id]
                stops += process
                if (process == null) return false
                process.session.close(); running.remove(id, process)
                return true
            }
            override fun listBackground() = running.values.toList()
            override fun getBackgroundLogs(id: String) = emptyList<String>()
        }
        val manager = LocalBackgroundProcesses(commands, owner)
        suspend fun start(id: String = "server") = manager.start(id, ShellCommand("serve"))
    }

    @Test fun `explicit stop detaches cleanup so owner close does not stop the process again`() = runTest {
        val fixture = Fixture()
        fixture.start()
        assertTrue(fixture.manager.stop("server"))
        fixture.owner.close(); fixture.owner.close()
        assertEquals(1, fixture.stops.size)
        assertEquals(1, fixture.processes.single().closes)
    }

    @Test fun `stopped generation cannot release a replacement when replacement close fails`() = runTest {
        val fixture = Fixture()
        fixture.start(); fixture.manager.stop("server"); fixture.start()
        fixture.processes[1].failures = 1
        try { fixture.owner.close(); fail("cleanup error lost") } catch (_: ResourceCleanupException) { }
        assertTrue(fixture.processes[1].isAlive)
        assertEquals(listOf(1, 1), fixture.processes.map { it.closes })
        fixture.owner.close()
        assertEquals(listOf(1, 2), fixture.processes.map { it.closes })
    }

    @Test fun `natural exit is cleaned before the same name starts another process`() = runTest {
        val fixture = Fixture()
        fixture.start(); fixture.processes[0].isAlive = false
        fixture.start(); fixture.owner.close()
        assertEquals(listOf(1, 1), fixture.processes.map { it.closes })
        assertEquals(2, fixture.stops.size)
    }

    @Test fun `failed old generation cleanup prevents same name replacement until retry succeeds`() = runTest {
        val fixture = Fixture()
        fixture.start(); fixture.processes[0].isAlive = false; fixture.processes[0].failures = 1
        try { fixture.start(); fail("replacement bypassed cleanup") } catch (_: ResourceCleanupException) { }
        assertEquals(1, fixture.processes.size)
        fixture.start(); fixture.owner.close()
        assertEquals(listOf(2, 1), fixture.processes.map { it.closes })
    }

    @Test fun `failed explicit stop remains owned for session disposal retry`() = runTest {
        val fixture = Fixture()
        fixture.start(); fixture.processes.single().failures = 1
        try { fixture.manager.stop("server"); fail("stop error lost") } catch (_: ResourceCleanupException) { }
        fixture.owner.close()
        assertEquals(2, fixture.processes.single().closes)
    }

    @Test fun `cancelled stop drains before same name restart and leaves no stale cleanup`() = runTest {
        val fixture = Fixture()
        fixture.start()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeStop = { gate.await() }
        val stopping = async { fixture.manager.stop("server") }
        runCurrent(); stopping.cancel()
        val restarting = async { fixture.start() }
        runCurrent(); assertFalse(restarting.isCompleted)
        gate.complete(Unit); stopping.join(); restarting.await()
        fixture.owner.close()
        assertEquals(listOf(1, 1), fixture.processes.map { it.closes })
    }

    @Test fun `slow startup does not block another name and owner disposal drains its late process`() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeStart = { if (it == "slow") withContext(NonCancellable) { gate.await() } }
        val slow = async { fixture.start("slow") }
        runCurrent()
        fixture.start("fast")
        assertEquals(listOf("fast"), fixture.running.keys.toList())
        val closing = async { fixture.owner.close() }
        runCurrent(); assertFalse(closing.isCompleted)
        gate.complete(Unit); closing.await(); slow.join()
        assertTrue(fixture.running.isEmpty())
        assertEquals(listOf(1, 1), fixture.processes.map { it.closes })
    }

    @Test fun `two starts with the same name cannot overwrite a running generation`() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeStart = { gate.await() }
        val first = async { fixture.start() }
        runCurrent()
        val second = async { runCatching { fixture.start() }.exceptionOrNull() }
        runCurrent(); gate.complete(Unit); first.await()
        assertTrue(second.await() is IllegalArgumentException)
        assertEquals(1, fixture.processes.size)
        fixture.owner.close()
    }

    @Test fun `closed owner refuses startup without invoking backend`() = runTest {
        val fixture = Fixture()
        fixture.owner.close()
        try { fixture.start(); fail("closed owner accepted") } catch (_: IllegalStateException) { }
        assertTrue(fixture.processes.isEmpty())
    }

    @Test fun `a process removed by backend cleanup does not make explicit stop report success`() = runTest {
        val fixture = Fixture()
        fixture.start(); fixture.running.clear()
        assertFalse(fixture.manager.stop("server"))
        fixture.owner.close()
        assertEquals(1, fixture.stops.size)
    }

    @Test fun `a queued old stop cannot stop the replacement that starts before it gets the lock`() = runTest {
        val fixture = Fixture()
        fixture.start()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeStop = { gate.await() }
        val firstStop = async { fixture.manager.stop("server") }
        runCurrent()
        val restarting = async { fixture.start() }
        runCurrent()
        val staleStop = async { fixture.manager.stop("server") }
        runCurrent(); gate.complete(Unit)
        assertTrue(firstStop.await()); restarting.await()
        assertFalse(staleStop.await())
        assertTrue(fixture.processes[1].isAlive)
        fixture.owner.close()
        assertEquals(listOf(1, 1), fixture.processes.map { it.closes })
    }

    @Test fun `owner disposal attempts every generation and retry only touches failed releases`() = runTest {
        val fixture = Fixture()
        for (id in listOf("one", "two", "three")) fixture.start(id)
        fixture.processes[0].failures = 1; fixture.processes[1].failures = 1
        try { fixture.owner.close(); fail("cleanup failures lost") }
        catch (t: ResourceCleanupException) { assertEquals(2, t.failures.size) }
        assertEquals(listOf(1, 1, 1), fixture.processes.map { it.closes })
        fixture.owner.close()
        assertEquals(listOf(2, 2, 1), fixture.processes.map { it.closes })
        assertTrue(fixture.running.isEmpty())
    }
}

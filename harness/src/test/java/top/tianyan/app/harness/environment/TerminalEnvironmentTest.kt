package top.tianyan.app.harness.environment

import com.termux.terminal.TerminalSession
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.TerminalSessionEntity
import top.tianyan.app.core.database.TerminalSessionRepository
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.terminal.RemoteTerminalProcessFactory
import top.tianyan.app.runtime.terminal.TerminalProcess
import top.tianyan.app.runtime.terminal.TerminalProcessFactory
import top.tianyan.app.runtime.terminal.TerminalSessionClientRouter
import top.tianyan.app.runtime.terminal.TerminalSessionManager

class TerminalEnvironmentTest {
    @Test fun `remote world renders through the pluggable backend without starting local terminal`() = runBlocking {
        val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "terminal"))
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
            arrayOf(LinuxRuntime::class.java)) { _, method, _ -> error("local fallback: ${method.name}") } as LinuxRuntime
        var writes = 0
        var savedRows = emptyList<TerminalSessionEntity>()
        val repository = object : TerminalSessionRepository {
            override fun observeAll() = MutableStateFlow(emptyList<TerminalSessionEntity>())
            override suspend fun listAll() = savedRows
            override suspend fun nextOrder() = 0
            override suspend fun upsert(session: TerminalSessionEntity) { writes++ }
            override suspend fun delete(id: String) { writes++ }
            override suspend fun deleteAll() { writes++ }
        }
        var receivedName: String? = null
        val manager = TerminalSessionManager(runtime, repository, TerminalSessionClientRouter(),
            ExecutionEnvironmentFactory { _, _, _ -> world },
            TerminalProcessFactory { _, _ -> error("local terminal factory must not be used for a remote world") },
            RemoteTerminalProcessFactory { _, name, _ ->
                receivedName = name
                object : TerminalProcess {
                    private var alive = true
                    override val session: TerminalSession get() = error("native renderer is not started in unit tests")
                    override val isAlive get() = alive
                    override suspend fun finish() { alive = false }
                    override fun write(data: ByteArray) = Unit
                }
            })
        val handle = manager.createSession(id = "terminal", label = "remote")
        assertTrue(world.events.contains("pty:/workspace/p"))
        assertEquals("remote", receivedName)
        assertEquals("linux", handle.environmentLabel)
        assertEquals("/workspace/p", handle.workingDirectory)
        assertEquals(1, writes)
        assertEquals(1, manager.handles.value.size)
        assertEquals(0, world.closes)
        manager.closeAllSessions()
        assertEquals(1, world.closes)
        assertTrue(manager.handles.value.isEmpty())
        savedRows = listOf(TerminalSessionEntity("legacy", "old", "/srv/projects", 1, 0, "ssh:old"))
        manager.ensureActive()
        assertEquals(1, manager.handles.value.size)
        assertNotEquals("legacy", manager.handles.value.single().id)
        manager.closeAllSessions()
    }
}

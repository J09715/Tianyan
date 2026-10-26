package top.tianyan.app.harness.environment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.remote.SshConnector
import top.tianyan.app.runtime.shell.*

class SelectedExecutionEnvironmentFactoryTest {
    private val repository = object : RemoteServerRepository {
        override val servers = MutableStateFlow(emptyList<RemoteServer>())
        override val activeId = MutableStateFlow<String?>(null)
        override suspend fun credentials(id: String): SshCredentials = error("Must not read credentials")
        override suspend fun save(server: RemoteServer, credentials: SshCredentials) = Unit
        override suspend fun select(id: String?) { activeId.value = id }
        override suspend fun remove(id: String) = Unit
    }
    private val localWorld = object : ExecutionEnvironment {
        override val id = ExecutionEnvironmentId("local-proot", "debian", "/workspace", "parent")
        override val files: ExecutionFiles get() = error("Unused")
        override fun workingDirectory(explicit: String?) = explicit ?: id.workspace
        override suspend fun execute(command: ShellCommand): CommandResult = error("Unused")
        override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess = error("Unused")
        override suspend fun stopBackground(id: String) = false
        override fun listBackground() = emptyList<ManagedProcess>()
        override fun getBackgroundLogs(id: String) = emptyList<String>()
        override suspend fun openSession(config: SessionConfig): LinuxSession = error("Unused")
        override suspend fun openTerminal(config: SessionConfig): LinuxSession = error("Unused")
        override suspend fun close() = Unit
    }
    @Test fun `explicit inherited local distribution wins over new remote selection`() = runBlocking {
        repository.activeId.value = "another-server"
        var distribution: String? = null
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { _, _, target ->
            distribution = target; localWorld
        }, repository, SshConnector())
        assertSame(localWorld, factory.open("child", "/workspace", "debian"))
        assertEquals("debian", distribution)
    }
    @Test fun `removed remote server never falls back to phone execution`() = runBlocking {
        var localCalled = false
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { _, _, _ ->
            localCalled = true; localWorld
        }, repository, SshConnector())
        try { factory.open("child", "/workspace", "ssh:removed"); fail() }
        catch (_: IllegalStateException) { }
        assertFalse(localCalled)
    }
}

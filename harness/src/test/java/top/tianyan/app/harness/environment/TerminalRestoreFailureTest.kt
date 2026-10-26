package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.*
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.*
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.remote.SshConnector
import top.tianyan.app.runtime.terminal.*

class TerminalRestoreFailureTest {
    @Test fun `deleted remote profile does not block initialization of selected local terminal`() = runBlocking {
        val old = RemoteServer("deleted", "Deleted", "server-a", username = "agent", workspace = "/srv/projects", fingerprint = "SHA256:old")
        val saved = Json.encodeToString(SessionExecutionTarget.serializer(), SessionExecutionTarget(old.distributionId, "", old))
        val rows = mutableListOf(TerminalSessionEntity("old-terminal", "old", "/srv/projects", 1, 0, old.distributionId, saved))
        val repository = object : TerminalSessionRepository {
            override fun observeAll() = MutableStateFlow(rows.toList())
            override suspend fun listAll() = rows.toList()
            override suspend fun nextOrder() = 1
            override suspend fun upsert(session: TerminalSessionEntity) { rows.removeAll { it.id == session.id }; rows += session }
            override suspend fun delete(id: String) { rows.removeAll { it.id == id } }
            override suspend fun deleteAll() { rows.clear() }
        }
        val servers = object : RemoteServerRepository {
            override val servers = MutableStateFlow(emptyList<RemoteServer>())
            override val activeId = MutableStateFlow<String?>(null)
            override suspend fun credentials(id: String) = error("removed profile")
            override suspend fun save(server: RemoteServer, credentials: SshCredentials) = Unit
            override suspend fun select(id: String?) = Unit
            override suspend fun remove(id: String) = Unit
        }
        var localOpens = 0
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { owner, workspace, distribution ->
            localOpens++; FakeWorld(ExecutionEnvironmentId("local-proot", distribution!!, workspace, owner))
        }, servers, SshConnector(), SessionExecutionTargets(servers, localDistribution = { "ubuntu" }))
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            error("unexpected runtime call ${method.name}")
        } as LinuxRuntime
        val manager = TerminalSessionManager(runtime, repository, TerminalSessionClientRouter(), factory,
            remoteProcessFactory = RemoteTerminalProcessFactory { _, _, _ ->
                object : TerminalProcess {
                    override val session: com.termux.terminal.TerminalSession get() = error("no native renderer")
                    override val isAlive = true
                    override suspend fun finish() = Unit
                    override fun write(data: ByteArray) = Unit
                }
            })
        try {
            manager.ensureActive()
            assertEquals(1, localOpens)
            assertEquals(listOf("old-terminal"), manager.restoreFailures.value.map { it.id })
            assertEquals(saved, rows.single { it.id == "old-terminal" }.executionTargetJson)
            assertEquals("ubuntu", manager.handles.value.single().distributionId)
            manager.retryFailedRestorations()
            assertEquals("Retry must not duplicate live terminals", 1, localOpens)
            manager.removeFailedRestoration("old-terminal")
            assertTrue(manager.restoreFailures.value.isEmpty())
            assertEquals(1, rows.size)
            assertEquals(manager.handles.value.single().id, rows.single().id)
        } finally { manager.closeAllSessions() }
    }

    @Test fun `recoverable outage preserves original record and permits retry`() = runBlocking {
        val fixture = TerminalLifecycleFixture()
        val old = TerminalSessionEntity("offline", "original", "/workspace/original", 1, 0, "debian")
        fixture.repository.upsert(old)
        fixture.beforeOpen = { if (it == old.id) error("temporarily offline") }
        try {
            fixture.manager.ensureActive()
            assertEquals(old, fixture.repository.contents[old.id])
            assertEquals(1, fixture.manager.handles.value.size)
            assertEquals(old.id, fixture.manager.restoreFailures.value.single().id)
            fixture.beforeOpen = {}
            fixture.manager.retryFailedRestorations()
            assertEquals(2, fixture.manager.handles.value.size)
            assertTrue(fixture.manager.restoreFailures.value.isEmpty())
            assertEquals(old.workingDirectory, fixture.manager.handles.value.single { it.id == old.id }.workingDirectory)
        } finally { fixture.manager.closeAllSessions() }
    }

    @Test fun `cancellation does not create replacement terminal`() = runBlocking {
        val fixture = TerminalLifecycleFixture()
        fixture.repository.upsert(TerminalSessionEntity("cancelled", "old", "/root", 1, 0, "ubuntu"))
        fixture.beforeOpen = { throw kotlinx.coroutines.CancellationException("cancelled") }
        try {
            try { fixture.manager.ensureActive(); fail("must cancel") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(fixture.manager.handles.value.isEmpty())
            assertTrue(fixture.manager.restoreFailures.value.isEmpty())
            assertEquals(1, fixture.repository.contents.size)
        } finally { fixture.manager.closeAllSessions() }
    }

    @Test fun `unconfirmed failed creation cleanup blocks fallback until released`() = runBlocking {
        val fixture = TerminalLifecycleFixture()
        fixture.repository.upsert(TerminalSessionEntity("pending", "old", "/root", 1, 0, "ubuntu"))
        fixture.failCloses["pending"] = 1
        fixture.construct = { error("renderer setup failed") }
        try {
            try { fixture.manager.ensureActive(); fail("must retain unconfirmed cleanup") }
            catch (_: IllegalStateException) { }
            assertTrue(fixture.manager.handles.value.isEmpty())
            assertEquals("pending", fixture.manager.restoreFailures.value.single().id)
            fixture.construct = {}
            fixture.manager.retryFailedRestorations()
            assertEquals("pending", fixture.manager.handles.value.single().id)
            assertTrue(fixture.manager.restoreFailures.value.isEmpty())
        } finally { fixture.manager.closeAllSessions() }
    }
}

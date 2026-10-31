package top.tianyan.app.harness.environment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.*
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.*
import top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory
import top.tianyan.app.runtime.remote.SshConnector

class SessionExecutionTargetsTest {
    private val a = RemoteServer("a", "A", "server-a", username = "agent", workspace = "/srv/projects", fingerprint = "SHA256:a")
    private val b = a.copy(id = "b", host = "server-b", fingerprint = "SHA256:b")
    private val servers = object : RemoteServerRepository {
        override val servers = MutableStateFlow(listOf(a, b))
        override val activeId = MutableStateFlow<String?>(a.id)
        override suspend fun credentials(id: String) = SshCredentials("secret")
        override suspend fun save(server: RemoteServer, credentials: SshCredentials) = Unit
        override suspend fun select(id: String?) { activeId.value = id }
        override suspend fun remove(id: String) { servers.value = servers.value.filterNot { it.id == id } }
    }
    private val sessions = MemorySessions()

    @Test fun `startup captures persisted local selection and restart preserves it`() = runBlocking {
        servers.select(null)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val savedChoice = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1)
        val active = MutableStateFlow("ubuntu")
        val state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
        val selection = top.tianyan.app.runtime.environment.LocalDistributionSelection(savedChoice, active, state, scope)
        try {
            savedChoice.emit("debian")
            val targets = SessionExecutionTargets(servers, sessions, selection::capture)
            sessions.upsert(session("startup").copy(executionTargetJson = targets.capture("/workspace/demo")))
            active.value = "oracle"; state.value = RuntimeState.Ready
            assertEquals("debian", SessionExecutionTargets(servers, sessions, selection::capture).resolve("startup", "/workspace/demo").distribution)
            assertEquals("oracle", targets.resolve("terminal:new", "").distribution)
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun `remote capture never waits for local identity`() = runBlocking {
        val targets = SessionExecutionTargets(servers, sessions) { error("local selection must not be read") }
        val encoded = kotlinx.coroutines.withTimeout(2_000) { targets.capture("/workspace/demo") }
        sessions.upsert(session("remote-startup").copy(executionTargetJson = encoded))
        assertEquals(a, targets.resolve("remote-startup", "/workspace/demo").remote)
    }

    @Test fun `restart keeps remote host even after default becomes local or another server`() = runBlocking {
        sessions.upsert(session("one"))
        SessionExecutionTargets(servers, sessions).resolve("one", "/workspace/demo")
        servers.select(b.id)
        assertEquals(a, SessionExecutionTargets(servers, sessions).resolve("one", "/workspace/demo").remote)
        servers.select(null)
        assertEquals(a, SessionExecutionTargets(servers, sessions).resolve("one", "/workspace/demo").remote)
        sessions.upsert(session("local"))
        SessionExecutionTargets(servers, sessions) { "ubuntu" }.resolve("local", "/workspace/demo")
        servers.select(a.id)
        assertEquals("ubuntu", SessionExecutionTargets(servers, sessions).resolve("local", "/workspace/demo").distribution)
    }
    @Test fun `child rejects edited host instead of reconnecting parent identity to new server`() = runBlocking {
        sessions.upsert(session("parent"))
        val targets = SessionExecutionTargets(servers, sessions)
        targets.resolve("parent", "/workspace/demo")
        servers.servers.value = listOf(a.copy(host = "server-b", fingerprint = "SHA256:b"), b)
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { _, _, _ -> error("local fallback") }, servers, SshConnector(), targets)
        try { factory.open("parent::child", "/workspace/demo", a.distributionId); fail("edited host was accepted") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("档案已更改")) }
    }
    @Test fun `legacy local history ignores remote default and persists binding for restart and children`() = runBlocking {
        sessions.upsert(session("legacy").copy(executionTargetJson = "unbound"))
        var local = "ubuntu"
        val targets = SessionExecutionTargets(servers, sessions) { local }
        val target = targets.resolve("legacy", "/workspace/demo")
        assertEquals("ubuntu", target.distribution)
        assertNull(target.remote)
        assertNotEquals("unbound", sessions.findById("legacy")!!.executionTargetJson)
        local = "debian"
        servers.select(b.id)
        assertEquals(target, SessionExecutionTargets(servers, sessions) { local }.resolve("legacy", "/workspace/demo"))
        assertEquals(target, targets.resolve("legacy::child", "/workspace/demo", "ubuntu"))
    }
    @Test fun `portable imports and missing durable owners fail closed`() = runBlocking {
        sessions.upsert(session("imported").copy(executionTargetJson = "restore-required"))
        for (owner in listOf("imported", "removed")) {
            try { SessionExecutionTargets(servers, sessions).resolve(owner, "/workspace/demo"); fail("unsafe identity inferred") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun `standalone terminal owners can bind without pretending to be saved chat sessions`() = runBlocking {
        val targets = SessionExecutionTargets(servers, sessions)
        val first = targets.resolve("terminal:one", "/workspace/demo", a.distributionId)
        servers.select(b.id)
        assertEquals(first, targets.resolve("terminal:one", "/workspace/demo", a.distributionId))
    }
    @Test fun `terminal restart restores original host and refuses edited profile`() = runBlocking {
        val saved = SessionExecutionTargets(servers, sessions).capture("", a.distributionId)
        servers.select(b.id)
        val targets = SessionExecutionTargets(servers, sessions)
        targets.restore("terminal:one", "", saved)
        assertEquals(a, targets.resolve("terminal:one", "", a.distributionId).remote)
        servers.servers.value = listOf(a.copy(host = "replacement"), b)
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { _, _, _ -> error("local fallback") }, servers, SshConnector(), targets)
        try { factory.open("terminal:one", "", a.distributionId); fail("terminal moved to replacement host") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("档案已更改")) }
    }
    @Test fun `standalone MCP acquisition rejects server switches before opening remote or phone`() = runBlocking {
        val scope = top.tianyan.app.runtime.remote.remoteConnectionScope(a)
        val factory = SelectedExecutionEnvironmentFactory(ExecutionEnvironmentFactory { _, _, _ -> error("phone fallback") }, servers, SshConnector())
        for ((index, selected) in listOf(b.id, null).withIndex()) {
            servers.select(selected)
            try { factory.open("mcp:test-$index@environment:$scope", "", null); fail("wrong backend accepted") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("服务器已改变")) }
        }
    }
    @Test fun `fork and child inherit original binding while new sessions capture latest default`() = runBlocking {
        val targets = SessionExecutionTargets(servers, sessions)
        sessions.upsert(session("parent").copy(executionTargetJson = targets.capture("/workspace/demo")))
        servers.select(b.id)
        sessions.upsert(sessions.findById("parent")!!.copy(id = "fork"))
        assertEquals(a, targets.resolve("parent::child", "/workspace/demo", a.distributionId).remote)
        assertEquals(a, SessionExecutionTargets(servers, sessions).resolve("fork", "/workspace/demo").remote)
        sessions.upsert(session("new").copy(executionTargetJson = targets.capture("/workspace/demo")))
        assertEquals(b, targets.resolve("new", "/workspace/demo").remote)
    }
    private fun session(id: String) = HarnessSessionEntity(id, id, 1, 1, null, workspace = "/workspace/demo")
}

private class MemorySessions : HarnessSessionRepository {
    private val values = linkedMapOf<String, HarnessSessionEntity>()
    override suspend fun upsert(session: HarnessSessionEntity) { values[session.id] = session }
    override suspend fun findById(id: String) = values[id]
    override fun observeAll() = MutableStateFlow(values.values.toList())
    override suspend fun listAll() = values.values.toList()
    override suspend fun deleteSession(id: String) { values.remove(id) }
    override suspend fun touch(id: String, updatedAt: Long) = Unit
    override suspend fun rename(id: String, title: String, updatedAt: Long) = Unit
    override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = Unit
    override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = Unit
    override suspend fun setRunMode(id: String, runMode: String, updatedAt: Long) = Unit
    override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) = Unit
    override suspend fun countInRange(start: Long?, end: Long?) = values.size
}

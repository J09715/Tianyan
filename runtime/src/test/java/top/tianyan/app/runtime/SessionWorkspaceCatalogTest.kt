package top.tianyan.app.runtime

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.datastore.REMOTE_CONFIGURATION_UNAVAILABLE
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.runtime.environment.ExecutionFileEntry
import top.tianyan.app.runtime.environment.LocalBackendAccess

class SessionWorkspaceCatalogTest {
    private val profile = RemoteServer("remote", "Remote", "host", username = "agent", workspace = "/srv/projects")
    private val repository = object : RemoteServerRepository {
        override val servers = MutableStateFlow(listOf(profile))
        override val activeId = MutableStateFlow<String?>(profile.id)
        override suspend fun credentials(id: String) = error("not needed")
        override suspend fun save(server: RemoteServer, credentials: SshCredentials) = Unit
        override suspend fun select(id: String?) { activeId.value = id }
        override suspend fun remove(id: String) = Unit
    }
    @Test fun `remote-only projects are selectable and phone projects are never queried`() = runBlocking {
        val catalog = SessionWorkspaceCatalog(repository, { error("phone project query") }, { target ->
            assertEquals(profile, target); listOf(ExecutionFileEntry("remote-only", true, 0, 0))
        })
        val state = catalog.observe().first { !it.loading }
        assertEquals("/workspace/remote-only", state.projects.single().linuxPath)
        assertEquals("/srv/projects/remote-only", state.projects.single().path)
        catalog.validate(state, "/workspace/remote-only")
        try { catalog.validate(state, "/workspace/phone-only"); fail("phone project accepted") }
        catch (_: IllegalArgumentException) { }
        repository.select(null)
        try { catalog.validate(state, "/workspace/remote-only"); fail("stale server accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `connection failure clears projects without local fallback`() = runBlocking {
        val catalog = SessionWorkspaceCatalog(repository, { error("phone fallback") }, { error("SSH unavailable") })
        val state = catalog.observe().first { !it.loading }
        assertTrue(state.projects.isEmpty()); assertNotNull(state.error)
        try { catalog.validate(state, "/workspace/old-project"); fail("stale project accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `local operation guard blocks remote and unavailable states before any mutation`() = runBlocking {
        val access = LocalBackendAccess(repository)
        var mutations = 0
        fun mutate() { access.requireLocal(); mutations++ }
        for (id in listOf(profile.id, REMOTE_CONFIGURATION_UNAVAILABLE)) {
            repository.select(id)
            try { mutate(); fail("phone mutation allowed") } catch (_: IllegalStateException) { }
        }
        assertEquals(0, mutations)
        repository.select(null); mutate(); assertEquals(1, mutations)
    }
}

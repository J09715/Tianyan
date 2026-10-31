package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.runtime.remote.HostKeyConfirmationRequired
import top.tianyan.app.runtime.remote.SshConnectionTester

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteServerViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repository : RemoteServerRepository {
        override val servers = MutableStateFlow(emptyList<RemoteServer>())
        override val activeId = MutableStateFlow<String?>("existing-server")
        val saved = mutableListOf<RemoteServer>()
        override suspend fun credentials(id: String): SshCredentials = error("unused")
        override suspend fun save(server: RemoteServer, credentials: SshCredentials) { saved += server }
        override suspend fun select(id: String?) { activeId.value = id }
        override suspend fun remove(id: String) = Unit
    }
    private val server = RemoteServer("server", "Server", "example.test", username = "root", workspace = "/root")
    private val credentials = SshCredentials("password")
    private fun tester() = SshConnectionTester { target, _ ->
        if (target.fingerprint.isBlank()) throw HostKeyConfirmationRequired("SHA256:fixture")
        check(target.fingerprint == "SHA256:fixture")
    }
    @Test fun `test and fingerprint confirmation do not save or change primary system`() = runTest {
        val repository = Repository()
        val viewModel = RemoteServerViewModel(repository, tester())
        viewModel.test(server, credentials)
        advanceUntilIdle()
        assertEquals("SHA256:fixture", viewModel.connectionState.value.fingerprint)
        viewModel.confirmFingerprint()
        advanceUntilIdle()
        assertTrue(viewModel.connectionState.value.tested)
        assertFalse(viewModel.connectionState.value.saved)
        assertTrue(repository.saved.isEmpty())
        assertEquals("existing-server", repository.activeId.value)
        viewModel.connect(server, credentials)
        advanceUntilIdle()
        assertTrue(viewModel.connectionState.value.saved)
        assertEquals(1, repository.saved.size)
        assertEquals("SHA256:fixture", repository.saved.single().fingerprint)
        assertEquals("existing-server", repository.activeId.value)
    }
    @Test fun `changed host cannot reuse the previously confirmed fingerprint`() = runTest {
        val viewModel = RemoteServerViewModel(Repository(), tester())
        viewModel.test(server, credentials)
        advanceUntilIdle()
        viewModel.confirmFingerprint()
        advanceUntilIdle()
        viewModel.connect(server.copy(host = "different.test"), credentials)
        advanceUntilIdle()
        assertNotNull(viewModel.connectionState.value.fingerprint)
        assertFalse(viewModel.connectionState.value.saved)
    }
    @Test fun `failed test does not report successful save`() = runTest {
        val repository = Repository()
        val viewModel = RemoteServerViewModel(repository, SshConnectionTester { _, _ -> error("offline") })
        viewModel.connect(server.copy(fingerprint = "SHA256:fixture"), credentials)
        advanceUntilIdle()
        assertFalse(viewModel.connectionState.value.saved)
        assertNotNull(viewModel.connectionState.value.message)
        assertTrue(repository.saved.isEmpty())
    }
}

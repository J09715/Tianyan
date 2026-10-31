package top.tianyan.app.harness.mcp

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.environment.LocalDistributionSelection
import top.tianyan.app.runtime.remote.remoteConnectionScope

class StandaloneMcpScopeTest {
    private val profile = RemoteServer("host", "Host", "server", username = "agent")
    private val active = MutableStateFlow<String?>(null)
    private val servers = Proxy.newProxyInstance(RemoteServerRepository::class.java.classLoader,
        arrayOf(RemoteServerRepository::class.java)) { _, method, _ ->
        when (method.name) { "getActiveId" -> active; "getServers" -> MutableStateFlow(listOf(profile)); else -> error(method.name) }
    } as RemoteServerRepository

    @Test fun `local settings capture waits for saved distribution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = MutableSharedFlow<String>(replay = 1)
        val selection = LocalDistributionSelection(persisted, MutableStateFlow("ubuntu"), MutableStateFlow(RuntimeState.NotInitialized), scope)
        val standalone = StandaloneMcpScope(servers, selection::current, selection.changes)
        try {
            val key = async(start = CoroutineStart.UNDISPATCHED) { standalone.captureKey() }
            assertFalse(key.isCompleted)
            persisted.emit("debian")
            assertEquals("local:debian", withTimeout(2_000) { key.await() })
        } finally { scope.cancel() }
    }

    @Test fun `remote settings key and status changes do not wait for phone selection`() = runBlocking {
        active.value = profile.id
        val standalone = StandaloneMcpScope(servers, { error("phone selection not loaded") }, emptyFlow())
        val key = remoteConnectionScope(profile)
        assertEquals(key, withTimeout(2_000) { standalone.captureKey() })
        assertEquals(key, withTimeout(2_000) { standalone.changes.first() })
    }
}

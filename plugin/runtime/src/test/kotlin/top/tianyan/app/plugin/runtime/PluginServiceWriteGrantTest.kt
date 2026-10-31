package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginServiceWriteGrantTest {
    private val capability = PluginCapabilityInput.SERVICE_WRITE
    private val identity = PluginCapabilityIdentity("example.github", "1.0.0", 1, "https://page", "android:local")

    @Test fun writesCannotInheritAReadOrSessionGrantAndExecuteOnlyTheFrozenRequest() = runTest {
        val executed = mutableListOf<String>()
        val session = PluginCapabilitySession(identity, mapOf(capability to { call -> executed += call.text!!; "ok" }),
            longLivedCapabilities = setOf(capability))
        val body = "api/auth/token\n{\"token\":\"original\"}"
        val first = async { session.call(PluginCapabilityCall("first", capability, body), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        assertEquals("permission_denied", first.await().text)
        assertTrue(executed.isEmpty())
        val second = async { session.call(PluginCapabilityCall("second", capability, body), identity.origin, true) }
        runCurrent()
        val prompt = session.prompt.value!!
        assertEquals(body, prompt.call.text)
        assertEquals("busy", session.call(PluginCapabilityCall("other", capability, "api/auth/token\nreplacement"), identity.origin, true).text)
        session.decide(prompt.token, PluginGrantChoice.ONCE)
        assertTrue(second.await().success)
        assertEquals(listOf(body), executed)
        assertEquals("replayed_request", session.call(PluginCapabilityCall("second", capability, body), identity.origin, true).text)
    }
}
